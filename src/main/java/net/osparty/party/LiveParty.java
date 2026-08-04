package net.osparty.party;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.osparty.OSPartyConfig;
import net.osparty.model.Member;
import net.osparty.party.LiveFrames.CapacityFrame;
import net.osparty.party.LiveFrames.CommandFrame;
import net.osparty.party.LiveFrames.DiscordFrame;
import net.osparty.party.LiveFrames.HeartbeatFrame;
import net.osparty.party.LiveFrames.HelloFrame;
import net.osparty.party.LiveFrames.HostFrame;
import net.osparty.party.LiveFrames.JoinFrame;
import net.osparty.party.LiveFrames.JoinPromptFrame;
import net.osparty.party.LiveFrames.LeaveFrame;
import net.osparty.party.LiveFrames.LockedFrame;
import net.osparty.party.LiveFrames.MetaFrame;
import net.osparty.party.LiveFrames.PingFrame;
import net.osparty.party.LiveFrames.ReadyFrame;
import net.osparty.party.LiveFrames.ReadyStartFrame;
import net.osparty.party.LiveFrames.SpecDrainFrame;
import net.osparty.party.LiveFrames.TransferHostFrame;
import net.osparty.party.LiveFrames.UpdateFrame;
import net.osparty.tools.PersonalBests;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;

/**
 * The live party, over OSParty's own endpoint ({@link LivePartyChannel}). The roster is server-authoritative
 * (received in {@code roster} frames); live member state is a relayed {@link PlayerUpdate}, sent as the
 * parts that changed and merged on receipt.
 *
 * <p>Everything the party does goes through here: host and join, live state, admission and kick, map pings,
 * ready checks, spec drains, friends-chat prompts and the host-transfer handshake.
 */
@Slf4j
@Singleton
public class LiveParty implements LivePartyBackend {
	private enum Mode { NONE, HOST, MEMBER }

	private static final long ONLINE_TIMEOUT_MS = 20_000;
	/**
	 * Run energy is reported to the nearest this many points. It drains about one point per tick while
	 * moving, so reported exactly it makes every moving player dirty every tick whether or not anything
	 * interesting happened — which is frame <em>count</em>, the one cost the split into vitals/items/profile
	 * frames does nothing about. Empty and full are always exact; those are the values anyone reads.
	 */
	private static final int RUN_ENERGY_STEP = 5;

	/**
	 * How often to prove we are still here when we have nothing else to say. Well inside
	 * {@link #ONLINE_TIMEOUT_MS}, and suppressed whenever any other frame went out in the same window — in
	 * an active party the state traffic already proves it, so this costs nothing but idle parties.
	 */
	private static final long HEARTBEAT_MS = 5_000;

	/**
	 * How long a member may go quiet before the room drops it (the server's own member timeout).
	 *
	 * <p>Ours to know because nothing announces it: the room simply stops carrying us, and everything we
	 * send afterwards goes nowhere. Logging out with the client running is exactly that silence — the game
	 * tick this runs on stops — so anyone who takes a break longer than this comes back to a party that has
	 * forgotten them. See {@link #tick()}.
	 */
	private static final long SWEPT_AFTER_MS = 90_000;

	private final Client client;
	private final ConfigManager configManager;
	private final OSPartyConfig config;
	private final LivePartyChannel channel;
	private final Gson gson;
	private final LiveStateCodec codec = new LiveStateCodec();
	/**
	 * Inbound spec drains, join prompts and host-transfer steps are re-posted here as plain message objects,
	 * so the plugin's existing subscribers (defence tracker, panel, FC popup) receive them the way they
	 * always have. The socket frame is the wire; the event bus is how the rest of the plugin hears about it.
	 */
	private final EventBus eventBus;

	private volatile Mode mode = Mode.NONE;
	private volatile String roomKey;
	private volatile String currentActivityId;
	private volatile int capacity;
	private volatile boolean locked;

	// Server-authoritative roster (last roster frame) + room meta.
	private volatile List<LivePartyChannel.RosterEntry> rosterEntries = List.of();
	private volatile String hostName;
	private volatile String discordUrl;
	/** The advertised party's settings: ours to publish while hosting, the host's to follow as a member. */
	private volatile net.osparty.model.PartyMeta partyMeta;

	// Live per-member snapshots, keyed by server-assigned member id.
	private final Map<Long, PlayerUpdate> playerData = new ConcurrentHashMap<>();
	/**
	 * The merged raw JSON behind each entry in {@link #playerData}. Updates carry only what changed, so a
	 * frame has to be merged into what we already held rather than replacing it — and merging the JSON
	 * rather than the deserialised object means a field the sender has not learned about yet still survives,
	 * which is what lets a new kind of update ship without touching this class.
	 */
	private final Map<Long, JsonObject> rawState = new ConcurrentHashMap<>();
	private final Map<Long, Long> lastSeen = new ConcurrentHashMap<>();

	private volatile long localMemberId;
	private volatile PartyStatus localStatus;

	// Local self-report.
	private volatile String localRole;
	private volatile boolean localLearner;
	private volatile boolean localTeacher;
	private volatile boolean localInvited;
	// What has changed since we last told anyone. Split three ways because the frames are: vitals move every
	// tick and cost ~60 bytes, items move rarely and cost ~500, profile barely moves at all.
	private volatile boolean vitalsDirty;
	private volatile boolean itemsDirty;
	private volatile boolean profileDirty;

	// The last values we actually put on the wire, which is what "changed" has to be measured against —
	// not what the client currently reads, and not what we hold for a peer.
	// Written on the client thread but cleared by reset(), which the socket thread reaches through a kick or
	// a closed roster.
	private volatile int sentHp = -1;
	private volatile int sentPrayer = -1;
	private volatile int sentSpec = -1;
	private volatile int sentRunEnergy = -1;
	/** Whether the vitals built for the frame being assembled include one that moved down. Set by {@link #vitals}. */
	private boolean vitalsUrgent;
	private volatile int lastSentWorld = -1;
	private final Map<Skill, Integer> sentRealLevels = new ConcurrentHashMap<>();
	/** When we last sent anything at all, so the heartbeat only fires in the silence. */
	private volatile long lastSentAt;

	// Cached local identity (read on the client thread in tick(); used off-thread when (re)sending frames).
	private volatile long localAccountHash;
	private volatile String localName;
	private volatile int localWorld;
	/** Identity last announced to the server, so a resolved name/hash is re-sent exactly once. */
	private volatile String announcedName;
	private volatile long announcedAccountHash;

	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private volatile Runnable onEnded;
	private volatile Runnable onKicked;
	private final List<TilePing> pings = new CopyOnWriteArrayList<>();

