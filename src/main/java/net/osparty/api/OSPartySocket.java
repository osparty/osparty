package net.osparty.api;

import net.osparty.model.Advertisement;
import net.osparty.model.AdvertisementDelta;
import net.osparty.model.AdvertisementRequest;
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
 *
 * <p>It is also the only connection the plugin opens: the live party ({@link net.osparty.party.LiveParty})
 * rides here as a second channel, tagged per frame with {@link Mux}. This class owns the connection, the
 * backoff and the node hint, and knows nothing about what the live channel says; it hands those frames to
 * whoever registered as {@link LiveChannel}.
 */
@Slf4j
@Singleton
public class OSPartySocket extends WebSocketListener
{
	private static final long MIN_BACKOFF_MS = 1_000;
	private static final long MAX_BACKOFF_MS = 30_000;

	private final OkHttpClient client;
	private final Gson gson;
	private final HttpUrl base;
	/** Credentials this machine holds, one per character. Read on every connect, written on enrolment. */
	private final net.osparty.store.CredentialStore credentials;
	/** Frame parse failures already reported, so one that recurs every tick is logged once rather than always. */
	private final java.util.Set<String> warnedFrames = java.util.concurrent.ConcurrentHashMap.newKeySet();
	/**
	 * The character whose credential this connection presents. Zero until the plugin reports one, which is
	 * the ordinary state on a client that has not logged in yet.
	 */
	private volatile long accountHash;
	/**
	 * The last real character {@link #setAccountHash} reconnected for. Separate from {@link #accountHash}
	 * because that field also passes through the logged-out sentinel on every world hop (see
	 * {@code OSPartyPlugin#onGameStateChanged}), and comparing against it directly would reconnect twice per
	 * hop for no reason -- once dropping to the sentinel, once coming back to the same character.
	 */
	private volatile long lastReconnectedAccountHash;
	/** Sent as {@code X-OSParty-Client} so the service can see the deployed spread instead of guessing. */
	private static final String VERSION = net.osparty.ui.OSPartyPanel.VERSION;

	/** Overrides the device label outright, for a machine that does not resolve or a user who would rather not send its name. */
	static final String DEVICE_LABEL_PROPERTY = "osparty.deviceLabel";

	/**
	 * Sent as {@code X-OSParty-Device} so a freshly enrolled credential starts with a real name instead of a
	 * bare timestamp -- see {@link net.osparty.ui.DeviceManagerDialog}. Best-effort and unverified, same
	 * footing as {@link #VERSION}: nothing here is ever used to decide anything, only to display.
	 */
	private static String deviceLabel()
	{
		String hostname;
		try
		{
			hostname = java.net.InetAddress.getLocalHost().getHostName();
		}
		catch (java.net.UnknownHostException e)
		{
			hostname = null;
		}
		return deviceLabel(System.getProperty(DEVICE_LABEL_PROPERTY), hostname);
	}

	/**
	 * The fallback chain on its own, apart from the system calls that feed it, so it can be exercised without
	 * a real network stack: the local hostname lookup can fail depending on DNS/network config.
	 *
	 * <p>The override is a system property and not an environment variable because the plugin hub disallows
	 * {@code System.getenv} outright -- see the {@code osparty.apiUrl} precedent in {@link BoardApiClient}.
	 * Returning null is a fine outcome: the server names an unlabelled device after its enrolment date, and
	 * the user can rename it from the device manager either way.
	 */
	static String deviceLabel(String override, String hostname)
	{
		if (override != null && !override.isBlank())
		{
			return override.trim();
		}
		if (hostname != null && !hostname.isBlank())
		{
			return hostname;
		}
		return null;
	}

	/**
	 * The pod the live party wants this connection on, or null for "anywhere".
	 *
	 * <p>A live party lives in the memory of one node, so its members have to reach that node. Discovery does
	 * not care where it lands — the board is the same everywhere — which is why the hint can be set and
	 * dropped underneath it. What moving costs the board is one delta: the reconnect re-subscribes with
	 * {@link #boardSeq}, so the parties we already hold are not sent again.
	 */
	private volatile String nodeHint;
	/** The live party, while there is one. Registered on host/join and dropped on leave. */
	private volatile LiveChannel live;

	/**
	 * The live party's half of this connection. Implemented by {@link net.osparty.party.LivePartyChannel},
	 * which keeps every bit of protocol and reconnect-semantics knowledge on its own side of this seam.
	 */
	public interface LiveChannel
	{
		/** The connection came up (or came back). The live party re-announces itself and resends state. */
		void onLiveOpen();

		/** One live frame, as JSON. */
		void onLiveFrame(String json);

		/** The connection went away. A reconnect is already scheduled; nothing to do but note it. */
		void onLiveClosed();
	}

	private final Map<String, Advertisement> ads = new LinkedHashMap<>();
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

	private final List<Consumer<List<Advertisement>>> searchListeners = new CopyOnWriteArrayList<>();
	// Activity to scope the live list to (null = all). Kept across reconnects so onOpen re-sends it.
	private volatile String subscribeActivity;
	/**
	 * The highest board revision applied. Offered back on the next subscribe so a reconnect costs the
	 * handful of advertisements that moved rather than all of them — which matters most exactly when it
	 * hurts most, during a deploy, when every client reconnects at once.
	 */
	private volatile long boardSeq;
	// One-shot lookups awaiting a directed byCode/byHost reply, keyed by the echoed code/host.
	private final Map<String, Consumer<Advertisement>> pendingByCode = new ConcurrentHashMap<>();
	private final Map<String, Consumer<Advertisement>> pendingByHost = new ConcurrentHashMap<>();
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
	private volatile int attempt;

	// Hosting state (kept across reconnects so we can resume the same ad).
	private volatile String hostingId;
	private volatile String hostingKey;
	private volatile HostPending pendingHost;
	private volatile String lastSentPatch;
	/** The node last stamped onto our own advertisement, so the stamp is sent once rather than per update. */
	private volatile String publishedNode;

