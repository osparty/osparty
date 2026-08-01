package net.osparty.party;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.osparty.api.OSPartySocket;

/**
 * The plugin's live-party channel. Frame semantics live in {@link LiveParty}; what this class owns is the
 * live half of the protocol that is not about a party — announcing on (re)connect, following the server to
 * the node that holds the room, and waiting out a handover.
 *
 * <p>It no longer owns a connection. The live party used to open a second WebSocket for the duration of a
 * party, which meant every user actually in one cost the gateway twice what a browsing user did; it now rides
 * on {@link OSPartySocket}'s session-long connection as a tagged channel. Starting and stopping are therefore
 * about attaching to that connection, not about opening anything — the socket is up either way.
 *
 * <p>What did not change is that the server holds no durable live state: on every (re)connect this fires
 * {@link #setOnOpen}, so the caller re-announces itself and re-sends its state, and the room is rebuilt
 * (PARTY_V2_MIGRATION.md recovery).
 *
 * <p>P2 node-hint routing still applies, and now moves the whole connection: a {@code redirect} frame pins
 * {@link OSPartySocket} to the owning pod, and leaving the party releases the pin without moving again — the
 * board is served identically everywhere, so there is nothing to go back for. P4 {@code ownerPending}: a room
 * whose owner drained answers with a retry delay rather than an error, and this re-announces after it rather
 * than treating the party as gone — a member reconnects faster than its host re-hosts, and without this it
 * would arrive to "no room" and silently fall out of the party.
 */
@Slf4j
public class LivePartyChannel implements OSPartySocket.LiveChannel {
	/** Retry delay used when an {@code ownerPending} frame does not name one. */
	private static final long DEFAULT_RETRY_MS = 1_000;
	/**
	 * Cap on consecutive {@code ownerPending} retries. The server stops deferring once the room's handover
	 * window lapses — it answers {@code no room} instead — so this is only a backstop against a server that
	 * defers forever. Twenty retries covers a handover window several times over.
	 */
	private static final int MAX_PENDING_RETRIES = 20;

	private final OSPartySocket connection;
	private final Gson gson;

	private volatile ScheduledExecutorService retries;
	private volatile Consumer<Frame> listener = frame -> { };
	private volatile Runnable onOpen = () -> { };

	/** Whether a party is in progress, and so whether we are attached to the connection at all. */
	private volatile boolean started;
	/** Consecutive {@code ownerPending} deferrals; reset whenever the server actually seats us. */
	private volatile int pendingRetries;

	@javax.inject.Inject
	public LivePartyChannel(OSPartySocket connection, Gson gson) {
		this.connection = connection;
		this.gson = gson;
	}

	public void setListener(Consumer<Frame> listener) {
		this.listener = listener;
	}

	/** Invoked on the socket thread on every (re)connect, so the caller can re-announce and re-send state. */
	public void setOnOpen(Runnable onOpen) {
		this.onOpen = onOpen;
	}

