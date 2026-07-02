package net.osparty.api;

import net.osparty.model.Party;
import net.osparty.model.PartyDelta;
import net.osparty.model.PartyRequest;
import com.google.gson.Gson;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * The plugin's single, session-long WebSocket to the party API. It does double duty:
 *
 * <ul>
 *   <li><b>Search read</b> — when a listener is registered ({@link #setSearchListener})
 *       it subscribes to the live party list and re-emits the full list on every change.</li>
 *   <li><b>Host write</b> — {@link #host}/{@link #update}/{@link #unhost} advertise and
 *       mutate the host's ad; the open connection itself is the ad's keep-alive (the server
 *       refreshes its TTL while we're connected), so there's no periodic heartbeat.</li>
 * </ul>
 *
 * <p>Reconnects with jittered backoff. On each (re)connect it re-subscribes (if searching)
 * and, if hosting, sends {@code resume} to reclaim the ad by id+key — so a brief drop keeps
 * the same party. Opened/closed once per plugin lifetime ({@link #start}/{@link #stop}).
 */
@Slf4j
@Singleton
public class PartySocket extends WebSocketListener
{
	private static final long MIN_BACKOFF_MS = 1_000;
	private static final long MAX_BACKOFF_MS = 30_000;

	private final OkHttpClient client;
	private final Gson gson;
	private final String url;

	private final Map<String, Party> parties = new LinkedHashMap<>();
	private final ScheduledExecutorService reconnects = Executors.newSingleThreadScheduledExecutor(r ->
	{
		Thread t = new Thread(r, "osparty-socket");
		t.setDaemon(true);
		return t;
	});

	private final List<Consumer<List<Party>>> searchListeners = new CopyOnWriteArrayList<>();
	// Activity to scope the live list to (null = all). Kept across reconnects so onOpen re-sends it.
	private volatile String subscribeActivity;
	// One-shot lookups awaiting a directed byCode/byHost reply, keyed by the echoed code/host.
	private final Map<String, Consumer<Party>> pendingByCode = new ConcurrentHashMap<>();
	private final Map<String, Consumer<Party>> pendingByHost = new ConcurrentHashMap<>();
	// Host createVoiceChannel requests awaiting a voiceChannel reply (or a matching error), keyed by party id.
	private final Map<String, VoicePending> pendingVoiceChannel = new ConcurrentHashMap<>();
	private volatile boolean started;
	private volatile boolean closed;
	private volatile boolean connected;
	private volatile WebSocket webSocket;
	private volatile int onlineUsers = -1;
	private int attempt;

	// Hosting state (kept across reconnects so we can resume the same ad).
	private volatile String hostingId;
	private volatile String hostingKey;
	private volatile HostPending pendingHost;
	private volatile String lastSentPatch;

	@Inject
	PartySocket(OkHttpClient httpClient, Gson gson)
	{
		// A WebSocket must not inherit the REST read timeout; the ping keeps it alive
		// through OkHttp and any proxy in between.
		this.client = httpClient.newBuilder()
			.pingInterval(Duration.ofSeconds(20))
			.readTimeout(Duration.ZERO)
			.build();
		this.gson = gson;
		this.url = buildWsUrl();
	}

	private static String buildWsUrl()
	{
		// OkHttp upgrades the https URL to a WebSocket; path mirrors WebSocketConfig.WS_PATH.
		HttpUrl base = HttpUrl.parse(PartyApiClient.apiBaseUrl());
		if (base == null)
		{
			throw new IllegalStateException("Invalid API base URL: " + PartyApiClient.apiBaseUrl());
		}
		return base.newBuilder()
			.addPathSegment("api").addPathSegment("v1")
			.addPathSegment("ws").addPathSegment("parties")
			.build().toString();
	}

	/** Open the connection (once). Called when the plugin starts. */
	public synchronized void start()
	{
		if (started)
		{
			return;
		}
		started = true;
		closed = false;
		connect();
	}

	/** Close the connection for good. Called when the plugin stops. */
	public synchronized void stop()
	{
		closed = true;
		connected = false;
		reconnects.shutdownNow();
		WebSocket socket = webSocket;
		if (socket != null)
		{
			socket.close(1000, "plugin stopped");
		}
	}

	public boolean isConnected()
	{
		return connected && !closed;
	}

	/** @return the server-reported count of connected plugin clients, or {@code -1} if unknown. */
	public int onlineUsers()
	{
		return connected ? onlineUsers : -1;
	}

	/** Force an immediate reconnect attempt (e.g. from a UI "Reconnect" button). */
	public synchronized void reconnectNow()
	{
		if (closed || connected)
		{
			return;
		}
		attempt = 0;
		connect();
	}

	private synchronized void connect()
	{
		if (closed)
		{
			return;
		}
		webSocket = client.newWebSocket(new Request.Builder().url(url).build(), this);
	}

	// --- Search read ---

	/** Register the listener that wants the live party list; pushes the current list now. */
	public void setSearchListener(Consumer<List<Party>> listener)
	{
		setSearchListener(listener, null);
	}

	/**
	 * Register the live-list listener, scoping the server feed to a single activity ({@code null} =
	 * all). Scoping cuts the server's fan-out to just the matching ads.
	 */
	public void setSearchListener(Consumer<List<Party>> listener, String activity)
	{
		subscribeActivity = blankToNull(activity);
		searchListeners.add(listener);
		if (connected)
		{
			send(subscribeFrame());
		}
		listener.accept(snapshot());
	}

	/**
	 * Re-scope the live feed to a different activity ({@code null} = all) without re-registering the
	 * listener. The server replies with a fresh snapshot for the new scope.
	 */
	public void setSearchActivity(String activity)
	{
		String next = blankToNull(activity);
		if (Objects.equals(next, subscribeActivity))
		{
			return;
		}
		subscribeActivity = next;
		if (connected && !searchListeners.isEmpty())
		{
			send(subscribeFrame());
		}
	}

	/** Stop receiving the list firehose (the connection stays up for hosting). */
	public void clearSearchListener(Consumer<List<Party>> listener)
	{
		if (searchListeners.remove(listener) && searchListeners.isEmpty())
		{
			subscribeActivity = null;
			if (connected)
			{
				send(gson.toJson(Collections.singletonMap("type", "unsubscribe")));
			}
		}
	}

	// --- Host write ---

	/** Advertise a new ad over the socket; the {@code hosted} ack carries the server's id. */
	public void host(PartyRequest request, String key, Consumer<Party> onSuccess, Consumer<Throwable> onError)
	{
		hostingKey = key;
		lastSentPatch = null;
		pendingHost = new HostPending(onSuccess, onError);
		if (connected)
		{
			send(gson.toJson(new HostFrame(request, key)));
		}
		else
		{
			pendingHost = null;
			onError.accept(new IOException("socket not connected"));
		}
	}

	/** Record an ad created out-of-band (REST fallback) so a reconnect resumes it. */
	public void setHosting(String id, String key)
	{
		hostingId = id;
		hostingKey = key;
		lastSentPatch = null;
		if (connected)
		{
			send(resumeFrame(id, key));
		}
	}

	/** Push a partial change to the hosted ad (deduped — only sent when it differs). */
	public void update(String id, String key, Object patch)
	{
		hostingId = id;
		hostingKey = key;
		String json = gson.toJson(patch);
		if (json.equals(lastSentPatch))
		{
			return;
		}
		lastSentPatch = json;
		if (connected)
		{
			send(gson.toJson(new UpdateFrame(id, key, patch)));
		}
	}

	/**
	 * Push a host-initiated edit to the hosted ad. Unlike {@link #update}, this is a one-off
	 * user action so it always sends (no dedup), and it resets {@code lastSentPatch} so the
	 * next keep-alive heartbeat re-sends its live fields against the new baseline.
	 */
	public void edit(String id, String key, Object patch)
	{
		hostingId = id;
		hostingKey = key;
		lastSentPatch = null;
		if (connected)
		{
			send(gson.toJson(new UpdateFrame(id, key, patch)));
		}
	}

	/** Disband the hosted ad. */
	public void unhost(String id, String key)
	{
		if (connected)
		{
			send(gson.toJson(new MutateFrame("unhost", id, key)));
		}
		if (id != null && id.equals(hostingId))
		{
			hostingId = null;
			hostingKey = null;
			lastSentPatch = null;
		}
	}

	// --- One-shot lookups (request/response over the socket) ---

	/** Look up a party by invite code; {@code onResult} gets the party, or null if none/offline. */
	public void getByCode(String code, Consumer<Party> onResult)
	{
		if (code == null || !connected)
		{
			onResult.accept(null);
			return;
		}
		pendingByCode.put(code, onResult);
		send(gson.toJson(new LookupFrame("getByCode", code, null)));
	}

	/** Look up the ad hosted by a player; {@code onResult} gets the party, or null if none/offline. */
	public void getByHost(String host, Consumer<Party> onResult)
	{
		if (host == null || !connected)
		{
			onResult.accept(null);
			return;
		}
		pendingByHost.put(host, onResult);
		send(gson.toJson(new LookupFrame("getByHost", null, host)));
	}

	/**
	 * Host action: ask the backend's Discord bot to provision a voice channel for the hosted party.
	 * {@code onUrl} receives the invite URL to share with members; {@code onError} fires if the socket
	 * is down or the server reports failure. Idempotent server-side, so a retry just echoes the URL.
	 * Callbacks run on the socket reader thread — marshal to the EDT in the UI.
	 */
	public void createVoiceChannel(String id, String key, Consumer<String> onUrl, Consumer<Throwable> onError)
	{
		if (id == null || !connected)
		{
			onError.accept(new IOException("socket not connected"));
			return;
		}
		pendingVoiceChannel.put(id, new VoicePending(onUrl, onError));
		send(gson.toJson(new VoiceFrame(id, key)));
	}

	// --- WebSocket callbacks ---

	@Override
	public void onOpen(WebSocket socket, Response response)
	{
		connected = true;
		attempt = 0;
		if (!searchListeners.isEmpty())
		{
			send(subscribeFrame());
		}
		String id = hostingId;
		if (id != null)
		{
			send(resumeFrame(id, hostingKey));
		}
	}

	@Override
	public void onMessage(WebSocket socket, String text)
	{
		Frame frame;
		try
		{
			frame = gson.fromJson(text, Frame.class);
		}
		catch (Exception e)
		{
			return;
		}
		if (frame == null || frame.type == null)
		{
			return;
		}
		switch (frame.type)
		{
			case "snapshot":
				synchronized (parties)
				{
					parties.clear();
					putAll(frame.parties);
				}
				emitSearch();
				break;
			case "created":
			case "updated":
				if (frame.party != null && frame.party.getId() != null)
				{
					synchronized (parties)
					{
						parties.put(frame.party.getId(), frame.party);
					}
					emitSearch();
				}
				break;
			case "removed":
				if (frame.id != null)
				{
					synchronized (parties)
					{
						parties.remove(frame.id);
					}
					emitSearch();
				}
				break;
			case "batch":
				applyBatch(frame);
				break;
			case "hosted":
				handleHosted(frame.party);
				break;
			case "gone":
				handleGone(frame.id);
				break;
			case "voiceChannel":
				completeVoiceChannel(frame.id, frame.url);
				break;
			case "error":
				handleError(frame.id, frame.detail);
				break;
			case "byCode":
				completeLookup(pendingByCode, frame.id, frame.party);
				break;
			case "byHost":
				completeLookup(pendingByHost, frame.id, frame.party);
				break;
			case "presence":
				onlineUsers = frame.online;
				break;
			default:
				break;
		}
	}

	@Override
	public void onClosing(WebSocket socket, int code, String reason)
	{
		socket.close(1000, null);
	}

	@Override
	public void onClosed(WebSocket socket, int code, String reason)
	{
		connected = false;
		scheduleReconnect();
	}

	@Override
	public void onFailure(WebSocket socket, Throwable t, Response response)
	{
		connected = false;
		HostPending pending = pendingHost;
		if (pending != null && !closed)
		{
			pendingHost = null;
			pending.onError.accept(t);
		}
		if (!closed)
		{
			log.debug("Party socket failed ({}); will retry", t.toString());
		}
		scheduleReconnect();
	}

	/**
	 * Apply a whole tick's changes from a {@code batch} frame: full {@code created} parties are
	 * upserted, {@code updated} deltas are merged into the party we already hold, and {@code removed}
	 * ids are dropped — all under one lock, then a single re-emit of the list.
	 */
	private void applyBatch(Frame frame)
	{
		boolean changed = false;
		synchronized (parties)
		{
			if (frame.created != null)
			{
				for (Party party : frame.created)
				{
					if (party != null && party.getId() != null)
					{
						parties.put(party.getId(), party);
						changed = true;
					}
				}
			}
			if (frame.updated != null)
			{
				for (PartyDelta delta : frame.updated)
				{
					if (delta == null || delta.getId() == null)
					{
						continue;
					}
					Party existing = parties.get(delta.getId());
					if (existing != null)
					{
						// Unknown ids are ignored; the next snapshot (e.g. on reconnect) heals the gap.
						delta.applyTo(existing);
						changed = true;
					}
				}
			}
			if (frame.removed != null)
			{
				for (String id : frame.removed)
				{
					if (id != null && parties.remove(id) != null)
					{
						changed = true;
					}
				}
			}
		}
		if (changed)
		{
			emitSearch();
		}
	}

	private void handleHosted(Party party)
	{
		if (party == null || party.getId() == null)
		{
			return;
		}
		hostingId = party.getId();
		HostPending pending = pendingHost;
		pendingHost = null;
		if (pending != null)
		{
			pending.onSuccess.accept(party);
		}
	}

	private void handleGone(String id)
	{
		// The grace window lapsed before we reconnected — the ad is gone server-side.
		if (id != null && id.equals(hostingId))
		{
			log.debug("Hosted ad {} expired before resume; clearing hosting state", id);
			hostingId = null;
			hostingKey = null;
			lastSentPatch = null;
		}
	}

	private void handleError(String id, String detail)
	{
		// An error carrying an id may be the rejection of a pending voice-channel request; route it there
		// first so its onError fires (rather than being mistaken for a host rejection or just logged).
		if (id != null)
		{
			VoicePending voice = pendingVoiceChannel.remove(id);
			if (voice != null)
			{
				voice.onError.accept(new IOException("voice channel failed: " + detail));
				return;
			}
		}
		HostPending pending = pendingHost;
		if (pending != null)
		{
			pendingHost = null;
			pending.onError.accept(new IOException("host rejected: " + detail));
		}
		else
		{
			log.debug("Party socket error frame: {}", detail);
		}
	}

	private void completeVoiceChannel(String id, String url)
	{
		if (id == null)
		{
			return;
		}
		VoicePending pending = pendingVoiceChannel.remove(id);
		if (pending != null)
		{
			pending.onUrl.accept(url);
		}
	}

	private void scheduleReconnect()
	{
		if (closed)
		{
			return;
		}
		long base = Math.min(MAX_BACKOFF_MS, MIN_BACKOFF_MS << Math.min(attempt, 5));
		long jitter = ThreadLocalRandom.current().nextLong(MIN_BACKOFF_MS);
		attempt++;
		try
		{
			reconnects.schedule(this::connect, base + jitter, TimeUnit.MILLISECONDS);
		}
		catch (RejectedExecutionException ignored)
		{
			// executor shut down by stop()
		}
	}

	private void emitSearch()
	{
		if (searchListeners.isEmpty())
		{
			return;
		}
		List<Party> snap = snapshot();
		for (Consumer<List<Party>> listener : searchListeners)
		{
			listener.accept(snap);
		}
	}

	private static void completeLookup(Map<String, Consumer<Party>> pending, String key, Party party)
	{
		if (key == null)
		{
			return;
		}
		Consumer<Party> callback = pending.remove(key);
		if (callback != null)
		{
			callback.accept(party);
		}
	}

	private List<Party> snapshot()
	{
		synchronized (parties)
		{
			return new ArrayList<>(parties.values());
		}
	}

	private void putAll(Party[] list)
	{
		if (list != null)
		{
			for (Party party : list)
			{
				if (party != null && party.getId() != null)
				{
					parties.put(party.getId(), party);
				}
			}
		}
	}

	private void send(String json)
	{
		WebSocket socket = webSocket;
		if (socket != null)
		{
			socket.send(json);
		}
	}

	private String subscribeFrame()
	{
		String activity = subscribeActivity;
		if (activity == null)
		{
			return gson.toJson(Collections.singletonMap("type", "subscribe"));
		}
		Map<String, String> frame = new LinkedHashMap<>();
		frame.put("type", "subscribe");
		frame.put("activity", activity);
		return gson.toJson(frame);
	}

	private static String blankToNull(String value)
	{
		return (value == null || value.isBlank()) ? null : value;
	}

	private String resumeFrame(String id, String key)
	{
		return gson.toJson(new MutateFrame("resume", id, key));
	}

	private static final class HostPending
	{
		final Consumer<Party> onSuccess;
		final Consumer<Throwable> onError;

		HostPending(Consumer<Party> onSuccess, Consumer<Throwable> onError)
		{
			this.onSuccess = onSuccess;
			this.onError = onError;
		}
	}

	/** A server frame; only the fields relevant to {@link #type} are populated. */
	private static final class Frame
	{
		String type;
		long version;
		Party[] parties;
		Party party;
		String id;
		String detail;
		// "voiceChannel" frame: the Discord invite URL for the hosted party's provisioned channel.
		String url;
		// "batch" frame: a tick's worth of changes, applied together.
		Party[] created;
		PartyDelta[] updated;
		String[] removed;
		// "presence" frame: the global count of connected plugin clients.
		int online;
	}

	// Outbound frame shapes (Gson omits null fields, so a patch carries only what's set).

	private static final class HostFrame
	{
		final String type = "host";
		final PartyRequest request;
		final String key;

		HostFrame(PartyRequest request, String key)
		{
			this.request = request;
			this.key = key;
		}
	}

	private static final class UpdateFrame
	{
		final String type = "update";
		final String id;
		final String key;
		final Object patch;

		UpdateFrame(String id, String key, Object patch)
		{
			this.id = id;
			this.key = key;
			this.patch = patch;
		}
	}

	private static final class MutateFrame
	{
		final String type;
		final String id;
		final String key;

		MutateFrame(String type, String id, String key)
		{
			this.type = type;
			this.id = id;
			this.key = key;
		}
	}

	private static final class LookupFrame
	{
		final String type;
		final String code;
		final String host;

		LookupFrame(String type, String code, String host)
		{
			this.type = type;
			this.code = code;
			this.host = host;
		}
	}

	private static final class VoiceFrame
	{
		final String type = "createVoiceChannel";
		final String id;
		final String key;

		VoiceFrame(String id, String key)
		{
			this.id = id;
			this.key = key;
		}
	}

	private static final class VoicePending
	{
		final Consumer<String> onUrl;
		final Consumer<Throwable> onError;

		VoicePending(Consumer<String> onUrl, Consumer<Throwable> onError)
		{
			this.onUrl = onUrl;
			this.onError = onError;
		}
	}
}