	@Inject
	OSPartySocket(OkHttpClient httpClient, Gson gson, net.osparty.store.CredentialStore credentials)
	{
		this.credentials = credentials;
		// A WebSocket must not inherit the REST read timeout; the ping keeps it alive.
		this.client = httpClient.newBuilder()
			.pingInterval(Duration.ofSeconds(20))
			.readTimeout(Duration.ZERO)
			.build();
		this.gson = gson;
		// OkHttp upgrades the https URL to a WebSocket.
		HttpUrl parsed = HttpUrl.parse(BoardApiClient.apiBaseUrl());
		if (parsed == null)
		{
			throw new IllegalStateException("Invalid API base URL: " + BoardApiClient.apiBaseUrl());
		}
		this.base = parsed;
	}

	/**
	 * {@code [/n/{nodeId}]/api/ws} — the merged endpoint, optionally pinned to the pod that owns our party.
	 *
	 * <p>The hint prefix appears only once a live party asks for it. Without one the gateway round-robins,
	 * which is what a client not in a party wants.
	 */
	private String currentUrl()
	{
		HttpUrl.Builder builder = base.newBuilder();
		String hint = nodeHint;
		if (hint != null && !hint.isEmpty())
		{
			builder.addPathSegment("n").addPathSegment(hint);
		}
		return builder
			.addPathSegment("api").addPathSegment("ws")
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
		// A hint outlives nothing: the party it belonged to is over, and a restart should be placed on its
		// own merits rather than inheriting where the last session happened to end up.
		nodeHint = null;
		publishedNode = null;
		live = null;
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
	public int onlineUserCount()
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
		Request.Builder request = new Request.Builder().url(currentUrl());
		// The version rides every connection so the service can see what is actually deployed rather than
		// guess. Released plugins update on their own schedule and there is no way to ask them.
		request.header("X-OSParty-Client", VERSION);
		// A best-effort device name, used only as a fresh enrolment's starting label -- see deviceLabel().
		// Absent rather than sent blank when nothing could be determined.
		String label = deviceLabel();
		if (label != null)
		{
			request.header("X-OSParty-Device", label);
		}
		// The credential for whoever is logged in, when this machine has one. Sent on the upgrade rather
		// than in a frame so identity is settled before the connection carries anything, and as a header
		// rather than a query parameter so it stays out of proxy logs. Every reconnect path funnels through
		// here, so this re-presents itself for free.
		String token = credentials.get(accountHash);
		if (token != null)
		{
			request.header("X-OSParty-Auth", token);
		}
		webSocket = client.newWebSocket(request.build(), this);
	}

	/**
	 * The account this connection should present a credential for. Set by the plugin every tick as the
	 * logged-in character changes.
	 *
	 * <p>A credential can only be presented at the handshake, not mid-connection, so this reconnects whenever
	 * it is told about a real character it has not already reconnected for -- otherwise a connection opened
	 * before login (or before this character's credential existed) would go on identifying unauthenticated
	 * forever, since nothing else about the connection ever prompts it to try again. Skipped for the
	 * logged-out sentinel: there is no credential to present for it, and it would otherwise cost two
	 * reconnects per world hop for a character that never actually changed.
	 */
	public synchronized void setAccountHash(long accountHash)
	{
		this.accountHash = accountHash;
		boolean known = accountHash != 0 && accountHash != -1;
		if (known && accountHash != lastReconnectedAccountHash)
		{
			lastReconnectedAccountHash = accountHash;
			reconnectForMove("account changed");
		}
	}

	// --- Live-party channel ---

	/**
	 * Register (or, with null, drop) the live party's half of this connection.
	 *
	 * <p>Registering does not open anything: the connection is already up, and the live party simply starts
	 * being told about it. If it is up right now, the callback fires immediately, because the caller expects
	 * the same "announce yourself" moment it would get from a fresh socket.
	 */
	public void setLiveChannel(LiveChannel channel)
	{
		this.live = channel;
		if (channel != null && isConnected())
		{
			fireLiveOpen(channel);
		}
	}

	/**
	 * Move this connection to a named pod, because that is where our party lives.
	 *
	 * <p>An immediate reconnect rather than a backoff: we know exactly where to go, and every moment spent
	 * elsewhere is a moment the party cannot relay. The board survives it — the re-subscribe asks only for
	 * what changed while we were away.
	 */
	public synchronized void moveTo(String nodeId)
	{
		if (nodeId == null || nodeId.isEmpty() || nodeId.equals(nodeHint))
		{
			return;
		}
		nodeHint = nodeId;
		reconnectForMove("node hint");
	}

	/**
	 * Forget the pod hint. Reconnects only if asked — leaving a party is not a reason to move, since any node
	 * serves the board equally well and the next party will send us wherever it lives anyway.
	 *
	 * @param reconnect whether to go somewhere else now, as when the node we are pinned to is going away
	 */
	public synchronized void clearNodeHint(boolean reconnect)
	{
		if (nodeHint == null && !reconnect)
		{
			return;
		}
		nodeHint = null;
		if (reconnect)
		{
			reconnectForMove("owner changed");
		}
	}

	private void reconnectForMove(String reason)
	{
		attempt = 0;
		WebSocket socket = webSocket;
		if (socket != null)
		{
			// onClosed schedules the reconnect, which picks up the new URL.
			socket.close(1000, reason);
		}
		else if (started && !closed)
		{
			connect();
		}
	}

	/**
	 * Tell the board which pod our live room is on, so joiners can reach it without a redirect.
	 *
	 * <p>Only meaningful while hosting, and sent as an ordinary ad patch — one small frame, once, when the
	 * room is placed or moves. The server cannot work this out for itself: a host on an older plugin holds
	 * two sockets whose pods need not agree, so the node is ours to report.
	 */
	public void publishLiveNode(String node)
	{
		if (node == null || node.isEmpty() || node.equals(publishedNode))
		{
			return;
		}
		String id = hostingId;
		if (id != null && connected)
		{
			// Latched only once the patch is actually on the wire, or a party hosted without one would keep
			// the stamp suppressed for every party after it.
			publishedNode = node;
			send(gson.toJson(new UpdateFrame(id, hostingKey, java.util.Map.of("node", node))));
		}
	}