	/**
	 * Attach the live channel: from here the shared connection carries this party's frames.
	 *
	 * <p>Nothing is opened. If the connection is already up — which it is, unless the network is down —
	 * {@link OSPartySocket#setLiveChannel} fires the announce callback straight away, so the caller sees the
	 * same "we are connected, say who we are" moment a fresh socket used to give it.
	 */
	public synchronized void attach() {
		if (started) {
			return;
		}
		started = true;
		pendingRetries = 0;
		if (retries == null || retries.isShutdown()) {
			retries = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "osparty-live-retry");
				t.setDaemon(true);
				return t;
			});
		}
		connection.setLiveChannel(this);
	}

	/**
	 * Detach: the party is over, and the connection goes back to carrying discovery alone.
	 *
	 * <p>The node hint is released but not acted on. It belonged to the party we were in, and keeping it would
	 * send the next party we host straight back to the node that owned the last one, which is how one node
	 * ends up owning them all. Reconnecting to drop it would be pure cost, though: any node serves the board,
	 * and the next party will move us wherever it lives.
	 */
	public synchronized void detach() {
		started = false;
		connection.setLiveChannel(null);
		connection.clearNodeHint(false);
		if (retries != null) {
			retries.shutdownNow();
		}
	}

	/** Move the shared connection to the pod an advertisement named, before we try to join its room. */
	public void hintNode(String node) {
		connection.moveTo(node);
	}

	/** Stamp the pod our room was placed on onto our own advertisement, so joiners arrive there directly. */
	public void publishNode(String node) {
		connection.publishLiveNode(node);
	}

	/** Whether live frames can reach the server right now. */
	public boolean isConnected() {
		return started && connection.isConnected();
	}

	/**
	 * Send one frame on the live channel.
	 *
	 * <p>Binary and tagged, like everything on this connection: the tag says which protocol it belongs to, and
	 * sending UTF-8 bytes rather than text saves the server a per-recipient encode, which measured as a fifth
	 * of its CPU.
	 */
	public void send(Object frame) {
		if (started) {
			connection.sendLive(gson.toJson(frame));
		}
	}

	@Override
	public void onLiveOpen() {
		pendingRetries = 0;
		announce();
	}

	@Override
	public void onLiveClosed() {
		// The connection schedules its own reconnect; onLiveOpen will re-announce us when it lands.
	}

	@Override
	public void onLiveFrame(String json) {
		Frame frame;
		try {
			frame = gson.fromJson(json, Frame.class);
		}
		catch (Exception e) {
			return;
		}
		if (frame == null || frame.type == null) {
			return;
		}
		if ("redirect".equals(frame.type)) {
			// Our room lives elsewhere. Moving takes the board with us, which costs one delta on arrival.
			connection.moveTo(frame.nodeId);
			return;
		}
		if ("ownerChanged".equals(frame.type)) {
			// The owning node is going away (shutdown drain) or already lost the room: drop the stale hint
			// and reconnect unhinted, landing wherever the gateway sends us.
			connection.clearNodeHint(true);
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
			log.debug("Live party frame handling failed: {}", e.toString());
		}
	}

	/**
	 * The room exists but has no owner yet: its old node drained and the host is re-claiming it right now.
	 * Re-announce after the server's delay rather than reconnecting — this connection is healthy, the room
	 * simply is not there yet, and moving would only take the same question to another node.
	 */
	private void scheduleReannounce(Long retryAfterMs) {
		ScheduledExecutorService executor = retries;
		if (!started || executor == null || pendingRetries >= MAX_PENDING_RETRIES) {
			return;
		}
		pendingRetries++;
		long delay = retryAfterMs == null || retryAfterMs <= 0 ? DEFAULT_RETRY_MS : retryAfterMs;
		// Jitter so a whole party retrying together does not arrive as one burst.
		long jitter = ThreadLocalRandom.current().nextLong(DEFAULT_RETRY_MS / 4);
		try {
			executor.schedule(this::reannounce, delay + jitter, TimeUnit.MILLISECONDS);
		}
		catch (RejectedExecutionException ignored) {
			// executor shut down by stop()
		}
	}

	private void reannounce() {
		if (isConnected()) {
			announce();
		}
	}

	/** Re-send {@code hello} and {@code host}/{@code join}, exactly as a fresh connection would. */
	private void announce() {
		try {
			onOpen.run();
		}
		catch (Exception e) {
			log.debug("Live party announce failed: {}", e.toString());
		}
	}

	/** An incoming server frame; only the fields relevant to {@link #type} are populated. Mirrors Outbound. */
	public static final class Frame {
		@SerializedName("t")
		public String type;
		@SerializedName("m")
		public Long memberId;
		public String status;
		public String host;
		public Integer capacity;
		public Boolean locked;
		public Boolean closed;
		public String discordUrl;
		public List<RosterEntry> members;
		/** Every peer's live update from one aggregation window, on a {@code memberUpdates} frame. */
		@SerializedName("u")
		public List<MemberUpdate> updates;
		/** Opaque live snapshot (a serialised PlayerUpdate); the caller converts it with its own Gson. */
		@SerializedName("s")
		public JsonObject state;
		/** Opaque host ad settings (a serialised PartyMeta), on a {@code meta} frame. */
		public JsonObject meta;
		public Integer x;
		public Integer y;
		public Integer plane;
		public Integer color;
		public String name;
		public String detail;
		/** Owner node-hint on a {@code redirect} frame; the connection moves to {@code /n/{nodeId}/…}. */
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

	/** One member's live update inside a {@code memberUpdates} frame. */
	public static final class MemberUpdate {
		@SerializedName("m")
		public long memberId;
		@SerializedName("s")
		public JsonObject state;
	}

	/** One roster row from a {@code roster} frame. */
	public static final class RosterEntry {
		@SerializedName("m")
		public long memberId;
		public String name;
		public long accountHash;
		public String status;
		public String role;
		public boolean learner;
		public boolean teacher;
	}
}
