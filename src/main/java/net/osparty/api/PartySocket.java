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
 * The plugin's single, session-long WebSocket to the party API: search reads and host writes both
 * run over it, and the open connection is the host ad's keep-alive. Reconnects with jittered backoff,
 * re-subscribing and resuming the hosted ad on each (re)connect.
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
	// Recreated on each start(): stop() shuts the executor down for good.
	private volatile ScheduledExecutorService reconnects;

	private static ScheduledExecutorService newReconnectExecutor()
	{
		return Executors.newSingleThreadScheduledExecutor(r ->
		{
			Thread t = new Thread(r, "osparty-socket");
			t.setDaemon(true);
			return t;
		});
	}

	private final List<Consumer<List<Party>>> searchListeners = new CopyOnWriteArrayList<>();
	// Activity to scope the live list to (null = all). Kept across reconnects so onOpen re-sends it.
	private volatile String subscribeActivity;
	// One-shot lookups awaiting a directed byCode/byHost reply, keyed by the echoed code/host.
	private final Map<String, Consumer<Party>> pendingByCode = new ConcurrentHashMap<>();
	private final Map<String, Consumer<Party>> pendingByHost = new ConcurrentHashMap<>();
	// Host createVoiceChannel requests awaiting a voiceChannel reply (or a matching error), keyed by party id.
	private final Map<String, VoicePending> pendingVoiceChannel = new ConcurrentHashMap<>();
	// Discord link: the in-flight startDiscordLink request, and getDiscordLink polls keyed by accountHash.
	private volatile LinkUrlPending pendingLinkUrl;
	private final Map<Long, Consumer<DiscordLinkStatus>> pendingLinkStatus = new ConcurrentHashMap<>();
	// Member requestVoiceAccess calls awaiting a voiceAccess ack (or matching error), keyed by party id.
	private final Map<String, VoicePending> pendingVoiceAccess = new ConcurrentHashMap<>();
	// Host transferHost calls awaiting a transferred ack (or matching error), keyed by party id.
	private final Map<String, VoicePending> pendingTransfer = new ConcurrentHashMap<>();
	// Outbound invites awaiting an inviteAck, keyed by the normalised target name.
	private final Map<String, Consumer<Boolean>> pendingInvite = new ConcurrentHashMap<>();
	// Where inbound "invited" pushes are delivered (the plugin registers this at startup).
	private volatile Consumer<PartyInvite> inviteListener;
	// Our own identity, resent on each (re)connect so the server can route invites to us.
	private volatile long identityHash;
	private volatile String identityName;
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
		// A WebSocket must not inherit the REST read timeout; the ping keeps it alive.
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
		attempt = 0;
		// A prior stop() shuts the executor down permanently; give this run a fresh one.
		if (reconnects == null || reconnects.isShutdown())
		{
			reconnects = newReconnectExecutor();
		}
		connect();
	}

	/** Close the connection for good. Called when the plugin stops. */
	public synchronized void stop()
	{
		closed = true;
		connected = false;
		started = false;
		if (reconnects != null)
		{
			reconnects.shutdownNow();
		}
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

	/** Register the live-list listener, scoping the feed to one activity ({@code null} = all). */
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

	/** Re-scope the live feed to a different activity ({@code null} = all); server sends a fresh snapshot. */
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
	 * Push a host-initiated edit to the hosted ad. Unlike {@link #update} it always sends (no dedup) and
	 * resets {@code lastSentPatch} so the next heartbeat re-sends live fields against the new baseline.
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

	/**
	 * Host action: hand the ad to a new host in place, re-keying the credential to {@code newKey}.
	 * {@code onSuccess} fires on the {@code transferred} ack; we keep hosting state until then, so a
	 * failed transfer leaves us keeping the ad alive as before.
	 */
	public void transferHost(String id, String oldKey, String newHost, String newKey,
		Consumer<Party> onSuccess, Consumer<Throwable> onError)
	{
		if (id == null || !connected)
		{
			onError.accept(new IOException("socket not connected"));
			return;
		}
		pendingTransfer.put(id, new VoicePending(url -> onSuccess.accept(null), onError));
		send(gson.toJson(new TransferFrame(id, oldKey, newHost, newKey)));
	}

	/**
	 * Drop local hosting state for {@code id} WITHOUT disbanding it (unlike {@link #unhost}); used by the
	 * old host after a transfer so we stop resuming/keeping the ad alive.
	 */
	public void clearHosting(String id)
	{
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
	 * Host action: ask the backend bot to provision a voice channel. {@code onUrl} gets the invite URL,
	 * {@code onError} on failure. Idempotent. Callbacks run on the socket reader thread.
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

	// --- Discord account linking ---

	/** Begin an OAuth2 Discord link for {@code accountHash}: {@code onUrl} gets the authorize URL, else {@code onError}. */
	public void startDiscordLink(long accountHash, Consumer<String> onUrl, Consumer<Throwable> onError)
	{
		if (!connected)
		{
			onError.accept(new IOException("socket not connected"));
			return;
		}
		pendingLinkUrl = new LinkUrlPending(onUrl, onError);
		send(gson.toJson(new AccountHashFrame("startDiscordLink", accountHash)));
	}

	/** Look up whether {@code accountHash} is linked; {@code onResult} gets the status, or null if offline. */
	public void getDiscordLink(long accountHash, Consumer<DiscordLinkStatus> onResult)
	{
		if (!connected)
		{
			onResult.accept(null);
			return;
		}
		pendingLinkStatus.put(accountHash, onResult);
		send(gson.toJson(new AccountHashFrame("getDiscordLink", accountHash)));
	}

	/** Remove the Discord binding for {@code accountHash} server-side. Fire-and-forget. */
	public void unlinkDiscord(long accountHash)
	{
		if (!connected)
		{
			return;
		}
		send(gson.toJson(new AccountHashFrame("unlinkDiscord", accountHash)));
	}

	/** Badge privacy: hide/re-show {@code accountHash}'s Discord-role badges; {@code onResult} gets refreshed status. */
	public void setBadgeVisibility(long accountHash, boolean visible, Consumer<DiscordLinkStatus> onResult)
	{
		if (!connected)
		{
			if (onResult != null)
			{
				onResult.accept(null);
			}
			return;
		}
		if (onResult != null)
		{
			pendingLinkStatus.put(accountHash, onResult);
		}
		send(gson.toJson(new BadgeVisibilityFrame(accountHash, visible)));
	}

	/** Host action: ask the backend bot to disconnect a kicked member from the party's voice channel. */
	public void kickVoiceMember(String id, String key, long accountHash)
	{
		if (id == null || !connected)
		{
			return;
		}
		send(gson.toJson(new KickVoiceFrame(id, key, accountHash)));
	}

	/**
	 * Member self-service: grant our per-user access to the party's voice channel before opening the
	 * invite. {@code onGranted} fires on the ack; {@code onError} if refused or offline.
	 */
	public void requestVoiceAccess(String id, long accountHash, Runnable onGranted, Consumer<Throwable> onError)
	{
		if (id == null || !connected)
		{
			onError.accept(new IOException("socket not connected"));
			return;
		}
		pendingVoiceAccess.put(id, new VoicePending(ignored -> onGranted.run(), onError));
		send(gson.toJson(new VoiceAccessFrame(id, accountHash)));
	}

	// --- Invites ---

	/**
	 * Register our OSRS identity so the server can route incoming invites to this connection. Remembered
	 * and re-sent on every reconnect. Safe to call repeatedly (e.g. once per login).
	 */
	public void identify(long accountHash, String name)
	{
		identityHash = accountHash;
		identityName = name;
		if (connected)
		{
			send(gson.toJson(new IdentifyFrame(accountHash, name)));
		}
	}

	/** Where inbound invites are delivered; replaces any previous listener. */
	public void setInviteListener(Consumer<PartyInvite> listener)
	{
		this.inviteListener = listener;
	}

	/**
	 * Invite an online friend to a party we're in. {@code onResult} gets true if the invite reached the
	 * friend's client, false if they weren't online in OSParty (or we're offline).
	 */
	public void invite(String partyId, String fromName, long fromAccountHash, String target,
		Consumer<Boolean> onResult)
	{
		if (partyId == null || target == null || !connected)
		{
			onResult.accept(false);
			return;
		}
		pendingInvite.put(normalizeName(target), onResult);
		send(gson.toJson(new InviteFrame(partyId, fromName, fromAccountHash, target)));
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
		if (identityHash != 0 || identityName != null)
		{
			send(gson.toJson(new IdentifyFrame(identityHash, identityName)));
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
			case "discordLinkUrl":
				completeLinkUrl(frame.url);
				break;
			case "discordLink":
				completeLinkStatus(frame.accountHash, frame.id, frame.username, frame.badgesVisible);
				break;
			case "voiceAccess":
				completeVoiceAccess(frame.id);
				break;
			case "transferred":
				completeTransfer(frame.id);
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
			case "invited":
				handleInvited(frame.party, frame.from);
				break;
			case "inviteAck":
				completeInviteAck(frame.id, frame.delivered);
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

	/** Apply a {@code batch} frame's created/updated/removed changes under one lock, then a single re-emit. */
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

	/** Invoked (off EDT) with the party id when the server reports our hosted ad no longer exists. */
	private volatile Consumer<String> onHostedGone;

	public void setOnHostedGone(Consumer<String> callback)
	{
		this.onHostedGone = callback;
	}

	private void notifyHostedGone(String id)
	{
		Consumer<String> cb = onHostedGone;
		if (cb != null)
		{
			cb.accept(id);
		}
	}

	private void handleGone(String id)
	{
		log.info("Party socket: received 'gone' frame for party {} (hosting {})", id, hostingId);
		// Our hosted ad is gone server-side (stale purge, manual cleanup, or expired before resume).
		if (id != null && id.equals(hostingId))
		{
			hostingId = null;
			hostingKey = null;
			lastSentPatch = null;
			notifyHostedGone(id);
		}
	}

	private void handleError(String id, String detail)
	{
		// Our own ad vanished server-side (stale purge / manual cleanup) — the server rejects
		// the heartbeat with "gone". Fold hosting state and tell the UI so the tab clears.
		if ("gone".equals(detail) && id != null && id.equals(hostingId))
		{
			log.info("Party socket: heartbeat rejected with 'gone' for party {}; clearing hosting state", id);
			hostingId = null;
			hostingKey = null;
			lastSentPatch = null;
			notifyHostedGone(id);
			return;
		}
		// An id'd error may reject a pending voice/access/transfer request; route it there first.
		if (id != null)
		{
			VoicePending voice = pendingVoiceChannel.remove(id);
			if (voice != null)
			{
				voice.onError.accept(new IOException("voice channel failed: " + detail));
				return;
			}
			VoicePending access = pendingVoiceAccess.remove(id);
			if (access != null)
			{
				access.onError.accept(new IOException("voice access failed: " + detail));
				return;
			}
			VoicePending transfer = pendingTransfer.remove(id);
			if (transfer != null)
			{
				transfer.onError.accept(new IOException("host transfer failed: " + detail));
				return;
			}
		}
		// Link errors carry no id; route to an in-flight link request before a host rejection.
		LinkUrlPending link = pendingLinkUrl;
		if (link != null)
		{
			pendingLinkUrl = null;
			link.onError.accept(new IOException("link failed: " + detail));
			return;
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

	private void completeLinkUrl(String url)
	{
		LinkUrlPending pending = pendingLinkUrl;
		pendingLinkUrl = null;
		if (pending != null)
		{
			if (url != null)
			{
				pending.onUrl.accept(url);
			}
			else
			{
				pending.onError.accept(new IOException("no link url"));
			}
		}
	}

	private void completeVoiceAccess(String id)
	{
		if (id == null)
		{
			return;
		}
		VoicePending pending = pendingVoiceAccess.remove(id);
		if (pending != null)
		{
			pending.onUrl.accept(null);
		}
	}

	private void completeTransfer(String id)
	{
		if (id == null)
		{
			return;
		}
		VoicePending pending = pendingTransfer.remove(id);
		if (pending != null)
		{
			pending.onUrl.accept(null);
		}
	}

	private void handleInvited(Party party, String from)
	{
		Consumer<PartyInvite> listener = inviteListener;
		if (listener != null && party != null)
		{
			listener.accept(new PartyInvite(party, from));
		}
	}

	private void completeInviteAck(String target, Boolean delivered)
	{
		if (target == null)
		{
			return;
		}
		Consumer<Boolean> callback = pendingInvite.remove(normalizeName(target));
		if (callback != null)
		{
			callback.accept(delivered != null && delivered);
		}
	}

	/** Normalise an OSRS name the same way the server does: strip the nbsp Jagex uses, trim, lowercase. */
	private static String normalizeName(String name)
	{
		return name == null ? null : name.replace('\u00A0', ' ').trim().toLowerCase();
	}

	private void completeLinkStatus(Long accountHash, String discordId, String username, Boolean badgesVisible)
	{
		if (accountHash == null)
		{
			return;
		}
		Consumer<DiscordLinkStatus> callback = pendingLinkStatus.remove(accountHash);
		if (callback != null)
		{
			// Older servers omit badgesVisible; treat absent as visible (the default).
			callback.accept(new DiscordLinkStatus(discordId != null, discordId, username,
				badgesVisible == null || badgesVisible));
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
		// "voiceChannel"/"discordLinkUrl" frame: an invite or OAuth authorize URL.
		String url;
		// "discordLink" frame: linked username, echoed accountHash, and badge-privacy pref (null = visible).
		String username;
		Long accountHash;
		Boolean badgesVisible;
		// "batch" frame: a tick's worth of changes, applied together.
		Party[] created;
		PartyDelta[] updated;
		String[] removed;
		// "presence" frame: the global count of connected plugin clients.
		int online;
		// "invited" frame: who sent the invite (host name when the sender didn't identify).
		String from;
		// "inviteAck" frame: whether the invite reached the target's client.
		Boolean delivered;
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

	/** Host-authorised reassignment of the ad to a new host, re-keying the credential to {@code newKey}. */
	private static final class TransferFrame
	{
		final String type = "transferHost";
		final String id;
		final String key;
		final String host;
		final String newKey;

		TransferFrame(String id, String key, String host, String newKey)
		{
			this.id = id;
			this.key = key;
			this.host = host;
			this.newKey = newKey;
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

	/** Outbound frame carrying just an accountHash: startDiscordLink / getDiscordLink. */
	private static final class AccountHashFrame
	{
		final String type;
		final long accountHash;

		AccountHashFrame(String type, long accountHash)
		{
			this.type = type;
			this.accountHash = accountHash;
		}
	}

	/** Badge privacy self-service: show/hide the caller's Discord-role badges on party ads. */
	private static final class BadgeVisibilityFrame
	{
		final String type = "setBadgeVisibility";
		final long accountHash;
		final boolean visible;

		BadgeVisibilityFrame(long accountHash, boolean visible)
		{
			this.accountHash = accountHash;
			this.visible = visible;
		}
	}

	/** Host-authorised kick of a member from the party's voice channel, by their accountHash. */
	private static final class KickVoiceFrame
	{
		final String type = "kickVoiceMember";
		final String id;
		final String key;
		final long accountHash;

		KickVoiceFrame(String id, String key, long accountHash)
		{
			this.id = id;
			this.key = key;
			this.accountHash = accountHash;
		}
	}

	/** Member self-service request for per-user access to the party's voice channel. */
	private static final class VoiceAccessFrame
	{
		final String type = "requestVoiceAccess";
		final String id;
		final long accountHash;

		VoiceAccessFrame(String id, long accountHash)
		{
			this.id = id;
			this.accountHash = accountHash;
		}
	}

	/** Outbound identity registration so the server can route invites to this connection. */
	private static final class IdentifyFrame
	{
		final String type = "identify";
		final long accountHash;
		final String name;

		IdentifyFrame(long accountHash, String name)
		{
			this.accountHash = accountHash;
			this.name = name;
		}
	}

	/** Outbound invite of {@code target} to a party we're in; {@code name} is our own (sender) name. */
	private static final class InviteFrame
	{
		final String type = "invite";
		final String id;
		final String name;
		final long accountHash;
		final String target;

		InviteFrame(String id, String name, long accountHash, String target)
		{
			this.id = id;
			this.name = name;
			this.accountHash = accountHash;
			this.target = target;
		}
	}

	private static final class LinkUrlPending
	{
		final Consumer<String> onUrl;
		final Consumer<Throwable> onError;

		LinkUrlPending(Consumer<String> onUrl, Consumer<Throwable> onError)
		{
			this.onUrl = onUrl;
			this.onError = onError;
		}
	}
}
