package net.osparty.api;

import net.osparty.model.Party;
import net.osparty.model.PartyRequest;
import com.google.gson.Gson;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

	private volatile Consumer<List<Party>> searchListener;
	private volatile boolean started;
	private volatile boolean closed;
	private volatile boolean connected;
	private volatile WebSocket webSocket;
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
		searchListener = listener;
		if (connected)
		{
			send(subscribeFrame());
		}
		listener.accept(snapshot());
	}

	/** Stop receiving the list firehose (the connection stays up for hosting). */
	public void clearSearchListener(Consumer<List<Party>> listener)
	{
		if (searchListener == listener)
		{
			searchListener = null;
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

	// --- WebSocket callbacks ---

	@Override
	public void onOpen(WebSocket socket, Response response)
	{
		connected = true;
		attempt = 0;
		if (searchListener != null)
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
			case "hosted":
				handleHosted(frame.party);
				break;
			case "gone":
				handleGone(frame.id);
				break;
			case "error":
				handleError(frame.detail);
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

	private void handleError(String detail)
	{
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
		Consumer<List<Party>> listener = searchListener;
		if (listener != null)
		{
			listener.accept(snapshot());
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
		return gson.toJson(Collections.singletonMap("type", "subscribe"));
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
}
