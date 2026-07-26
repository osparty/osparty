package net.osparty.party.v2;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.osparty.api.PartyApiClient;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * The plugin's WebSocket to the Party V2 live endpoint ({@code /api/v2/ws/party}). Owns the connection and
 * jittered-backoff reconnect; frame semantics live in {@link LivePartyV2}. On every (re)connect it fires
 * {@link #onOpen} so the caller re-announces itself and re-sends its current state — the server holds no
 * durable live state, so reconnection rebuilds the room naturally (PARTY_V2_MIGRATION.md recovery).
 *
 * <p>The connection lives for the duration of a party, not of the plugin: {@link LivePartyV2} starts it on
 * host/join and stops it on leave. Outside a party there is nothing to relay, and an idle socket would hold
 * a session on a node for every logged-in user rather than for every user actually in a party. Stopping also
 * releases the node hint, so the next party is placed on its merits instead of inheriting the last one's.
 *
 * <p>P1: connects to a single node (owner is always that node). P2 adds node-hint routing: the URL gains an
 * {@code /n/{nodeId}} segment and the socket honours {@code redirect} frames. P4 adds {@code ownerPending}:
 * a room whose owner drained answers with a retry delay rather than an error, and the socket re-announces
 * after it instead of treating the party as gone — a member reconnects faster than its host re-hosts, and
 * without this it would arrive to "no room" and silently fall out of the party.
 */
@Slf4j
public class PartyV2Socket extends WebSocketListener {
	private static final long MIN_BACKOFF_MS = 1_000;
	private static final long MAX_BACKOFF_MS = 30_000;

	/** Retry delay used when an {@code ownerPending} frame does not name one. */
	private static final long DEFAULT_RETRY_MS = 1_000;
	/**
	 * Cap on consecutive {@code ownerPending} retries. The server stops deferring once the room's handover
	 * window lapses — it answers {@code no room} instead — so this is only a backstop against a server that
	 * defers forever. Twenty retries covers a handover window several times over.
	 */
	private static final int MAX_PENDING_RETRIES = 20;

	private final OkHttpClient client;
	private final Gson gson;
	private final HttpUrl base;

	/** Owner node-hint (§3.2): when set, the URL gains an {@code /n/{nodeId}} prefix routing to that pod. */
	private volatile String nodeHint;

	private volatile ScheduledExecutorService reconnects;
	private volatile Consumer<Frame> listener = frame -> { };
	private volatile Runnable onOpen = () -> { };

	private volatile boolean started;
	private volatile boolean closed;
	private volatile boolean connected;
	private volatile WebSocket webSocket;
	private int attempt;
	/** Consecutive {@code ownerPending} deferrals; reset whenever the server actually seats us. */
	private volatile int pendingRetries;

	@javax.inject.Inject
	public PartyV2Socket(OkHttpClient httpClient, Gson gson) {
		this.client = httpClient.newBuilder()
			.pingInterval(Duration.ofSeconds(20))
			.readTimeout(Duration.ZERO)
			.build();
		this.gson = gson;
		this.base = resolveBase();
	}

	private static HttpUrl resolveBase() {
		String base = System.getProperty("osparty.partyV2Url");
		if (base == null || base.trim().isEmpty()) {
			base = PartyApiClient.apiBaseUrl();
		}
		HttpUrl parsed = HttpUrl.parse(base.trim());
		if (parsed == null) {
			throw new IllegalStateException("Invalid Party V2 URL: " + base);
		}
		return parsed;
	}

	/** {@code [/n/{nodeId}]/api/v2/ws/party} — the node-hint prefix is added only once a redirect sets it. */
	private String currentUrl() {
		HttpUrl.Builder builder = base.newBuilder();
		String hint = nodeHint;
		if (hint != null && !hint.isEmpty()) {
			builder.addPathSegment("n").addPathSegment(hint);
		}
		return builder
			.addPathSegment("api").addPathSegment("v2")
			.addPathSegment("ws").addPathSegment("party")
			.build().toString();
	}

	public void setListener(Consumer<Frame> listener) {
		this.listener = listener;
	}

	/** Invoked on the socket thread on every (re)connect, so the caller can re-announce and re-send state. */
	public void setOnOpen(Runnable onOpen) {
		this.onOpen = onOpen;
	}

	public synchronized void start() {
		if (started) {
			return;
		}
		started = true;
		closed = false;
		attempt = 0;
		if (reconnects == null || reconnects.isShutdown()) {
			reconnects = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "osparty-v2-socket");
				t.setDaemon(true);
				return t;
			});
		}
		connect();
	}

	public synchronized void stop() {
		closed = true;
		connected = false;
		started = false;
		// The hint belongs to the party we were in, not to us: keeping it would send the next party we host
		// straight back to the node that owned the last one, which is how one node ends up owning them all.
		nodeHint = null;
		if (reconnects != null) {
			reconnects.shutdownNow();
		}
		WebSocket socket = webSocket;
		if (socket != null) {
			socket.close(1000, "party left");
		}
		// Drop the reference before the close completes, so this socket's late callbacks are recognised as
		// stale by isStale() and cannot schedule a reconnect onto a session that has since restarted.
		webSocket = null;
	}

	public boolean isConnected() {
		return connected && !closed;
	}

	public void send(Object frame) {
		WebSocket socket = webSocket;
		if (socket != null && connected) {
			socket.send(gson.toJson(frame));
		}
	}

	private synchronized void connect() {
		if (closed) {
			return;
		}
		webSocket = client.newWebSocket(new Request.Builder().url(currentUrl()).build(), this);
	}

	/**
	 * Whether a callback belongs to a socket we have already moved on from. The socket is started and
	 * stopped repeatedly (once per party), and OkHttp delivers a closing socket's callbacks after
	 * {@link #stop} returns — so without this, a leave immediately followed by a host would see the old
	 * socket's {@code onClosed} schedule a reconnect against the new session and open a second connection.
	 *
	 * <p>Synchronised on the same monitor as {@link #connect}: the callback can otherwise arrive on a
	 * connection so fast that {@code webSocket} has not been assigned yet, and a live socket would be
	 * mistaken for an abandoned one.
	 */
	private synchronized boolean isStale(WebSocket socket) {
		return socket != this.webSocket;
	}

	@Override
	public void onOpen(WebSocket socket, Response response) {
		synchronized (this) {
			if (socket != this.webSocket) {
				socket.close(1000, "superseded");
				return;
			}
			connected = true;
			attempt = 0;
			pendingRetries = 0;
		}
		// Outside the lock: the callback re-announces the party and must not hold up a concurrent stop().
		try {
			onOpen.run();
		}
		catch (Exception e) {
			log.debug("Party V2 onOpen callback failed: {}", e.toString());
		}
	}

	@Override
	public void onMessage(WebSocket socket, String text) {
		// Plain read, not isStale(): this runs per live-state frame, and taking the connect lock on that
		// path would contend with every other member's traffic. A frame from an abandoned socket is
		// harmless — it carries no reconnect decision.
		if (socket != this.webSocket) {
			return;
		}
		Frame frame;
		try {
			frame = gson.fromJson(text, Frame.class);
		}
		catch (Exception e) {
			return;
		}
		if (frame == null || frame.type == null) {
			return;
		}
		if ("redirect".equals(frame.type)) {
			redirectTo(frame.nodeId);
			return;
		}
		if ("ownerChanged".equals(frame.type)) {
			// The owning node is going away (shutdown drain) or already lost the room: drop the stale hint
			// and reconnect unhinted, landing wherever the gateway sends us.
			clearHintAndReconnect();
			return;
		}
		if ("ownerPending".equals(frame.type)) {
			// The room is mid-handover: we got here before our host re-claimed it. Wait and re-announce.
			scheduleReannounce(frame.retryAfterMs);
			return;
		}
		if ("welcome".equals(frame.type)) {
			// Seated: whatever deferral got us here is over.
			pendingRetries = 0;
		}
		try {
			listener.accept(frame);
		}
		catch (Exception e) {
			log.debug("Party V2 frame handling failed: {}", e.toString());
		}
	}

	/** Adopt the owner node-hint and reconnect there (prompt retry, not a backoff — we know where to go). */
	private void redirectTo(String nodeId) {
		if (nodeId == null || nodeId.isEmpty() || nodeId.equals(nodeHint)) {
			return;
		}
		nodeHint = nodeId;
		attempt = 0;
		WebSocket socket = webSocket;
		if (socket != null) {
			socket.close(1000, "redirect");
		}
	}

	/**
	 * The room exists but has no owner yet: its old node drained and the host is re-claiming it right now.
	 * Re-announce after the server's delay rather than reconnecting — this socket is healthy, the room
	 * simply is not there yet, and reconnecting would only move us to another node with the same answer.
	 *
	 * <p>Re-announcing runs the same {@link #onOpen} callback a fresh connection does, so the caller re-sends
	 * its {@code hello} and its {@code host}/{@code join} exactly as it would after a reconnect.
	 */
	private void scheduleReannounce(Long retryAfterMs) {
		if (closed || pendingRetries >= MAX_PENDING_RETRIES) {
			return;
		}
		pendingRetries++;
		long delay = retryAfterMs == null || retryAfterMs <= 0 ? DEFAULT_RETRY_MS : retryAfterMs;
		// Jitter so a whole party retrying together does not arrive as one burst.
		long jitter = ThreadLocalRandom.current().nextLong(DEFAULT_RETRY_MS / 4);
		try {
			reconnects.schedule(this::reannounce, delay + jitter, TimeUnit.MILLISECONDS);
		}
		catch (RejectedExecutionException ignored) {
			// executor shut down by stop()
		}
	}

	private void reannounce() {
		if (closed || !connected) {
			return;
		}
		try {
			onOpen.run();
		}
		catch (Exception e) {
			log.debug("Party V2 re-announce failed: {}", e.toString());
		}
	}

	/** Forget the owner hint and reconnect, so the next node we reach can claim or resolve the room. */
	private void clearHintAndReconnect() {
		nodeHint = null;
		attempt = 0;
		WebSocket socket = webSocket;
		if (socket != null) {
			socket.close(1000, "owner changed");
		}
	}

	@Override
	public void onClosing(WebSocket socket, int code, String reason) {
		socket.close(1000, null);
	}

	@Override
	public void onClosed(WebSocket socket, int code, String reason) {
		if (isStale(socket)) {
			return;
		}
		connected = false;
		scheduleReconnect();
	}

	@Override
	public void onFailure(WebSocket socket, Throwable t, Response response) {
		if (isStale(socket)) {
			return;
		}
		connected = false;
		if (!closed) {
			log.debug("Party V2 socket failed ({}); will retry", t.toString());
		}
		scheduleReconnect();
	}

	private void scheduleReconnect() {
		if (closed) {
			return;
		}
		long base = Math.min(MAX_BACKOFF_MS, MIN_BACKOFF_MS << Math.min(attempt, 5));
		long jitter = ThreadLocalRandom.current().nextLong(MIN_BACKOFF_MS);
		attempt++;
		try {
			reconnects.schedule(this::connect, base + jitter, TimeUnit.MILLISECONDS);
		}
		catch (RejectedExecutionException ignored) {
			// executor shut down by stop()
		}
	}

	/** An incoming server frame; only the fields relevant to {@link #type} are populated. Mirrors Outbound. */
	public static final class Frame {
		public String type;
		public Long memberId;
		public String status;
		public String host;
		public Integer capacity;
		public Boolean locked;
		public Boolean closed;
		public String discordUrl;
		public List<RosterEntry> members;
		/** Opaque live snapshot (a serialised PlayerUpdate); the caller converts it with its own Gson. */
		public JsonObject state;
		/** Opaque host ad settings (a serialised PartyMeta), on a {@code meta} frame. */
		public JsonObject meta;
		public Integer x;
		public Integer y;
		public Integer plane;
		public Integer color;
		public String name;
		public String detail;
		/** Owner node-hint on a {@code redirect} frame; the socket reconnects to {@code /n/{nodeId}/…}. */
		public String nodeId;
		/** How long to wait before re-announcing, on an {@code ownerPending} frame. */
		public Long retryAfterMs;
		// Ready check.
		public Long checkId;
		public String starter;
		// Spec drain.
		public Integer npcIndex;
		public String weapon;
		public Integer hit;
		public Integer world;
		// Join prompt / host transfer.
		public String kind;
		public String friendsChat;
		public String newHostKey;
		public String newHostName;
		public Boolean hostStays;
	}

	/** One roster row from a {@code roster} frame. */
	public static final class RosterEntry {
		public long memberId;
		public String name;
		public long accountHash;
		public String status;
		public String role;
		public boolean learner;
		public boolean teacher;
	}
}