	// ---- ready check (one active per party) ---------------------------------
	private static final long READY_CHECK_TIMEOUT_MS = 30_000;
	private volatile long readyCheckId;
	private volatile long readyCheckStartedAt;
	private volatile String readyCheckStarter;
	private long readyCheckSeq;
	private final java.util.Set<Long> readyMembers = ConcurrentHashMap.newKeySet();
	private final java.util.concurrent.atomic.AtomicBoolean readyAllNotified =
		new java.util.concurrent.atomic.AtomicBoolean();
	private volatile Consumer<String> onReadyCheckStarted;
	private volatile Runnable onAllReady;
	private volatile Runnable onReadyExpired;

	@Inject
	private LiveParty(Client client, ConfigManager configManager, OSPartyConfig config,
		LivePartyChannel channel, Gson gson, EventBus eventBus) {
		this.client = client;
		this.configManager = configManager;
		this.config = config;
		this.channel = channel;
		this.gson = gson;
		this.eventBus = eventBus;
	}

	// ---- lifecycle ----------------------------------------------------------

	/**
	 * Wires the channel but does not attach it: there is nothing to relay until we are in a party, and a
	 * session held open from plugin start would cost the server one for every logged-in user rather than for
	 * every user actually partying. {@link #hostParty}/{@link #joinParty} attach; {@link #leave} and
	 * {@link #end} detach.
	 */
	@Override
	public void register() {
		channel.setListener(this::onFrame);
		channel.setOnOpen(this::onOpen);
	}

	@Override
	public void unregister() {
		channel.detach();
		reset();
	}

	/** On every (re)connect: re-announce identity, re-assert host/join, and re-send our state next tick. */
	private void onOpen() {
		// The server re-seats us from scratch on a reconnect, so our identity has to be announced again.
		announcedName = localName;
		announcedAccountHash = localAccountHash;
		send(new HelloFrame(localAccountHash, localName));
		if (mode == Mode.HOST) {
			sendHost();
			// A room rebuilt on a new owner holds no ad settings, so republish ours rather than leaving
			// every member on the copy it took when it applied.
			if (partyMeta != null) {
				send(new MetaFrame(gson.toJsonTree(partyMeta)));
			}
		}
		else if (mode == Mode.MEMBER) {
			sendJoin();
		}
		markAllDirty();
	}

	@Override
	public void hintLiveNode(String node) {
		if (node != null && !node.isEmpty()) {
			channel.hintNode(node);
		}
	}

	@Override
	public void addListener(Runnable listener) {
		listeners.add(listener);
	}

	@Override
	public void setOnEnded(Runnable onEnded) {
		this.onEnded = onEnded;
	}

	@Override
	public void setOnKicked(Runnable onKicked) {
		this.onKicked = onKicked;
	}

	// ---- connection ---------------------------------------------------------

	@Override
	public void hostParty(String passphrase, String hostName, String activityId, int capacity,
		boolean locked, String role, boolean learner, boolean teacher) {
		reset();
		mode = Mode.HOST;
		roomKey = passphrase;
		this.hostName = hostName;
		this.localName = hostName;
		this.capacity = capacity;
		this.locked = locked;
		this.currentActivityId = activityId;
		this.localRole = role;
		this.localLearner = learner;
		this.localTeacher = teacher;
		markAllDirty();
		// Attach after the mode is set: attaching announces us, and the announce reads mode to decide between
		// host and join. It only fires when this attach was the one that connected us, so send the frame
		// ourselves when it did not (leaveForSwitch leaves us attached already).
		if (!channel.attach()) {
			sendHost();
		}
		fire();
	}

	@Override
	public void joinParty(String passphrase, String activityId, int teamSize, String role, boolean learner) {
		joinParty(passphrase, activityId, teamSize, role, learner, false);
	}

	@Override
	public void joinParty(String passphrase, String activityId, int teamSize, String role, boolean learner,
		boolean invited) {
		reset();
		mode = Mode.MEMBER;
		roomKey = passphrase;
		this.currentActivityId = activityId;
		this.capacity = teamSize;
		this.localRole = role;
		this.localLearner = learner;
		this.localInvited = invited;
		markAllDirty();
		if (!channel.attach()) {
			sendJoin();
		}
		fire();
	}

	@Override
	public void leave() {
		if (mode != Mode.NONE) {
			send(new LeaveFrame());
		}
		reset();
		channel.detach();
		fire();
	}

	@Override
	public void leaveForSwitch() {
		// The subsequent join re-keys our session server-side; no explicit leave needed. We stay attached for
		// the same reason: detaching here would only cost the join a reconnect.
		reset();
		fire();
	}

	private void reset() {
		mode = Mode.NONE;
		roomKey = null;
		currentActivityId = null;
		capacity = 0;
		locked = false;
		rosterEntries = List.of();
		hostName = null;
		discordUrl = null;
		partyMeta = null;
		playerData.clear();
		rawState.clear();
		lastSeen.clear();
		localMemberId = 0;
		localStatus = null;
		localRole = null;
		localLearner = false;
		localTeacher = false;
		localInvited = false;
		vitalsDirty = false;
		itemsDirty = false;
		profileDirty = false;
		sentHp = -1;
		sentPrayer = -1;
		sentSpec = -1;
		sentRunEnergy = -1;
		lastSentWorld = -1;
		sentRealLevels.clear();
		pings.clear();
		clearReadyCheck();
		announcedName = null;
		announcedAccountHash = 0;
		// The next party starts its silence from here: its join frame is sent once the socket opens, which
		// is after the first tick, and until then there is nothing sent to measure from.
		lastSentAt = System.currentTimeMillis();
	}

	@Override
	public boolean isInParty() {
		return mode != Mode.NONE;
	}

	@Override
	public boolean isHosting() {
		return mode == Mode.HOST;
	}

	@Override
	public String passphrase() {
		return roomKey;
	}

	// ---- inbound frames (socket thread) -------------------------------------

