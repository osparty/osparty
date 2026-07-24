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
 * <p>P1: connects to a single node (owner is always that node). P2 adds node-hint routing: the URL gains an
 * {@code /n/{nodeId}} segment and the socket honours {@code redirect} frames.
 */
@Slf4j
public class PartyV2Socket extends WebSocketListener {
	private static final long MIN_BACKOFF_MS = 1_000;
	private static final long MAX_BACKOFF_MS = 30_000;

	private final OkHttpClient client;
	private final Gson gson;
	private final String url;

	private volatile ScheduledExecutorService reconnects;
	private volatile Consumer<Frame> listener = frame -> { };
	private volatile Runnable onOpen = () -> { };

	private volatile boolean started;
	private volatile boolean closed;
	private volatile boolean connected;
	private volatile WebSocket webSocket;
	private int attempt;

	@javax.inject.Inject
	public PartyV2Socket(OkHttpClient httpClient, Gson gson) {
		this.client = httpClient.newBuilder()
			.pingInterval(Duration.ofSeconds(20))
			.readTimeout(Duration.ZERO)
			.build();
		this.gson = gson;
		this.url = buildWsUrl();
	}

	private static String buildWsUrl() {
		String base = System.getProperty("osparty.partyV2Url");
		if (base == null || base.trim().isEmpty()) {
			base = PartyApiClient.apiBaseUrl();
		}
		HttpUrl parsed = HttpUrl.parse(base.trim());
		if (parsed == null) {
			throw new IllegalStateException("Invalid Party V2 URL: " + base);
		}
		return parsed.newBuilder()
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
		if (reconnects != null) {
			reconnects.shutdownNow();
		}
		WebSocket socket = webSocket;
		if (socket != null) {
			socket.close(1000, "plugin stopped");
		}
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
		webSocket = client.newWebSocket(new Request.Builder().url(url).build(), this);
	}

	@Override
	public void onOpen(WebSocket socket, Response response) {
		connected = true;
		attempt = 0;
		try {
			onOpen.run();
		}
		catch (Exception e) {
			log.debug("Party V2 onOpen callback failed: {}", e.toString());
		}
	}

	@Override
	public void onMessage(WebSocket socket, String text) {
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
		try {
			listener.accept(frame);
		}
		catch (Exception e) {
			log.debug("Party V2 frame handling failed: {}", e.toString());
		}
	}

	@Override
	public void onClosing(WebSocket socket, int code, String reason) {
		socket.close(1000, null);
	}

	@Override
	public void onClosed(WebSocket socket, int code, String reason) {
		connected = false;
		scheduleReconnect();
	}

	@Override
	public void onFailure(WebSocket socket, Throwable t, Response response) {
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
		public Integer x;
		public Integer y;
		public Integer plane;
		public Integer color;
		public String name;
		public String detail;
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