	/** Send one live-party frame. Silently dropped while disconnected. */
	public void sendLive(String json)
	{
		if (connected)
		{
			sendTagged(Mux.LIVE, json);
		}
	}

	// --- Search read ---

	/** Register the listener that wants the live party list; pushes the current list now. */
	public void setSearchListener(Consumer<List<Advertisement>> listener)
	{
		setSearchListener(listener, null);
	}

	/** Register the live-list listener, scoping the feed to one activity ({@code null} = all). */
	public void setSearchListener(Consumer<List<Advertisement>> listener, String activity)
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
	public void clearSearchListener(Consumer<List<Advertisement>> listener)
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
	public void host(AdvertisementRequest request, String key, Consumer<Advertisement> onSuccess, Consumer<Throwable> onError)
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
			publishedNode = null;
		}
	}

	/**
	 * Host action: hand the ad to a new host in place, re-keying the credential to {@code newKey}.
	 * {@code onSuccess} fires on the {@code transferred} ack; we keep hosting state until then, so a
	 * failed transfer leaves us keeping the ad alive as before.
	 */
	public void transferHost(String id, String oldKey, String newHost, String newHostAccountType, String newKey,
		Consumer<Advertisement> onSuccess, Consumer<Throwable> onError)
	{
		if (id == null || !connected)
		{
			onError.accept(new IOException("socket not connected"));
			return;
		}
		pendingTransfer.put(id, new VoicePending(url -> onSuccess.accept(null), onError));
		send(gson.toJson(new TransferFrame(id, oldKey, newHost, newHostAccountType, newKey)));
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
			publishedNode = null;
		}
	}

	// --- One-shot lookups (request/response over the socket) ---

	/** Look up an ad by invite code; {@code onResult} gets the ad, or null if none/offline. */
	public void fetchByCode(String code, Consumer<Advertisement> onResult)
	{
		if (code == null || !connected)
		{
			onResult.accept(null);
			return;
		}
		pendingByCode.put(code, onResult);
		send(gson.toJson(new LookupFrame("getByCode", code, null)));
	}

	/** Look up the ad hosted by a player; {@code onResult} gets the ad, or null if none/offline. */
	public void fetchByHost(String host, Consumer<Advertisement> onResult)
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
	public void fetchDiscordLink(long accountHash, Consumer<DiscordLinkStatus> onResult)
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

	public void reportAd(String id)
	{
		if (id == null || !connected)
		{
			return;
		}
		send(gson.toJson(new ReportFrame(id)));
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
	public void invite(String adId, String fromName, long fromAccountHash, String target,
		Consumer<Boolean> onResult)
	{
		if (adId == null || target == null || !connected)
		{
			onResult.accept(false);
			return;
		}
		pendingInvite.put(normalizeName(target), onResult);
		send(gson.toJson(new InviteFrame(adId, fromName, fromAccountHash, target)));
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
		// Last, and only if a party is in progress: the live half re-announces itself over the same
		// connection. The server holds no durable live state, so a reconnect is how a room is rebuilt.
		LiveChannel channel = live;
		if (channel != null)
		{
			fireLiveOpen(channel);
		}
	}

	private void fireLiveOpen(LiveChannel channel)
	{
		try
		{
			channel.onLiveOpen();
		}
		catch (Exception e)
		{
			log.debug("Live re-announce failed: {}", e.toString());
		}
	}

	/**
	 * Every frame the server sends: the channel tag, then either JSON or a gzip stream of it.
	 *
	 * <p>The board's snapshot and batches are compressed — they are the only frames big enough to be worth it
	 * and the only ones the server shares between clients, so it deflates each one once for everybody — while
	 * small directed replies are not. Which is which is read off the gzip magic rather than announced, since
	 * the alternative is a second flag saying what the first two bytes already say.
	 */
	@Override
	public void onMessage(WebSocket socket, okio.ByteString bytes)
	{
		if (bytes.size() < 2)
		{
			return;
		}
		byte tag = bytes.getByte(0);
		byte[] payload = bytes.substring(1).toByteArray();
		// gzip magic. The board compresses its shared frames; nothing else does.
		if (payload.length > 1 && payload[0] == (byte) 0x1f && payload[1] == (byte) 0x8b)
		{
			payload = inflate(payload);
			if (payload == null)
			{
				return;
			}
		}
		String text = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
		if (tag == Mux.LIVE)
		{
			LiveChannel channel = live;
			if (channel != null)
			{
				try
				{
					channel.onLiveFrame(text);
				}
				catch (Exception e)
				{
					log.debug("Live frame handling failed: {}", e.toString());
				}
			}
			return;
		}
		if (tag == Mux.BOARD)
		{
			onMessage(socket, text);
		}
	}

	private static byte[] inflate(byte[] compressed)
	{
		try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
			new java.io.ByteArrayInputStream(compressed));
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream())
		{
			byte[] buffer = new byte[8192];
			for (int read = in.read(buffer); read > 0; read = in.read(buffer))
			{
				out.write(buffer, 0, read);
			}
			return out.toByteArray();
		}
		catch (Exception e)
		{
			log.debug("Failed to inflate a compressed frame: {}", e.toString());
			return null;
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
			// A frame this build cannot read. Between plugin and service versions that is what a protocol
			// difference looks like, and it is also what a field collision in Frame looks like -- two frame
			// types using one name for different types makes Gson throw here and take the whole frame with
			// it. Dropping that in silence is why one presented as a server that had simply stopped
			// answering: the credential was issued, the row was written, and nothing on this side ran.
			// Logged once per shape so a client that meets it every tick reports it rather than filling the
			// log. The service does the same for frames it cannot read.
			if (warnedFrames.size() < 16 && warnedFrames.add(e.getClass().getName()))
			{
				log.warn("Party socket: unreadable frame ({}): {}", e.toString(),
					text == null || text.length() <= 200 ? text : text.substring(0, 200) + "…");
			}
			return;
		}
		if (frame == null || frame.type == null)
		{
			return;
		}
		switch (frame.type)
		{
			case "snapshot":
				synchronized (ads)
				{
					ads.clear();
					putAll(frame.ads);
				}
				// A snapshot means the server declined to resume us, so this is the new baseline.
				boardSeq = frame.seq == null ? 0 : frame.seq;
				emitSearch();
				break;
			case "created":
			case "updated":
				if (frame.ad != null && frame.ad.getId() != null)
				{
					synchronized (ads)
					{
						ads.put(frame.ad.getId(), frame.ad);
					}
					emitSearch();
				}
				break;
			case "removed":
				if (frame.id != null)
				{
					synchronized (ads)
					{
						ads.remove(frame.id);
					}
					emitSearch();
				}
				break;
			case "batch":
				applyBatch(frame);
				if (frame.seq != null && frame.seq > boardSeq)
				{
					boardSeq = frame.seq;
				}
				break;
			case "hosted":
				handleHosted(frame.ad);
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
				completeLookup(pendingByCode, frame.id, frame.ad);
				break;
			case "byHost":
				completeLookup(pendingByHost, frame.id, frame.ad);
				break;
			case "presence":
				onlineUsers = frame.online;
				break;
			case "invited":
				handleInvited(frame.ad, frame.from);
				break;
			case "inviteAck":
				completeInviteAck(frame.id, frame.delivered);
				break;
		case "authIssued":
			handleAuthIssued(frame.token, frame.codes);
			break;
		case "authFailed":
			handleAuthFailed(frame);
			break;
		case "couplingCode":
			handleCouplingCode(frame.accountHash, frame.code);
			break;
		case "couplingCodeSent":
			handleCouplingCodeSent(frame.reached);
			break;
		case "couplingResult":
			handleCouplingResult(frame.accountHash, frame.success);
			break;
		case "couplingAccepted":
			handleCouplingAccepted(frame.accountHash);
			break;
		case "recoveryCodes":
			handleRecoveryCodes(frame.codes, frame.remaining);
			break;
		case "recoveryResult":
			handleRecoveryResult(frame.success, frame.pending, frame.detail);
			break;
		case "discordRecoveryUrl":
			handleDiscordRecoveryUrl(frame.url, frame.ticket);
			break;
		case "devices":
			handleDevices(frame.devices);
			break;
		case "deviceRevoked":
			handleDeviceRevoked(frame.deviceId, Boolean.TRUE.equals(frame.success));
			break;
		case "deviceRenamed":
			handleDeviceRenamed(frame.deviceId, Boolean.TRUE.equals(frame.success));
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
		signedIn = false;
		failAllPending(null);
		fireLiveClosed();
		scheduleReconnect();
	}

	@Override
	public void onFailure(WebSocket socket, Throwable t, Response response)
	{
		connected = false;
		signedIn = false;
		failAllPending(t);
		if (!closed)
		{
			log.debug("Party socket failed ({}); will retry", t.toString());
		}
		fireLiveClosed();
		scheduleReconnect();
	}

	/**
	 * Every in-flight request is a callback something is waiting on, and the reply it wants can no longer
	 * arrive. Each holder is failed the same way {@link #handleError} fails it, so a drop and a refusal look
	 * alike to the caller.
	 */
	private void failAllPending(Throwable cause)
	{
		if (closed)
		{
			return;
		}
		Throwable t = cause != null ? cause : new IOException("socket closed");
		HostPending host = pendingHost;
		if (host != null)
		{
			pendingHost = null;
			host.onError.accept(t);
		}
		LinkUrlPending link = pendingLinkUrl;
		if (link != null)
		{
			pendingLinkUrl = null;
			link.onError.accept(t);
		}
		for (VoicePending pending : drain(pendingVoiceChannel))
		{
			pending.onError.accept(t);
		}
		for (VoicePending pending : drain(pendingVoiceAccess))
		{
			pending.onError.accept(t);
		}
		for (VoicePending pending : drain(pendingTransfer))
		{
			pending.onError.accept(t);
		}
		// The plain consumers have no error channel: they say so the same way an offline call does.
		for (Consumer<Advertisement> pending : drain(pendingByCode))
		{
			pending.accept(null);
		}
		for (Consumer<Advertisement> pending : drain(pendingByHost))
		{
			pending.accept(null);
		}
		for (Consumer<DiscordLinkStatus> pending : drain(pendingLinkStatus))
		{
			pending.accept(null);
		}
		for (Consumer<Boolean> pending : drain(pendingInvite))
		{
			pending.accept(false);
		}
	}

	private static <K, V> List<V> drain(Map<K, V> pending)
	{
		List<V> out = new ArrayList<>();
		for (K key : new ArrayList<>(pending.keySet()))
		{
			V value = pending.remove(key);
			if (value != null)
			{
				out.add(value);
			}
		}
		return out;
	}

	private void fireLiveClosed()
	{
		LiveChannel channel = live;
		if (channel == null)
		{
			return;
		}
		try
		{
			channel.onLiveClosed();
		}
		catch (Exception e)
		{
			log.debug("Live close handling failed: {}", e.toString());
		}
	}

	/** Apply a {@code batch} frame's created/updated/removed changes under one lock, then a single re-emit. */
	private void applyBatch(Frame frame)
	{
		boolean changed = false;
		synchronized (ads)
		{
			if (frame.created != null)
			{
				for (Advertisement ad : frame.created)
				{
					if (ad != null && ad.getId() != null)
					{
						ads.put(ad.getId(), ad);
						changed = true;
					}
				}
			}
			if (frame.updated != null)
			{
				for (AdvertisementDelta delta : frame.updated)
				{
					if (delta == null || delta.getId() == null)
					{
						continue;
					}
					Advertisement existing = ads.get(delta.getId());
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
					if (id != null && ads.remove(id) != null)
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

	private void handleHosted(Advertisement ad)
	{
		if (ad == null || ad.getId() == null)
		{
			return;
		}
		hostingId = ad.getId();
		HostPending pending = pendingHost;
		pendingHost = null;
		if (pending != null)
		{
			pending.onSuccess.accept(ad);
		}
	}

	/** Invoked (off EDT) with the ad id when the server reports our hosted ad no longer exists. */
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
			publishedNode = null;
			notifyHostedGone(id);
		}
	}

	/**
	 * Keep the credential the server just issued for the logged-in character.
	 *
	 * <p>Sent once and never again -- the server keeps only a digest of it -- so losing this write means
	 * enrolling afresh on the next connection rather than being locked out. It is not applied to the live
	 * connection: this one already carries the identity that earned the credential, and the next connect
	 * picks it up from the store.
	 */
	private void handleAuthIssued(String token, List<String> recoveryCodes)
	{
		long account = accountHash;
		if (token == null || token.isEmpty() || !net.osparty.store.PlayerFlag.isKnown(account))
		{
			return;
		}
		credentials.put(account, token);
		signedIn = true;
		log.debug("Party socket: stored an OSParty credential for this character");
		Consumer<SignedInEvent> cb = onSignedIn;
		if (cb != null)
		{
			cb.accept(new SignedInEvent(account, recoveryCodes == null ? List.of() : recoveryCodes));
		}
	}

	/**
	 * The server could not sign this device in, and has said which ways back in are open.
	 *
	 * <p>Arrives at most once per connection per character: the server will not repeat itself until asked
	 * with {@link #retryAuth}, because the answer does not change on its own and this used to interrupt the
	 * user on every world hop.
	 */
	private void handleAuthFailed(Frame frame)
	{
		signedIn = false;
		// Whatever this machine was holding for that character, the server did not accept it — either it was
		// revoked from another device or there never was one. Keeping it would mean presenting a dead
		// credential on every reconnect forever, and it is the thing standing between here and enrolling
		// cleanly once the user has recovered.
		if (frame.accountHash != null)
		{
			credentials.clear(frame.accountHash);
		}
		Consumer<AuthFailedEvent> cb = onAuthFailed;
		if (cb != null)
		{
			cb.accept(new AuthFailedEvent(frame.accountHash == null ? 0L : frame.accountHash,
				Boolean.TRUE.equals(frame.coupling),
				Boolean.TRUE.equals(frame.recoveryCodes),
				Boolean.TRUE.equals(frame.discord)));
		}
	}

	private volatile Consumer<SignedInEvent> onSignedIn;
	private volatile Consumer<AuthFailedEvent> onAuthFailed;
	private volatile Consumer<CouplingCodeEvent> onCouplingCode;
	private volatile Consumer<Integer> onCouplingCodeSent;
	private volatile Consumer<CouplingResultEvent> onCouplingResult;
	private volatile Consumer<Long> onCouplingAccepted;
	/** Whether this connection is speaking for a proved character. Reset whenever the socket goes down. */
	private volatile boolean signedIn;

	public void setOnSignedIn(Consumer<SignedInEvent> callback)
	{
		this.onSignedIn = callback;
	}

	public void setOnAuthFailed(Consumer<AuthFailedEvent> callback)
	{
		this.onAuthFailed = callback;
	}

	/**
	 * Whether the current connection has proved which character it is.
	 *
	 * <p>False covers three different situations that look the same from here -- no credential yet, a
	 * credential the server no longer honours, and simply not connected. The UI treats them alike because
	 * the user's next step is the same in all three.
	 */
	public boolean isSignedIn()
	{
		return connected && signedIn;
	}

	public void setOnCouplingCode(Consumer<CouplingCodeEvent> callback)
	{
		this.onCouplingCode = callback;
	}

	public void setOnCouplingCodeSent(Consumer<Integer> callback)
	{
		this.onCouplingCodeSent = callback;
	}

	public void setOnCouplingResult(Consumer<CouplingResultEvent> callback)
	{
		this.onCouplingResult = callback;
	}

	public void setOnCouplingAccepted(Consumer<Long> callback)
	{
		this.onCouplingAccepted = callback;
	}

	private void handleCouplingCode(Long accountHash, String code)
	{
		Consumer<CouplingCodeEvent> cb = onCouplingCode;
		if (cb != null)
		{
			cb.accept(new CouplingCodeEvent(accountHash, code));
		}
	}

	private void handleCouplingCodeSent(Integer reached)
	{
		Consumer<Integer> cb = onCouplingCodeSent;
		if (cb != null)
		{
			cb.accept(reached == null ? 0 : reached);
		}
	}

	private void handleCouplingResult(Long accountHash, Boolean success)
	{
		Consumer<CouplingResultEvent> cb = onCouplingResult;
		if (cb != null)
		{
			cb.accept(new CouplingResultEvent(accountHash, success));
		}
	}

	/**
	 * Another machine has just joined this account. A notice, not a loss: coupling adds a machine and this
	 * one keeps its credential. Surfaced anyway, because the screen the code was read off is the one place
	 * somebody who did not expect it would notice.
	 */
	private void handleCouplingAccepted(Long accountHash)
	{
		Consumer<Long> cb = onCouplingAccepted;
		if (cb != null)
		{
			cb.accept(accountHash);
		}
	}

	public void couplingConfirm(long accountHash, String code)
	{
		if (!connected)
		{
			return;
		}
		send(gson.toJson(new CouplingConfirmFrame(accountHash, code)));
	}

	/**
	 * Ask the server to try signing this device in again.
	 *
	 * <p>Needed because the server answers a failed sign-in once per connection and then stays quiet — the
	 * answer only changes when the user does something about it, typically opening OSParty on the machine
	 * that holds the account. This is that "I've done it, try again".
	 */
	public void retryAuth()
	{
		long account = accountHash;
		if (!connected || !net.osparty.store.PlayerFlag.isKnown(account))
		{
			return;
		}
		send(gson.toJson(new AccountFrame("retryAuth", account)));
	}

	/**
	 * Ask the server to mint a coupling code and show it on this account's other signed-in device(s).
	 *
	 * <p>Nothing is minted until this is called -- {@code authFailed}'s {@code coupling} flag only says a
	 * device is out there, not that a code exists yet. The answer arrives on {@link #setOnCouplingCodeSent}.
	 */
	public void requestCouplingCode()
	{
		long account = accountHash;
		if (!connected || !net.osparty.store.PlayerFlag.isKnown(account))
		{
			return;
		}
		send(gson.toJson(new AccountFrame("requestCouplingCode", account)));
	}

	// --- Recovery ---

	private volatile Consumer<RecoveryCodesEvent> onRecoveryCodes;
	private volatile Consumer<RecoveryResultEvent> onRecoveryResult;
	private volatile java.util.function.BiConsumer<String, String> onDiscordRecoveryUrl;

	public void setOnRecoveryCodes(Consumer<RecoveryCodesEvent> callback)
	{
		this.onRecoveryCodes = callback;
	}

	public void setOnRecoveryResult(Consumer<RecoveryResultEvent> callback)
	{
		this.onRecoveryResult = callback;
	}

	/** {@code (url, ticket)} for a started Discord recovery: open the URL, then poll with the ticket. */
	public void setOnDiscordRecoveryUrl(java.util.function.BiConsumer<String, String> callback)
	{
		this.onDiscordRecoveryUrl = callback;
	}

	/**
	 * Spend a recovery code to sign this device in.
	 *
	 * <p>The only path that needs nothing but the code — no second machine, no browser — which is why it is
	 * the one that answers "the old PC is gone".
	 */
	public void redeemRecoveryCode(String code)
	{
		long account = accountHash;
		if (!connected || code == null || !net.osparty.store.PlayerFlag.isKnown(account))
		{
			return;
		}
		send(gson.toJson(new CodeFrame("recoveryConfirm", account, code)));
	}

	/** Mint a fresh set of codes, replacing any unspent ones. Signed-in devices only. */
	public void issueRecoveryCodes()
	{
		if (connected)
		{
			send(gson.toJson(new TypedFrame("issueRecoveryCodes")));
		}
	}

	/** How many codes are left. Answers with a {@link RecoveryCodesEvent} carrying no codes. */
	public void requestRecoveryStatus()
	{
		if (connected)
		{
			send(gson.toJson(new TypedFrame("recoveryStatus")));
		}
	}

	/** Begin Discord recovery; the answer arrives on {@link #setOnDiscordRecoveryUrl}. */
	public void startDiscordRecovery()
	{
		long account = accountHash;
		if (!connected || !net.osparty.store.PlayerFlag.isKnown(account))
		{
			return;
		}
		send(gson.toJson(new AccountFrame("startDiscordRecovery", account)));
	}

	/**
	 * Ask whether the browser half of a Discord recovery has finished.
	 *
	 * <p>Polled rather than waited on because the callback lands on whichever server replica the browser
	 * reached, which is generally not the one holding this socket. The ticket is what ties the two together
	 * and it is ours to keep, so this survives a reconnect mid-flow.
	 */
	public void pollDiscordRecovery(String ticket)
	{
		if (connected && ticket != null)
		{
			send(gson.toJson(new TicketFrame("discordRecoveryPoll", ticket)));
		}
	}

	private void handleRecoveryCodes(List<String> codes, Integer remaining)
	{
		Consumer<RecoveryCodesEvent> cb = onRecoveryCodes;
		if (cb != null)
		{
			cb.accept(new RecoveryCodesEvent(codes == null ? List.of() : codes,
				remaining == null ? 0 : remaining));
		}
	}

	private void handleRecoveryResult(Boolean success, Boolean pending, String detail)
	{
		Consumer<RecoveryResultEvent> cb = onRecoveryResult;
		if (cb != null)
		{
			cb.accept(new RecoveryResultEvent(Boolean.TRUE.equals(success),
				Boolean.TRUE.equals(pending), detail));
		}
	}

	private void handleDiscordRecoveryUrl(String url, String ticket)
	{
		java.util.function.BiConsumer<String, String> cb = onDiscordRecoveryUrl;
		if (cb != null && url != null)
		{
			cb.accept(url, ticket);
		}
	}

	// --- Device management ---

	/** One machine currently entitled to speak for the local account, as reported by {@link #listDevices}. */
	public static final class DeviceInfo
	{
		/** The token's stored digest. Opaque to the client; authenticates nothing on its own — see the server-side doc. */
		public final String id;
		public final String label;
		public final long issuedAt;
		public final long lastSeenAt;

		DeviceInfo(String id, String label, long issuedAt, long lastSeenAt)
		{
			this.id = id;
			this.label = label;
			this.issuedAt = issuedAt;
			this.lastSeenAt = lastSeenAt;
		}
	}

	private volatile Consumer<List<DeviceInfo>> pendingDeviceList;
	private final Map<String, Consumer<Boolean>> pendingRevokes = new ConcurrentHashMap<>();
	private final Map<String, Consumer<Boolean>> pendingRenames = new ConcurrentHashMap<>();

	/**
	 * List the machines currently entitled to speak for the local account. {@code onDevices} gets the list,
	 * or an empty one if the socket is down or the server refuses (not authenticated — nothing to list).
	 */
	public void listDevices(Consumer<List<DeviceInfo>> onDevices)
	{
		if (!connected)
		{
			onDevices.accept(java.util.List.of());
			return;
		}
		pendingDeviceList = onDevices;
		send(gson.toJson(new TypedFrame("listDevices")));
	}

	/**
	 * Withdraw one device by the id {@link #listDevices} reported for it. {@code onResult} gets whether it
	 * actually happened — false for an id that was never valid, already gone, or belonged to another
	 * account (which cannot occur from this client, since ids only ever come from your own device list).
	 */
	public void revokeDevice(String deviceId, Consumer<Boolean> onResult)
	{
		if (deviceId == null || !connected)
		{
			onResult.accept(false);
			return;
		}
		pendingRevokes.put(deviceId, onResult);
		send(gson.toJson(new RevokeDeviceFrame(deviceId)));
	}

	/**
	 * Rename one device by the id {@link #listDevices} reported for it. Purely cosmetic -- it changes what
	 * {@link DeviceInfo#label} reads next time, nothing about what the device can do. {@code onResult} gets
	 * whether it actually happened, for the same reasons as {@link #revokeDevice}.
	 */
	public void renameDevice(String deviceId, String label, Consumer<Boolean> onResult)
	{
		if (deviceId == null || !connected)
		{
			onResult.accept(false);
			return;
		}
		pendingRenames.put(deviceId, onResult);
		send(gson.toJson(new RenameDeviceFrame(deviceId, label)));
	}

	private void handleDevices(List<DeviceFrameEntry> devices)
	{
		Consumer<List<DeviceInfo>> cb = pendingDeviceList;
		pendingDeviceList = null;
		if (cb == null)
		{
			return;
		}
		List<DeviceInfo> out = new ArrayList<>();
		if (devices != null)
		{
			for (DeviceFrameEntry d : devices)
			{
				out.add(new DeviceInfo(d.id, d.label, d.issuedAt, d.lastSeenAt));
			}
		}
		cb.accept(out);
	}

	private void handleDeviceRevoked(String deviceId, boolean success)
	{
		Consumer<Boolean> cb = deviceId == null ? null : pendingRevokes.remove(deviceId);
		if (cb != null)
		{
			cb.accept(success);
		}
	}

	private void handleDeviceRenamed(String deviceId, boolean success)
	{
		Consumer<Boolean> cb = deviceId == null ? null : pendingRenames.remove(deviceId);
		if (cb != null)
		{
			cb.accept(success);
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
			publishedNode = null;
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

	private void handleInvited(Advertisement ad, String from)
	{
		Consumer<PartyInvite> listener = inviteListener;
		if (listener != null && ad != null)
		{
			listener.accept(new PartyInvite(ad, from));
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
		List<Advertisement> snap = snapshot();
		for (Consumer<List<Advertisement>> listener : searchListeners)
		{
			listener.accept(snap);
		}
	}

	private static void completeLookup(Map<String, Consumer<Advertisement>> pending, String key, Advertisement ad)
	{
		if (key == null)
		{
			return;
		}
		Consumer<Advertisement> callback = pending.remove(key);
		if (callback != null)
		{
			callback.accept(ad);
		}
	}

	private List<Advertisement> snapshot()
	{
		synchronized (ads)
		{
			return new ArrayList<>(ads.values());
		}
	}

	private void putAll(Advertisement[] list)
	{
		if (list != null)
		{
			for (Advertisement ad : list)
			{
				if (ad != null && ad.getId() != null)
				{
					ads.put(ad.getId(), ad);
				}
			}
		}
	}

	private void send(String json)
	{
		sendTagged(Mux.BOARD, json);
	}

	/**
	 * One frame on one channel: the tag byte, then UTF-8 JSON, as a binary message. Every frame on this
	 * connection carries a tag, in both directions — a text frame has nowhere to put one.
	 */
	private void sendTagged(byte tag, String json)
	{
		WebSocket socket = webSocket;
		if (socket == null)
		{
			return;
		}
		byte[] payload = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		byte[] framed = new byte[payload.length + 1];
		framed[0] = tag;
		System.arraycopy(payload, 0, framed, 1, payload.length);
		socket.send(okio.ByteString.of(framed));
	}

	/**
	 * The subscribe frame, which is also where we tell the server we can read compressed frames.
	 *
	 * <p>The board and the batches that follow it are the largest things this plugin receives — a snapshot
	 * is every advertisement there is — and they are repetitive JSON, so they gzip several-fold. The server
	 * compresses each shared frame once and sends it to everyone who asked, so this costs it nothing per
	 * client; on this side it is one inflate on a background thread.
	 */
	private String subscribeFrame()
	{
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", "subscribe");
		String activity = subscribeActivity;
		if (activity != null)
		{
			frame.put("activity", activity);
		}
		frame.put("compress", true);
		long since = boardSeq;
		if (since > 0)
		{
			// We still hold the board, so ask only for the difference. The server answers with a batch if it
			// can still work out what we missed, and with a whole board if we have been away too long.
			frame.put("since", since);
		}
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
		final Consumer<Advertisement> onSuccess;
		final Consumer<Throwable> onError;

		HostPending(Consumer<Advertisement> onSuccess, Consumer<Throwable> onError)
		{
			this.onSuccess = onSuccess;
			this.onError = onError;
		}
	}

	/** A server frame; only the fields relevant to {@link #type} are populated. */
	private static final class Frame
	{
		String type;
		Advertisement[] ads;
		// Board revision this frame is current to; absent from a server that predates resume.
		Long seq;
		Advertisement ad;
		String id;
		String detail;
		// "voiceChannel"/"discordLinkUrl" frame: an invite or OAuth authorize URL.
		String url;
		// "discordLink" frame: linked username, echoed accountHash, and badge-privacy pref (null = visible).
		String username;
		Long accountHash;
		Boolean badgesVisible;
		// "batch" frame: a tick's worth of changes, applied together.
		Advertisement[] created;
		AdvertisementDelta[] updated;
		String[] removed;
		// "presence" frame: the global count of connected plugin clients.
		int online;
		// "invited" frame: who sent the invite (host name when the sender didn't identify).
		String from;
		// "inviteAck" frame: whether the invite reached the target's client.
		Boolean delivered;
		// "authIssued" frame: the credential minted for this character on this machine. Delivered exactly
		// once -- the server keeps only its digest -- so it is stored on arrival or lost.
		String token;
		// "authIssued" frame: the public id this character is shown to other players under.
		String playerId;
		// "couplingCode" frame: the six-digit code, shown so another device can be told it.
		String code;
		// "couplingCodeSent" frame: how many of this account's other devices were just shown the code.
		Integer reached;
		// "couplingResult"/"recoveryResult" frame: whether it worked.
		Boolean success;
		// "recoveryResult" frame: the Discord round trip hasn't finished yet. Not a failure -- it is what
		// almost every poll sees.
		Boolean pending;
		// "authFailed" frame: which ways back in are open right now. Each is server-confirmed, so the UI
		// only ever offers a route that will actually answer.
		Boolean coupling;
		Boolean recoveryCodes;
		Boolean discord;
		// "authIssued"/"recoveryCodes" frame: one-time recovery codes, in plaintext and only ever once.
		List<String> codes;
		// "recoveryCodes" frame: how many are left unspent.
		Integer remaining;
		// "discordRecoveryUrl" frame: the secret this connection polls the recovery with.
		String ticket;
		// "devices" frame: the caller's own devices.
		List<DeviceFrameEntry> devices;
		// "revokeDevice"/"deviceRevoked" frame: the target device's id (a token digest, not a secret).
		String deviceId;
	}

	// Outbound frame shapes (Gson omits null fields, so a patch carries only what's set).

	private static final class HostFrame
	{
		final String type = "host";
		final AdvertisementRequest request;
		final String key;

		HostFrame(AdvertisementRequest request, String key)
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
		final String hostAccountType;
		final String newKey;

		TransferFrame(String id, String key, String host, String hostAccountType, String newKey)
		{
			this.id = id;
			this.key = key;
			this.host = host;
			this.hostAccountType = hostAccountType;
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

	private static final class ReportFrame
	{
		final String type = "report";
		final String id;

		ReportFrame(String id)
		{
			this.id = id;
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

	/** Bare request carrying only its type: {@code listDevices}, {@code issueRecoveryCodes}. */
	private static final class TypedFrame
	{
		final String type;

		TypedFrame(String type)
		{
			this.type = type;
		}
	}

	private static final class RevokeDeviceFrame
	{
		final String type = "revokeDevice";
		final String deviceId;

		RevokeDeviceFrame(String deviceId)
		{
			this.deviceId = deviceId;
		}
	}

	private static final class RenameDeviceFrame
	{
		final String type = "renameDevice";
		final String deviceId;
		final String label;

		RenameDeviceFrame(String deviceId, String label)
		{
			this.deviceId = deviceId;
			this.label = label;
		}
	}

	/** One row of a {@code devices} response. */
	private static final class DeviceFrameEntry
	{
		String id;
		String label;
		long issuedAt;
		long lastSeenAt;
	}

	private static final class CouplingConfirmFrame
	{
		final String type = "couplingConfirm";
		final long accountHash;
		final String code;

		CouplingConfirmFrame(long accountHash, String code)
		{
			this.accountHash = accountHash;
			this.code = code;
		}
	}

	/** Request naming only the character it is about: {@code retryAuth}, {@code startDiscordRecovery}. */
	private static final class AccountFrame
	{
		final String type;
		final long accountHash;

		AccountFrame(String type, long accountHash)
		{
			this.type = type;
			this.accountHash = accountHash;
		}
	}

	/** Request carrying a code to be checked: {@code recoveryConfirm}. */
	private static final class CodeFrame
	{
		final String type;
		final long accountHash;
		final String code;

		CodeFrame(String type, long accountHash, String code)
		{
			this.type = type;
			this.accountHash = accountHash;
			this.code = code;
		}
	}

	/** Request carrying a recovery ticket: {@code discordRecoveryPoll}. */
	private static final class TicketFrame
	{
		final String type;
		final String ticket;

		TicketFrame(String type, String ticket)
		{
			this.type = type;
			this.ticket = ticket;
		}
	}

	/**
	 * Delivered when this device has been signed in as {@code accountHash}.
	 *
	 * @param recoveryCodes the account's one-time codes, present only on the very first device it ever
	 *     signed in on and never obtainable again. Empty every other time.
	 */
	public static final class SignedInEvent
	{
		public final long accountHash;
		public final List<String> recoveryCodes;

		SignedInEvent(long accountHash, List<String> recoveryCodes)
		{
			this.accountHash = accountHash;
			this.recoveryCodes = recoveryCodes;
		}
	}

	/**
	 * Delivered when this device could not be signed in, with the ways back in that are open right now.
	 *
	 * <p>Each flag is something the server has just checked, so an option offered here is one that will
	 * answer. All three can be false — a lost credential on an account with no codes and no Discord link —
	 * and that case is worth saying plainly rather than dressing up as a prompt the user cannot satisfy.
	 *
	 * @param coupling a six-digit code is on another of this account's devices right now
	 * @param recoveryCodes this account has unspent recovery codes
	 * @param discord this account can be recovered through Discord
	 */
	public static final class AuthFailedEvent
	{
		public final long accountHash;
		public final boolean coupling;
		public final boolean recoveryCodes;
		public final boolean discord;

		AuthFailedEvent(long accountHash, boolean coupling, boolean recoveryCodes, boolean discord)
		{
			this.accountHash = accountHash;
			this.coupling = coupling;
			this.recoveryCodes = recoveryCodes;
			this.discord = discord;
		}

		/** Whether there is anything at all the user could do from here. */
		public boolean hasAnyRoute()
		{
			return coupling || recoveryCodes || discord;
		}
	}

	/**
	 * Recovery codes as issued, or on a status check just how many are left.
	 *
	 * @param codes plaintext, delivered once. Empty on a status reply.
	 */
	public static final class RecoveryCodesEvent
	{
		public final List<String> codes;
		public final int remaining;

		RecoveryCodesEvent(List<String> codes, int remaining)
		{
			this.codes = codes;
			this.remaining = remaining;
		}
	}

	/**
	 * How a recovery attempt went.
	 *
	 * @param pending a Discord recovery poll that found no answer yet — the ordinary case, and not a
	 *     failure. Without it the UI cannot tell "still waiting" from "refused".
	 */
	public static final class RecoveryResultEvent
	{
		public final boolean success;
		public final boolean pending;
		public final String detail;

		RecoveryResultEvent(boolean success, boolean pending, String detail)
		{
			this.success = success;
			this.pending = pending;
			this.detail = detail;
		}
	}

	/** Delivered to the incumbent when a challenger requests coupling. */
	public static final class CouplingCodeEvent
	{
		public final long accountHash;
		public final String code;

		public CouplingCodeEvent(long accountHash, String code)
		{
			this.accountHash = accountHash;
			this.code = code;
		}
	}

	/** Delivered after the challenger submits a coupling code. */
	public static final class CouplingResultEvent
	{
		public final long accountHash;
		public final boolean success;

		public CouplingResultEvent(long accountHash, boolean success)
		{
			this.accountHash = accountHash;
			this.success = success;
		}
	}
}