	private void onFrame(LivePartyChannel.Frame frame) {
		switch (frame.type) {
			case "welcome":
				localMemberId = frame.memberId == null ? 0 : frame.memberId;
				localStatus = parseStatus(frame.status);
				// Where our room ended up. A host puts it on its advertisement so joiners reach this pod
				// directly instead of landing anywhere and being redirected off it.
				if (mode == Mode.HOST && frame.nodeId != null) {
					channel.publishNode(frame.nodeId);
				}
				fire();
				break;
			case "roster":
				applyRoster(frame);
				break;
			case "mu":
				// One window's worth of everyone else's changes, in the order they happened. The owner
				// already leaves our own out; the id check is belt and braces, not a filter we rely on.
				if (frame.updates != null) {
					for (LivePartyChannel.MemberUpdate update : frame.updates) {
						if (update != null && update.state != null && update.memberId != localMemberId) {
							applyState(update.memberId, update.state);
						}
					}
				}
				break;
			case "resync":
				// Someone was just seated with no picture of the room. The owner keeps no live state, so we
				// are the only copy of ours.
				markAllDirty();
				break;
			case "alive":
				// A peer with nothing to report. Counts as presence, nothing else.
				if (frame.memberId != null) {
					lastSeen.put(frame.memberId, System.currentTimeMillis());
				}
				break;
			case "meta":
				applyMeta(frame);
				break;
			case "memberLeft":
				if (frame.memberId != null) {
					playerData.remove(frame.memberId);
					rawState.remove(frame.memberId);
					lastSeen.remove(frame.memberId);
				}
				fire();
				break;
			case "ping":
				applyPing(frame);
				break;
			case "readyStart":
				applyReadyStart(frame);
				break;
			case "ready":
				applyReady(frame);
				break;
			case "specDrain":
				applySpecDrain(frame);
				break;
			case "fcRequest":
				applyJoinPrompt(frame);
				break;
			case "transferHost":
				applyTransferHost(frame);
				break;
			case "kicked":
				// The server only sends this to the member it removed, so no target check is needed. Fire
				// before end(), which clears the room and would leave nothing to say we were kicked rather
				// than that the host disbanded.
				Runnable kicked = onKicked;
				if (kicked != null) {
					kicked.run();
				}
				end();
				break;
			case "error":
				log.debug("Live party error: {}", frame.detail);
				break;
			default:
				break;
		}
	}

	private void applyRoster(LivePartyChannel.Frame frame) {
		rosterEntries = frame.members == null ? List.of() : frame.members;
		hostName = frame.host;
		if (frame.capacity != null) {
			capacity = frame.capacity;
		}
		if (frame.locked != null) {
			locked = frame.locked;
		}
		discordUrl = frame.discordUrl;
		// Refresh our own status from the authoritative roster.
		java.util.Set<Long> present = new java.util.HashSet<>();
		for (LivePartyChannel.RosterEntry entry : rosterEntries) {
			present.add(entry.memberId);
			if (entry.memberId == localMemberId) {
				localStatus = parseStatus(entry.status);
			}
		}
		// Member ids are per-connection, so without this every reconnect strands a whole generation of peer
		// state for the life of the party.
		playerData.keySet().retainAll(present);
		rawState.keySet().retainAll(present);
		lastSeen.keySet().retainAll(present);
		if (Boolean.TRUE.equals(frame.closed)) {
			end();
			return;
		}
		fire();
	}

	/**
	 * Fold a peer's update into what we already knew about them.
	 *
	 * <p>An update carries only the fields that changed, so anything it omits must be left alone rather than
	 * cleared — which is why the merge happens on the raw JSON, before deserialising: an absent field is
	 * simply one the incoming object does not overwrite. Deserialising first and copying fields across would
	 * lose that distinction entirely, since an omitted int and a zero look identical by then.
	 *
	 * <p>The consequence is that a field can never be cleared by leaving it out, which is what
	 * {@code hideInventory}/{@code hideGear} are for, applied after the merge.
	 */
	private void applyState(long memberId, JsonObject state) {
		PlayerUpdate update;
		try {
			JsonObject merged = LiveStateCodec.merge(rawState.get(memberId), state);
			update = gson.fromJson(LiveStateCodec.fromWire(merged), PlayerUpdate.class);
			if (update == null) {
				return;
			}
			rawState.put(memberId, merged);
		}
		catch (Exception e) {
			return;
		}
		applyPrivacy(update);
		update.setMemberId(memberId);
		playerData.put(memberId, update);
		lastSeen.put(memberId, System.currentTimeMillis());
		fire();
	}

	/**
	 * Drop what our own privacy settings withhold, and say so.
	 *
	 * <p>The flags are always set, both on and off. Omitting them when privacy is off would leave a peer's
	 * merged copy carrying the {@code true} from when it was on, so unhiding would never take effect.
	 */
	private void stripPrivate(PlayerUpdate update) {
		boolean hideInventory = config.hideInventory();
		boolean hideGear = config.hideGear();
		update.setHideInventory(hideInventory);
		update.setHideGear(hideGear);
		if (hideInventory) {
			clearInventory(update);
		}
		if (hideGear) {
			clearGear(update);
		}
	}

	/**
	 * Honour a peer's privacy settings by dropping what they withheld. Nulling the fields (rather than
	 * blanking them) keeps the panel on its existing "no data" path, which reads as hidden rather than as an
	 * empty inventory.
	 */
	static void applyPrivacy(PlayerUpdate update) {
		if (Boolean.TRUE.equals(update.getHideInventory())) {
			clearInventory(update);
		}
		if (Boolean.TRUE.equals(update.getHideGear())) {
			clearGear(update);
		}
	}

	/** The inventory and everything that travels with it: the rune pouch is carried, so it is inventory too. */
	private static void clearInventory(PlayerUpdate update) {
		update.setInventory(null);
		update.setInventoryQuantities(null);
		update.setRunePouch(null);
		update.setRunePouchAmounts(null);
		update.setRunePouchNames(null);
	}

	private static void clearGear(PlayerUpdate update) {
		update.setEquipment(null);
	}

	private void applyMeta(LivePartyChannel.Frame frame) {
		if (frame.meta == null) {
			return;
		}
		net.osparty.model.PartyMeta meta;
		try {
			meta = gson.fromJson(frame.meta, net.osparty.model.PartyMeta.class);
		}
		catch (Exception e) {
			return;
		}
		if (meta == null) {
			return;
		}
		partyMeta = meta;
		fire();
	}

	private void applyPing(LivePartyChannel.Frame frame) {
		if (frame.x == null || frame.y == null || frame.memberId == null || frame.memberId == localMemberId) {
			return;
		}
		WorldPoint point = new WorldPoint(frame.x, frame.y, frame.plane == null ? 0 : frame.plane);
		Color color = new Color(frame.color == null ? Color.CYAN.getRGB() : frame.color, true);
		addPing(new TilePing(point, frame.name, color, System.currentTimeMillis()));
		fire();
	}

	/** The room is over (host disbanded, or we were kicked): drop the connection with it. */
	private void end() {
		reset();
		channel.detach();
		Runnable cb = onEnded;
		if (cb != null) {
			cb.run();
		}
		fire();
	}

	// ---- per-tick (client thread) -------------------------------------------

	@Override
	public void tick() {
		if (mode == Mode.NONE) {
			return;
		}
		net.runelite.api.Player local = client.getLocalPlayer();
		if (local != null && local.getName() != null) {
			localName = local.getName();
		}
		localAccountHash = client.getAccountHash();
		localWorld = client.getWorld();
		announceIdentityIfResolved();
		expireReadyCheck();

		// Ask for our seat back after a silence long enough to have cost us it — a logout the room was never
		// told about, since the connection outlives the login. The host is answered by its advertisement
		// instead: a room that loses its host is disbanded rather than waiting for one.
		if (mode == Mode.MEMBER && System.currentTimeMillis() - lastSentAt >= SWEPT_AFTER_MS) {
			sendJoin();
			// Seated afresh, the room holds no state for us; nothing else would re-send what has not changed.
			markAllDirty();
		}

		if (localWorld != lastSentWorld) {
			profileDirty = true;
		}
		if (vitalsChanged()) {
			vitalsDirty = true;
		}
		if ((vitalsDirty || itemsDirty || profileDirty) && dueThisTick()) {
			sendLocalState();
		}
		// After the state send, so an active party never pays for this: anything we just sent already
		// proved we are here.
		if (System.currentTimeMillis() - lastSentAt >= HEARTBEAT_MS) {
			send(new HeartbeatFrame());
		}
	}

	/**
	 * Whether a non-urgent update may go out this tick.
	 *
	 * <p>One member's tick owes a frame to every other member, so what a party costs the server grows with
	 * the square of its size. Past six members that is worth spending a little staleness on: an eight-man
	 * sends every second tick, a twelve-man every sixth. Borrowed from RuneLite's own party plugin, which
	 * has no server-side aggregation and so has always had to do this in the client.
	 *
	 * <p>Aggregation on our side batches the fan-out but cannot remove the inbound frame; this can, which
	 * is why both are worth having. Anything urgent — damage taken, prayer drained, a spec spent — ignores
	 * this entirely, so nothing anyone reacts to is ever delayed by it.
	 */
	private boolean dueThisTick() {
		if (itemsDirty || profileDirty || vitalsDropped()) {
			// Items and profile are rare by construction; holding them back saves nothing worth having.
			return true;
		}
		// Pending applicants receive no fan-out, so they cost nothing and must not throttle anyone.
		int every = Math.max(1, admittedCount() - 6);
		return every == 1 || client.getTickCount() % every == 0;
	}

	/** Everyone the roster has seated, host included; applicants waiting on admission are not counted. */
	private int admittedCount() {
		int admitted = 0;
		for (LivePartyChannel.RosterEntry entry : rosterEntries) {
			if (!"PENDING".equals(entry.status)) {
				admitted++;
			}
		}
		return admitted;
	}

	/** Every outbound frame goes through here, so the heartbeat knows when it has nothing to add. */
	private void send(Object frame) {
		lastSentAt = System.currentTimeMillis();
		channel.send(frame);
	}

	/**
	 * Tell the server who we are once the client actually knows. A joiner sends its {@code join} frame from
	 * the UI, before any tick has run for this party, so its name and account hash are still unresolved
	 * there; without this the server's roster would keep the member nameless (its live state is opaque),
	 * which breaks the advertised member list, badges, the block list and host transfer.
	 */
	private void announceIdentityIfResolved() {
		String name = localName;
		long accountHash = localAccountHash;
		if ((name == null || name.isEmpty()) && accountHash == 0) {
			return;
		}
		if (java.util.Objects.equals(name, announcedName) && accountHash == announcedAccountHash) {
			return;
		}
		announcedName = name;
		announcedAccountHash = accountHash;
		send(new HelloFrame(accountHash, name));
	}

	/**
	 * Whether any vital differs from what we last sent. Measured against the sent values rather than against
	 * our own stored snapshot, because run energy is reported coarsely: comparing to the exact reading would
	 * report a change on every tick that the wire would then round away.
	 */
	private boolean vitalsChanged() {
		return client.getBoostedSkillLevel(Skill.HITPOINTS) != sentHp
			|| client.getBoostedSkillLevel(Skill.PRAYER) != sentPrayer
			|| client.getVarpValue(300) / 10 != sentSpec
			|| runEnergy() != sentRunEnergy;
	}

	/**
	 * Whether a vital has moved down since the last send, without disturbing anything.
	 *
	 * <p>Read before deciding whether a tick may be skipped, which is why it cannot be the flag
	 * {@link #vitals()} sets — that one is a record of the frame being built, and by then the decision has
	 * been made.
	 */
	private boolean vitalsDropped() {
		return client.getBoostedSkillLevel(Skill.HITPOINTS) < sentHp
			|| client.getBoostedSkillLevel(Skill.PRAYER) < sentPrayer
			|| client.getVarpValue(300) / 10 < sentSpec;
	}

	/** Run energy as reported: rounded to {@link #RUN_ENERGY_STEP}, with empty and full kept exact. */
	private int runEnergy() {
		int energy = client.getEnergy() / 100;
		if (energy <= 0 || energy >= 100) {
			return energy;
		}
		return energy / RUN_ENERGY_STEP * RUN_ENERGY_STEP;
	}

	/**
	 * Everything has to go out again, and whole. Every caller is a moment where somebody holds nothing of
	 * ours (a fresh party, a reconnect, or a resync for a member who has just been seated) so the slot
	 * baseline goes with the flags: sending them a difference against a picture they never received would
	 * leave them looking at an inventory made of gaps.
	 */
	private void markAllDirty() {
		vitalsDirty = true;
		itemsDirty = true;
		profileDirty = true;
		codec.resetSlots();
	}

	@Override
	public void markItemsDirty() {
		itemsDirty = true;
	}

	@Override
	public void markStatsDirty(Skill skill, int realLevel) {
		// Fires on boosts too, which is most of them. Only a real level-up belongs in a profile frame.
		Integer sent = sentRealLevels.get(skill);
		if (sent != null && sent == realLevel) {
			return;
		}
		profileDirty = true;
	}

	/**
	 * Send whatever changed, as up to three frames.
	 *
	 * <p>The whole point of the split is that the common case — a vital moved and nothing else — never
	 * touches {@link LocalPlayerSnapshot#snapshot}, which reads 28 inventory slots and 23 skill levels to
	 * produce a payload we would then throw away. Items and profile share one snapshot when both are dirty.
	 *
	 * <p>The frame also says whether it wants relaying promptly. Only a vital that moved down does — see
	 * {@link #vitals()} — so an inventory change or a level-up rides the owner node's idle window.
	 */
	private void sendLocalState() {
		JsonObject full = null;
		if (itemsDirty || profileDirty) {
			PlayerUpdate update = LocalPlayerSnapshot.snapshot(client);
			if (update == null) {
				// Not logged in yet; leave the flags set and try again next tick.
				return;
			}
			stripPrivate(update);
			update.setRunEnergy(runEnergy());
			update.setPbSeconds(PersonalBests.read(configManager, currentActivityId, capacity));
			update.setRole(localRole);
			update.setLearner(localLearner);
			update.setTeacher(localTeacher);
			update.setInvited(localInvited);
			update.setMemberId(localMemberId);
			// Onto the wire names here, once: everything downstream (the projections, the merge, our own
			// stored copy) speaks the short form, and only PlayerUpdate itself needs the long one back.
			full = LiveStateCodec.toWire(gson.toJsonTree(update).getAsJsonObject());
			echoLocally(full);
		}
		JsonObject update = new JsonObject();
		boolean urgent = false;
		if (vitalsDirty) {
			LiveStateCodec.addAll(update, vitals());
			urgent = vitalsUrgent;
		}
		if (itemsDirty) {
			JsonObject items = LiveStateCodec.project(full, LiveStateCodec.ITEM_FIELDS);
			codec.sparsify(items);
			LiveStateCodec.addAll(update, items);
		}
		if (profileDirty) {
			LiveStateCodec.addAll(update, LiveStateCodec.project(full, LiveStateCodec.PROFILE_FIELDS));
			lastSentWorld = localWorld;
			rememberRealLevels();
		}
		if (update.size() == 0) {
			return;
		}
		send(new UpdateFrame(update, urgent));
		vitalsDirty = false;
		itemsDirty = false;
		profileDirty = false;
		fire();
	}

	/** Fold what we are about to send into our own row, merged exactly as a peer's frame would be. */
	private void echoLocally(JsonObject wire) {
		if (localMemberId == 0) {
			return;
		}
		JsonObject merged = LiveStateCodec.merge(rawState.get(localMemberId), wire);
		rawState.put(localMemberId, merged);
		playerData.put(localMemberId, gson.fromJson(LiveStateCodec.fromWire(merged), PlayerUpdate.class));
	}

	/** The four numbers that actually move, and nothing else. Built without a full snapshot. */
	private JsonObject vitals() {
		int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
		int prayer = client.getBoostedSkillLevel(Skill.PRAYER);
		int spec = client.getVarpValue(300) / 10;
		// A vital moving down is damage taken, prayer drained or a spec spent — the moments a peer reacts to,
		// and the only ones worth interrupting the owner node's idle window for. Everything that moves up
		// (regen, restores, spec recharging) can travel with the next round. The first send after a reset
		// compares against -1, so a reconnect is never mistaken for a drop.
		vitalsUrgent = hp < sentHp || prayer < sentPrayer || spec < sentSpec;
		sentHp = hp;
		sentPrayer = prayer;
		sentSpec = spec;
		sentRunEnergy = runEnergy();
		// Short keys, as named by LiveStateCodec.TO_WIRE: this frame is four small integers and would
		// otherwise be mostly the words describing them.
		JsonObject out = new JsonObject();
		out.addProperty("hp", sentHp);
		out.addProperty("pr", sentPrayer);
		out.addProperty("sp", sentSpec);
		out.addProperty("re", sentRunEnergy);
		echoLocally(out);
		return out;
	}

	/** Note the levels a profile frame just carried, so a later boost is not mistaken for a level-up. */
	private void rememberRealLevels() {
		for (Skill skill : Skill.values()) {
			try {
				sentRealLevels.put(skill, client.getRealSkillLevel(skill));
			}
			catch (Exception ignored) {
				// Placeholder/unreleased skills, as in LocalPlayerSnapshot.
			}
		}
	}

	@Override
	public void markLocalDirty() {
		// The catch-all, and the only caller is a privacy toggle, which changes what the item frame is
		// allowed to carry. Marking everything would be safe but would re-send the profile for nothing.
		itemsDirty = true;
	}

	@Override
	public void broadcastOffline(String name) {
		if (mode == Mode.NONE || localMemberId == 0) {
			return;
		}
		JsonObject offline = new JsonObject();
		offline.addProperty("n", name);
		offline.addProperty("wd", 0);
		offline.addProperty("memberId", localMemberId);
		echoLocally(offline);
		// Urgent: rare, and a peer showing someone as still present after they logged out is the kind of
		// staleness the idle window is not allowed to cause.
		send(new UpdateFrame(offline, true));
		lastSentWorld = 0;
		fire();
	}

	// ---- local self-report --------------------------------------------------

	@Override
	public void setLocalRole(String role) {
		if (java.util.Objects.equals(role, localRole)) {
			return;
		}
		localRole = role;
		profileDirty = true;
		fire();
	}

	@Override
	public String getLocalRole() {
		return localRole;
	}

	@Override
	public void setLocalLearner(boolean learner) {
		if (learner == localLearner) {
			return;
		}
		localLearner = learner;
		profileDirty = true;
		fire();
	}

	@Override
	public boolean isLocalLearner() {
		return localLearner;
	}

	@Override
	public void setLocalTeacher(boolean teacher) {
		if (teacher == localTeacher) {
			return;
		}
		localTeacher = teacher;
		profileDirty = true;
		fire();
	}

	@Override
	public boolean isLocalTeacher() {
		return localTeacher;
	}

	// ---- host state / actions -----------------------------------------------

	@Override
	public void setCapacity(int capacity) {
		if (mode != Mode.HOST || capacity == this.capacity) {
			return;
		}
		this.capacity = capacity;
		send(new CapacityFrame(capacity));
		fire();
	}

	@Override
	public void setDiscordInviteUrl(String url) {
		if (mode != Mode.HOST || java.util.Objects.equals(url, discordUrl)) {
			return;
		}
		discordUrl = url;
		send(new DiscordFrame(url));
		fire();
	}

	@Override
	public String discordInviteUrl() {
		return discordUrl;
	}

	@Override
	public void setPartyMeta(net.osparty.model.PartyMeta meta) {
		if (mode != Mode.HOST || meta == null || meta.equals(partyMeta)) {
			return;
		}
		partyMeta = meta;
		send(new MetaFrame(gson.toJsonTree(meta)));
	}

	@Override
	public net.osparty.model.PartyMeta partyMeta() {
		return partyMeta;
	}

	@Override
	public void setLocked(boolean locked) {
		if (mode != Mode.HOST || locked == this.locked) {
			return;
		}
		this.locked = locked;
		send(new LockedFrame(locked));
		fire();
	}

	@Override
	public boolean isLocked() {
		return locked;
	}

	@Override
	public boolean canAdmitMore() {
		return capacity <= 0 || admittedCount() < capacity;
	}

	@Override
	public boolean admit(long memberId, String name) {
		if (mode != Mode.HOST || !canAdmitMore()) {
			return false;
		}
		send(new CommandFrame("ADMIT", memberId, name));
		return true;
	}

	@Override
	public void kick(long memberId) {
		if (mode == Mode.HOST) {
			send(new CommandFrame("KICK", memberId, null));
		}
	}

	@Override
	public void reject(long memberId) {
		if (mode == Mode.HOST) {
			send(new CommandFrame("REJECT", memberId, null));
		}
	}

	// ---- map pings ----------------------------------------------------------

	@Override
	public boolean sendPing(WorldPoint point, Color color) {
		if (mode == Mode.NONE || point == null) {
			return false;
		}
		String name = localName;
		send(new PingFrame(point.getX(), point.getY(), point.getPlane(), color.getRGB(), name));
		addPing(new TilePing(point, name, color, System.currentTimeMillis()));
		fire();
		return true;
	}

	@Override
	public List<TilePing> activePings() {
		expirePings();
		return pings;
	}

	private void addPing(TilePing ping) {
		expirePings();
		pings.add(ping);
	}

	private void expirePings() {
		long now = System.currentTimeMillis();
		long duration = Math.max(1, config.pingAnimMs());
		pings.removeIf(p -> now - p.getCreatedAt() > duration);
	}

	// ---- markers ------------------------------------------------------------

	@Override
	public Map<String, PartyMarker> learnerMarkers() {
		Map<String, PartyMarker> markers = new HashMap<>();
		for (RosterMember member : roster()) {
			if (member.getStatus() == PartyStatus.PENDING || member.getName() == null) {
				continue;
			}
			boolean teacher;
			boolean learner;
			if (member.isLocal()) {
				teacher = localTeacher;
				learner = localLearner;
			}
			else {
				PlayerUpdate data = member.getData();
				teacher = data != null && data.isTeacher();
				learner = data != null && data.isLearner();
			}
			PartyMarker marker = teacher ? PartyMarker.TEACHER : learner ? PartyMarker.LEARNER : PartyMarker.NONE;
			if (marker != PartyMarker.NONE) {
				markers.put(PlayerNames.normalize(member.getName()), marker);
			}
		}
		return markers;
	}

	// ---- roster views -------------------------------------------------------

	@Override
	public List<RosterMember> roster() {
		long now = System.currentTimeMillis();
		List<RosterMember> out = new ArrayList<>();
		for (LivePartyChannel.RosterEntry entry : rosterEntries) {
			PartyStatus status = parseStatus(entry.status);
			PlayerUpdate data = playerData.get(entry.memberId);
			String name = data != null && data.getName() != null ? data.getName() : entry.name;
			boolean local = entry.memberId == localMemberId;
			// The room's word that a member's connection is gone settles it at once. Everything else is our
			// own reading of their silence, which cannot mean anything until it has lasted a while.
			boolean online = local
				|| (!entry.offline && isRecent(now, entry.memberId) && data != null && data.getWorld() > 0);
			out.add(new RosterMember(entry.memberId, name, status, data, local, online));
		}
		out.sort(Comparator.comparingInt((RosterMember m) -> m.getStatus().ordinal())
			.thenComparing(RosterMember::getName, Comparator.nullsLast(String::compareToIgnoreCase)));
		return out;
	}

	@Override
	public List<Member> rosterMembers() {
		List<Member> out = new ArrayList<>();
		LivePartyChannel.RosterEntry host = null;
		List<LivePartyChannel.RosterEntry> others = new ArrayList<>();
		for (LivePartyChannel.RosterEntry entry : rosterEntries) {
			if ("HOST".equals(entry.status)) {
				host = entry;
			}
			else if ("MEMBER".equals(entry.status)) {
				others.add(entry);
			}
		}
		if (host != null) {
			out.add(new Member(nameFor(host), accountHashFor(host)));
		}
		others.sort(Comparator.comparingLong(e -> e.memberId));
		for (LivePartyChannel.RosterEntry entry : others) {
			out.add(new Member(nameFor(entry), accountHashFor(entry)));
		}
		return out;
	}

	/** The member's live self-reported name, falling back to whatever the roster carries. */
	private String nameFor(LivePartyChannel.RosterEntry entry) {
		PlayerUpdate data = playerData.get(entry.memberId);
		return data != null && data.getName() != null ? data.getName() : entry.name;
	}

	@Override
	public List<Member> currentMembers() {
		List<Member> out = new ArrayList<>();
		for (RosterMember m : roster()) {
			if (m.getStatus() == PartyStatus.PENDING || m.getData() == null || isUnresolvedName(m.getName())) {
				continue;
			}
			out.add(new Member(m.getName(), m.getData().getAccountHash()));
		}
		return out;
	}

	private long accountHashFor(LivePartyChannel.RosterEntry entry) {
		PlayerUpdate data = playerData.get(entry.memberId);
		return data != null && data.getAccountHash() != 0 ? data.getAccountHash() : entry.accountHash;
	}

	@Override
	public long accountHashForMember(long memberId) {
		PlayerUpdate data = playerData.get(memberId);
		if (data != null && data.getAccountHash() != 0) {
			return data.getAccountHash();
		}
		for (LivePartyChannel.RosterEntry entry : rosterEntries) {
			if (entry.memberId == memberId) {
				return entry.accountHash;
			}
		}
		return 0L;
	}

	@Override
	public boolean isForLocalMember(long memberId) {
		return localMemberId != 0 && memberId == localMemberId;
	}

	@Override
	public boolean isLocalAdmitted() {
		return localStatus == PartyStatus.HOST || localStatus == PartyStatus.MEMBER;
	}

	@Override
	public boolean isPendingApplicant(long memberId) {
		if (mode != Mode.HOST) {
			return false;
		}
		for (LivePartyChannel.RosterEntry entry : rosterEntries) {
			if (entry.memberId == memberId) {
				return "PENDING".equals(entry.status);
			}
		}
		return false;
	}

	@Override
	public String currentActivityId() {
		return currentActivityId;
	}

	@Override
	public int hostWorld() {
		if (mode == Mode.HOST) {
			PlayerUpdate mine = playerData.get(localMemberId);
			return mine != null && mine.getWorld() > 0 ? mine.getWorld() : localWorld;
		}
		for (LivePartyChannel.RosterEntry entry : rosterEntries) {
			if ("HOST".equals(entry.status)) {
				PlayerUpdate data = playerData.get(entry.memberId);
				return data != null ? data.getWorld() : 0;
			}
		}
		return 0;
	}

	@Override
	public boolean onDifferentWorldThanHost() {
		int host = hostWorld();
		int mine = localWorld;
		return host > 0 && mine > 0 && host != mine;
	}

	@Override
	public List<String> neededRoles(List<String> requiredRoles) {
		net.osparty.model.Activity activity = net.osparty.model.Activity.fromId(currentActivityId);
		boolean flexible = activity != null && activity.hasFlexibleRoles();
		if (!flexible && (requiredRoles == null || requiredRoles.isEmpty())) {
			return requiredRoles;
		}
		List<String> taken = new ArrayList<>();
		for (RosterMember member : roster()) {
			if (member.getStatus() == PartyStatus.PENDING) {
				continue;
			}
			String role = member.isLocal() ? localRole
				: (member.getData() != null ? member.getData().getRole() : null);
			if (role != null) {
				taken.add(role);
			}
		}
		if (flexible) {
			return activity.flexibleNeededRoles(taken, capacity > 0 ? capacity : taken.size());
		}
		String fillId = fillRoleId(activity, requiredRoles);
		List<String> remaining = new ArrayList<>(requiredRoles);
		for (String role : taken) {
			if (!remaining.remove(role) && fillId != null) {
				remaining.remove(fillId);
			}
		}
		return remaining;
	}

	private static String fillRoleId(net.osparty.model.Activity activity, List<String> requiredRoles) {
		if (activity == null) {
			return null;
		}
		for (boolean hardMode : new boolean[]{false, true}) {
			net.osparty.model.Role fill = activity.fillRole(hardMode);
			if (fill != null && requiredRoles.contains(fill.getId())) {
				return fill.getId();
			}
		}
		return null;
	}

	private boolean isRecent(long now, long memberId) {
		Long seen = lastSeen.get(memberId);
		return seen != null && now - seen < ONLINE_TIMEOUT_MS;
	}

	private static boolean isUnresolvedName(String name) {
		return name == null || name.trim().isEmpty() || "<unknown>".equalsIgnoreCase(name.trim());
	}

	private static PartyStatus parseStatus(String status) {
		if (status == null) {
			return PartyStatus.PENDING;
		}
		try {
			return PartyStatus.valueOf(status);
		}
		catch (IllegalArgumentException e) {
			return PartyStatus.PENDING;
		}
	}

	private void sendHost() {
		send(new HostFrame(roomKey, hostName, currentActivityId, capacity, locked, localRole,
			localLearner, localTeacher, localAccountHash));
	}

	private void sendJoin() {
		// A room rebuilt on a new owner seats everyone from scratch, so a member that was already admitted
		// has to say so — otherwise every handover dumps the whole party back into the host's applicant
		// queue to be re-admitted one by one. Admission is client-asserted either way (the server takes
		// `invited` on trust), so this claims nothing an ordinary reconnect could not already claim.
		boolean admitted = localInvited || localStatus == PartyStatus.MEMBER;
		send(new JoinFrame(roomKey, currentActivityId, localRole, localLearner, localTeacher, admitted,
			localName, localAccountHash));
	}

	private void fire() {
		for (Runnable listener : listeners) {
			listener.run();
		}
	}

	// ---- ready check --------------------------------------------------------

	@Override
	public void setOnReadyCheckStarted(Consumer<String> onReadyCheckStarted) {
		this.onReadyCheckStarted = onReadyCheckStarted;
	}

	@Override
	public void setOnAllReady(Runnable onAllReady) {
		this.onAllReady = onAllReady;
	}

	@Override
	public void setOnReadyExpired(Runnable onReadyExpired) {
		this.onReadyExpired = onReadyExpired;
	}

	/** Start a ready check (anyone in the party may). The starter counts as ready. */
	@Override
	public void startReadyCheck() {
		if (!isInParty() || localMemberId == 0 || onDifferentWorldThanHost()) {
			return;
		}
		long id = (localMemberId << 16) | (++readyCheckSeq & 0xFFFF);
		beginReadyCheck(id, localName, localMemberId);
		send(new ReadyStartFrame(id, readyCheckStarter));
		// Fire locally too: the server doesn't echo our own frame back to us.
		Consumer<String> cb = onReadyCheckStarted;
		if (cb != null) {
			cb.accept(readyCheckStarter);
		}
		fire();
	}

	@Override
	public void markReady() {
		// Guard against a stale enabled button: never ready up from another world than the host.
		if (readyCheckId == 0 || onDifferentWorldThanHost()) {
			return;
		}
		readyMembers.add(localMemberId);
		send(new ReadyFrame(readyCheckId));
		checkAllReady();
		fire();
	}

	private void applyReadyStart(LivePartyChannel.Frame frame) {
		if (frame.checkId == null || frame.memberId == null) {
			return;
		}
		beginReadyCheck(frame.checkId, frame.starter, frame.memberId);
		Consumer<String> cb = onReadyCheckStarted;
		if (cb != null) {
			cb.accept(frame.starter);
		}
		fire();
	}

	private void applyReady(LivePartyChannel.Frame frame) {
		if (frame.checkId == null || frame.memberId == null
			|| readyCheckId == 0 || frame.checkId != readyCheckId) {
			return;
		}
		readyMembers.add(frame.memberId);
		checkAllReady();
		fire();
	}

	private void beginReadyCheck(long id, String starter, long starterMemberId) {
		readyCheckId = id;
		readyCheckStartedAt = System.currentTimeMillis();
		readyCheckStarter = starter != null ? starter : "Someone";
		readyAllNotified.set(false);
		readyMembers.clear();
		readyMembers.add(starterMemberId);
	}

	private void clearReadyCheck() {
		readyCheckId = 0;
		readyCheckStartedAt = 0;
		readyCheckStarter = null;
		readyMembers.clear();
		readyAllNotified.set(false);
	}

	/** Fire the all-ready callback once when every (non-pending) member is ready. */
	private void checkAllReady() {
		if (readyCheckId == 0) {
			return;
		}
		java.util.Set<Long> required = activeMemberIds();
		if (required.isEmpty() || !readyMembers.containsAll(required)) {
			return;
		}
		if (readyAllNotified.compareAndSet(false, true)) {
			Runnable cb = onAllReady;
			if (cb != null) {
				cb.run();
			}
			clearReadyCheck();
		}
	}

	private void expireReadyCheck() {
		if (readyCheckId == 0) {
			return;
		}
		if (System.currentTimeMillis() - readyCheckStartedAt > READY_CHECK_TIMEOUT_MS) {
			clearReadyCheck();
			Runnable cb = onReadyExpired;
			if (cb != null) {
				cb.run();
			}
			fire();
		}
	}

	/** Member ids that must ready up: everyone admitted (host + members), not pending. */
	private java.util.Set<Long> activeMemberIds() {
		java.util.Set<Long> ids = new java.util.HashSet<>();
		for (LivePartyChannel.RosterEntry entry : rosterEntries) {
			if (!"PENDING".equals(entry.status)) {
				ids.add(entry.memberId);
			}
		}
		return ids;
	}

	@Override
	public ReadyCheckStatus readyCheck() {
		long id = readyCheckId;
		if (id == 0) {
			return null;
		}
		java.util.Set<Long> required = activeMemberIds();
		int ready = 0;
		for (long memberId : required) {
			if (readyMembers.contains(memberId)) {
				ready++;
			}
		}
		// Round up: the check reads "30s" for its whole first second and only hits 0 at expiry.
		long leftMs = Math.max(0, READY_CHECK_TIMEOUT_MS - (System.currentTimeMillis() - readyCheckStartedAt));
		long left = (leftMs + 999) / 1000;
		return new ReadyCheckStatus(readyCheckStarter, ready, required.size(), (int) left,
			readyMembers.contains(localMemberId));
	}

	// ---- spec drains --------------------------------------------------------

	/** Broadcast a defence-draining spec so every member's defence tracker sees the whole party's drain. */
	@Override
	public void sendSpecDrain(int npcIndex, String weapon, int hit, int world) {
		if (mode == Mode.NONE) {
			return;
		}
		send(new SpecDrainFrame(npcIndex, weapon, hit, world));
	}

	private void applySpecDrain(LivePartyChannel.Frame frame) {
		if (frame.memberId == null || frame.weapon == null) {
			return;
		}
		net.osparty.enums.SpecWeapon weapon;
		try {
			weapon = net.osparty.enums.SpecWeapon.valueOf(frame.weapon);
		}
		catch (IllegalArgumentException e) {
			return;
		}
		eventBus.post(new SpecDrainEvent(frame.memberId,
			frame.npcIndex == null ? -1 : frame.npcIndex, weapon,
			frame.hit == null ? 0 : frame.hit, frame.world == null ? 0 : frame.world));
	}

	// ---- friends-chat / join prompts ----------------------------------------

	@Override
	public void sendJoinPrompt(long targetMemberId, String kind, String friendsChat) {
		if (mode != Mode.HOST) {
			return;
		}
		send(new JoinPromptFrame(targetMemberId, kind, friendsChat));
	}

	private void applyJoinPrompt(LivePartyChannel.Frame frame) {
		JoinPromptEvent request = new JoinPromptEvent();
		// The server only delivers this to its target, so it is always aimed at us.
		request.setTargetMemberId(localMemberId);
		request.setHostName(frame.host != null ? frame.host : hostName);
		request.setKind(frame.kind);
		request.setFriendsChat(frame.friendsChat);
		if (frame.memberId != null) {
			request.setMemberId(frame.memberId);
		}
		eventBus.post(request);
	}

	// ---- host transfer ------------------------------------------------------

	/**
	 * Take over as host without leaving the room. The server has already moved the authoritative HOST
	 * status (it does so on COMMIT), so this only flips our local mode; the roster arrives as usual.
	 */
	@Override
	public void promoteToHost(String hostName) {
		mode = Mode.HOST;
		this.hostName = hostName;
		profileDirty = true;
		fire();
	}

	/** Step down from hosting while staying in the room. */
	@Override
	public void demoteToMember() {
		mode = Mode.MEMBER;
		// Our published ad settings still name us as host; drop them rather than re-applying them over the
		// handover, and wait for the new host to publish its own.
		partyMeta = null;
		fire();
	}

	@Override
	public void offerHostTransfer(long targetMemberId, String newHostKey, String newHostName, boolean hostStays) {
		send(new TransferHostFrame("OFFER", targetMemberId, newHostKey, newHostName, hostStays));
	}

	@Override
	public void acceptHostTransfer(long oldHostMemberId) {
		send(new TransferHostFrame("ACCEPT", oldHostMemberId, null, null, false));
	}

	@Override
	public void commitHostTransfer(long targetMemberId, String newHostKey, boolean hostStays) {
		send(new TransferHostFrame("COMMIT", targetMemberId, newHostKey, null, hostStays));
	}

	@Override
	public void abortHostTransfer(long targetMemberId) {
		send(new TransferHostFrame("ABORT", targetMemberId, null, null, false));
	}

	private void applyTransferHost(LivePartyChannel.Frame frame) {
		if (frame.kind == null || frame.memberId == null) {
			return;
		}
		HostTransferEvent message = new HostTransferEvent();
		try {
			message.setKind(HostTransferEvent.Kind.valueOf(frame.kind));
		}
		catch (IllegalArgumentException e) {
			return;
		}
		// Targeted delivery: the server only sends this to the member it is aimed at.
		message.setTargetMemberId(localMemberId);
		message.setNewHostKey(frame.newHostKey);
		message.setNewHostName(frame.newHostName);
		message.setHostStays(Boolean.TRUE.equals(frame.hostStays));
		message.setMemberId(frame.memberId);
		eventBus.post(message);
	}

	@Override
	public void generatePassphrase(Consumer<String> onGenerated) {
		String token = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		SwingUtilities.invokeLater(() -> onGenerated.accept(token));
	}
}
