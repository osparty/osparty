package net.osparty.party.v2;

import com.google.gson.Gson;
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
import net.osparty.party.LiveParty;
import net.osparty.party.LiveParty.Marker;
import net.osparty.party.LiveParty.RosterMember;
import net.osparty.party.LiveParty.Status;
import net.osparty.party.LivePartyBackend;
import net.osparty.party.LocalPlayerSync;
import net.osparty.party.PlayerUpdate;
import net.osparty.party.TilePing;
import net.osparty.tools.PersonalBests;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;

/**
 * {@link LivePartyBackend} over OSParty's own live endpoint ({@link PartyV2Socket}), replacing RuneLite's
 * P2P relay. The roster is server-authoritative (received in {@code roster} frames); live member snapshots
 * are relayed {@link PlayerUpdate}s. Structurally mirrors {@link LiveParty} so the UI is unchanged.
 * See PARTY_V2_MIGRATION.md.
 *
 * <p>P1 covers the core loop: host/join, live state, server-authoritative roster/admission, kick and map
 * pings. Ready checks, host transfer, spec drains and friends-chat prompts are marked {@code P3} and no-op
 * for now. Inbound RuneLite {@code @Subscribe} handlers ({@code onPlayerUpdate} etc.) are no-ops here — in
 * V2 those events never arrive; state comes over the socket instead.
 */
@Slf4j
@Singleton
public class LivePartyV2 implements LivePartyBackend {
	private enum Mode { NONE, HOST, MEMBER }

	private static final long ONLINE_TIMEOUT_MS = 20_000;
	private static final int LOCAL_REBROADCAST_TICKS = 10;

	private final Client client;
	private final ConfigManager configManager;
	private final OSPartyConfig config;
	private final PartyV2Socket socket;
	private final Gson gson;
	/**
	 * V2 has no RuneLite party bus, so inbound spec drains / join prompts / host-transfer steps are re-posted
	 * here as the same message objects the RuneLite backend produced. The plugin's existing subscribers
	 * (defence tracker, panel, FC popup) then run unchanged.
	 */
	private final EventBus eventBus;

	private volatile Mode mode = Mode.NONE;
	private volatile String roomKey;
	private volatile String currentActivityId;
	private volatile int capacity;
	private volatile boolean locked;

	// Server-authoritative roster (last roster frame) + room meta.
	private volatile List<PartyV2Socket.RosterEntry> rosterEntries = List.of();
	private volatile String hostName;
	private volatile String discordUrl;
	/** The advertised party's settings: ours to publish while hosting, the host's to follow as a member. */
	private volatile net.osparty.model.PartyMeta partyMeta;

	// Live per-member snapshots, keyed by server-assigned member id.
	private final Map<Long, PlayerUpdate> playerData = new ConcurrentHashMap<>();
	private final Map<Long, Long> lastSeen = new ConcurrentHashMap<>();

	private volatile long localMemberId;
	private volatile Status localStatus;

	// Local self-report.
	private volatile String localRole;
	private volatile boolean localLearner;
	private volatile boolean localTeacher;
	private volatile boolean localInvited;
	private volatile boolean localDirty;
	private int ticksSinceLocalBroadcast;

	// Cached local identity (read on the client thread in tick(); used off-thread when (re)sending frames).
	private volatile long localAccountHash;
	private volatile String localName;
	private volatile int localWorld;
	/** Identity last announced to the server, so a resolved name/hash is re-sent exactly once. */
	private volatile String announcedName;
	private volatile long announcedAccountHash;

	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private volatile Runnable onEnded;
	private final List<TilePing> pings = new CopyOnWriteArrayList<>();

	// ---- ready check (one active per party; same semantics as LiveParty) -----
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
	private LivePartyV2(Client client, ConfigManager configManager, OSPartyConfig config,
		PartyV2Socket socket, Gson gson, EventBus eventBus) {
		this.client = client;
		this.configManager = configManager;
		this.config = config;
		this.socket = socket;
		this.gson = gson;
		this.eventBus = eventBus;
	}

	// ---- lifecycle ----------------------------------------------------------

	/**
	 * Wires the socket but does not connect it: there is nothing to relay until we are in a party, and a
	 * socket held open from plugin start would cost a server session for every logged-in user rather than
	 * for every user actually partying. {@link #hostParty}/{@link #joinParty} connect; {@link #leave} and
	 * {@link #end} disconnect.
	 */
	@Override
	public void register() {
		socket.setListener(this::onFrame);
		socket.setOnOpen(this::onOpen);
	}

	@Override
	public void unregister() {
		socket.stop();
		reset();
	}

	/** On every (re)connect: re-announce identity, re-assert host/join, and re-send our state next tick. */
	private void onOpen() {
		// The server re-seats us from scratch on a reconnect, so our identity has to be announced again.
		announcedName = localName;
		announcedAccountHash = localAccountHash;
		socket.send(new HelloFrame(localAccountHash, localName));
		if (mode == Mode.HOST) {
			sendHost();
			// A room rebuilt on a new owner holds no ad settings, so republish ours rather than leaving
			// every member on the copy it took when it applied.
			if (partyMeta != null) {
				socket.send(new MetaFrame(gson.toJsonTree(partyMeta)));
			}
		}
		else if (mode == Mode.MEMBER) {
			sendJoin();
		}
		localDirty = true;
	}

	@Override
	public void addListener(Runnable listener) {
		listeners.add(listener);
	}

	@Override
	public void setOnEnded(Runnable onEnded) {
		this.onEnded = onEnded;
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
		localDirty = true;
		// Connect after the mode is set: whether we arrive connected or not, onOpen is what re-sends this
		// frame, and it reads mode to decide between host and join. sendHost() below covers the case where
		// the socket is already up (hosting straight after another party).
		socket.start();
		sendHost();
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
		localDirty = true;
		socket.start();
		sendJoin();
		fire();
	}

	@Override
	public void leave() {
		if (mode != Mode.NONE) {
			socket.send(new LeaveFrame());
		}
		reset();
		// After the leave frame: OkHttp transmits what is already queued before it sends the close.
		socket.stop();
		fire();
	}

	@Override
	public void leaveForSwitch() {
		// The subsequent join re-keys our session server-side; no explicit leave needed. The socket stays up
		// for the same reason — stopping it here would only cost the join a reconnect.
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
		lastSeen.clear();
		localMemberId = 0;
		localStatus = null;
		localRole = null;
		localLearner = false;
		localTeacher = false;
		localInvited = false;
		localDirty = false;
		ticksSinceLocalBroadcast = 0;
		pings.clear();
		clearReadyCheck();
		announcedName = null;
		announcedAccountHash = 0;
	}

	@Override
	public boolean isConnected() {
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

	private void onFrame(PartyV2Socket.Frame frame) {
		switch (frame.type) {
			case "welcome":
				localMemberId = frame.memberId == null ? 0 : frame.memberId;
				localStatus = parseStatus(frame.status);
				fire();
				break;
			case "roster":
				applyRoster(frame);
				break;
			case "memberState":
				applyMemberState(frame);
				break;
			case "meta":
				applyMeta(frame);
				break;
			case "memberLeft":
				if (frame.memberId != null) {
					playerData.remove(frame.memberId);
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
				applyFcRequest(frame);
				break;
			case "transferHost":
				applyTransferHost(frame);
				break;
			case "kicked":
				end();
				break;
			case "error":
				log.debug("Party V2 error: {}", frame.detail);
				break;
			default:
				break;
		}
	}

	private void applyRoster(PartyV2Socket.Frame frame) {
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
		for (PartyV2Socket.RosterEntry entry : rosterEntries) {
			if (entry.memberId == localMemberId) {
				localStatus = parseStatus(entry.status);
			}
		}
		if (Boolean.TRUE.equals(frame.closed)) {
			end();
			return;
		}
		fire();
	}

	private void applyMemberState(PartyV2Socket.Frame frame) {
		if (frame.memberId == null || frame.state == null) {
			return;
		}
		PlayerUpdate update;
		try {
			update = gson.fromJson(frame.state, PlayerUpdate.class);
		}
		catch (Exception e) {
			return;
		}
		if (update == null) {
			return;
		}
		update.setMemberId(frame.memberId);
		playerData.put(frame.memberId, update);
		lastSeen.put(frame.memberId, System.currentTimeMillis());
		fire();
	}

	private void applyMeta(PartyV2Socket.Frame frame) {
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

	private void applyPing(PartyV2Socket.Frame frame) {
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
		socket.stop();
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

		if (!localDirty && vitalsChanged()) {
			localDirty = true;
		}
		if (++ticksSinceLocalBroadcast >= LOCAL_REBROADCAST_TICKS) {
			// Belt-and-braces resync; the server also snapshots new joiners so this stays infrequent.
			localDirty = true;
		}
		if (localDirty) {
			PlayerUpdate update = LocalPlayerSync.snapshot(client);
			if (update != null) {
				if (config.hideInventory()) {
					update.setInventory(null);
					update.setInventoryQuantities(null);
					update.setRunePouch(null);
					update.setRunePouchAmounts(null);
					update.setRunePouchNames(null);
				}
				if (config.hideGear()) {
					update.setEquipment(null);
				}
				update.setPbSeconds(PersonalBests.read(configManager, currentActivityId, capacity));
				update.setRole(localRole);
				update.setLearner(localLearner);
				update.setTeacher(localTeacher);
				update.setInvited(localInvited);
				update.setMemberId(localMemberId);
				if (localMemberId != 0) {
					playerData.put(localMemberId, update);
				}
				socket.send(new StateFrame(update));
				localDirty = false;
				ticksSinceLocalBroadcast = 0;
				fire();
			}
		}
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
		socket.send(new HelloFrame(accountHash, name));
	}

	private boolean vitalsChanged() {
		PlayerUpdate self = playerData.get(localMemberId);
		if (self == null) {
			return false;
		}
		return self.getCurrentHp() != client.getBoostedSkillLevel(Skill.HITPOINTS)
			|| self.getCurrentPrayer() != client.getBoostedSkillLevel(Skill.PRAYER)
			|| self.getSpecialPercent() != client.getVarpValue(300) / 10
			|| self.getRunEnergy() != client.getEnergy() / 100;
	}

	@Override
	public void markLocalDirty() {
		localDirty = true;
	}

	@Override
	public void broadcastOffline(String name) {
		if (mode == Mode.NONE || localMemberId == 0) {
			return;
		}
		PlayerUpdate update = new PlayerUpdate();
		update.setName(name);
		update.setWorld(0);
		update.setMemberId(localMemberId);
		playerData.put(localMemberId, update);
		socket.send(new StateFrame(update));
		fire();
	}

	// ---- local self-report --------------------------------------------------

	@Override
	public void setLocalRole(String role) {
		if (java.util.Objects.equals(role, localRole)) {
			return;
		}
		localRole = role;
		localDirty = true;
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
		localDirty = true;
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
		localDirty = true;
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
		socket.send(new CapacityFrame(capacity));
		fire();
	}

	@Override
	public void setDiscordInviteUrl(String url) {
		if (mode != Mode.HOST || java.util.Objects.equals(url, discordUrl)) {
			return;
		}
		discordUrl = url;
		socket.send(new DiscordFrame(url));
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
		socket.send(new MetaFrame(gson.toJsonTree(meta)));
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
		socket.send(new LockedFrame(locked));
		fire();
	}

	@Override
	public boolean isLocked() {
		return locked;
	}

	@Override
	public boolean canAdmitMore() {
		if (capacity <= 0) {
			return true;
		}
		int admitted = 0;
		for (PartyV2Socket.RosterEntry entry : rosterEntries) {
			if (!"PENDING".equals(entry.status)) {
				admitted++;
			}
		}
		return admitted < capacity;
	}

	@Override
	public boolean admit(long memberId, String name) {
		if (mode != Mode.HOST || !canAdmitMore()) {
			return false;
		}
		socket.send(new CommandFrame("ADMIT", memberId, name));
		return true;
	}

	@Override
	public void kick(long memberId) {
		if (mode == Mode.HOST) {
			socket.send(new CommandFrame("KICK", memberId, null));
		}
	}

	@Override
	public void reject(long memberId) {
		if (mode == Mode.HOST) {
			socket.send(new CommandFrame("REJECT", memberId, null));
		}
	}

	// ---- map pings ----------------------------------------------------------

	@Override
	public void sendPing(WorldPoint point, Color color) {
		if (mode == Mode.NONE || point == null) {
			return;
		}
		String name = localName;
		socket.send(new PingFrame(point.getX(), point.getY(), point.getPlane(), color.getRGB(), name));
		addPing(new TilePing(point, name, color, System.currentTimeMillis()));
		fire();
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
	public Map<String, Marker> learnerMarkers() {
		Map<String, Marker> markers = new HashMap<>();
		for (RosterMember member : roster()) {
			if (member.getStatus() == Status.PENDING || member.getName() == null) {
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
			Marker marker = teacher ? Marker.TEACHER : learner ? Marker.LEARNER : Marker.NONE;
			if (marker != Marker.NONE) {
				markers.put(LiveParty.normalizeName(member.getName()), marker);
			}
		}
		return markers;
	}

	// ---- roster views -------------------------------------------------------

	@Override
	public List<RosterMember> roster() {
		long now = System.currentTimeMillis();
		List<RosterMember> out = new ArrayList<>();
		for (PartyV2Socket.RosterEntry entry : rosterEntries) {
			Status status = parseStatus(entry.status);
			PlayerUpdate data = playerData.get(entry.memberId);
			String name = data != null && data.getName() != null ? data.getName() : entry.name;
			boolean local = entry.memberId == localMemberId;
			boolean online = local || (isRecent(now, entry.memberId) && data != null && data.getWorld() > 0);
			out.add(new RosterMember(entry.memberId, name, status, data, local, online));
		}
		out.sort(Comparator.comparingInt((RosterMember m) -> m.getStatus().ordinal())
			.thenComparing(RosterMember::getName, Comparator.nullsLast(String::compareToIgnoreCase)));
		return out;
	}

	@Override
	public List<Member> rosterMembers() {
		List<Member> out = new ArrayList<>();
		PartyV2Socket.RosterEntry host = null;
		List<PartyV2Socket.RosterEntry> others = new ArrayList<>();
		for (PartyV2Socket.RosterEntry entry : rosterEntries) {
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
		for (PartyV2Socket.RosterEntry entry : others) {
			out.add(new Member(nameFor(entry), accountHashFor(entry)));
		}
		return out;
	}

	/** The member's live self-reported name, falling back to whatever the roster carries. */
	private String nameFor(PartyV2Socket.RosterEntry entry) {
		PlayerUpdate data = playerData.get(entry.memberId);
		return data != null && data.getName() != null ? data.getName() : entry.name;
	}

	@Override
	public List<Member> currentMembers() {
		List<Member> out = new ArrayList<>();
		for (RosterMember m : roster()) {
			if (m.getStatus() == Status.PENDING || m.getData() == null || isUnresolvedName(m.getName())) {
				continue;
			}
			out.add(new Member(m.getName(), m.getData().getAccountHash()));
		}
		return out;
	}

	private long accountHashFor(PartyV2Socket.RosterEntry entry) {
		PlayerUpdate data = playerData.get(entry.memberId);
		return data != null && data.getAccountHash() != 0 ? data.getAccountHash() : entry.accountHash;
	}

	@Override
	public long accountHashForMember(long memberId) {
		PlayerUpdate data = playerData.get(memberId);
		if (data != null && data.getAccountHash() != 0) {
			return data.getAccountHash();
		}
		for (PartyV2Socket.RosterEntry entry : rosterEntries) {
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
		return localStatus == Status.HOST || localStatus == Status.MEMBER;
	}

	@Override
	public boolean isPendingApplicant(long memberId) {
		if (mode != Mode.HOST) {
			return false;
		}
		for (PartyV2Socket.RosterEntry entry : rosterEntries) {
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
		for (PartyV2Socket.RosterEntry entry : rosterEntries) {
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
			if (member.getStatus() == Status.PENDING) {
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

	private static Status parseStatus(String status) {
		if (status == null) {
			return Status.PENDING;
		}
		try {
			return Status.valueOf(status);
		}
		catch (IllegalArgumentException e) {
			return Status.PENDING;
		}
	}

	private void sendHost() {
		socket.send(new HostFrame(roomKey, hostName, currentActivityId, capacity, locked, localRole,
			localLearner, localTeacher, localAccountHash));
	}

	private void sendJoin() {
		// A room rebuilt on a new owner seats everyone from scratch, so a member that was already admitted
		// has to say so — otherwise every handover dumps the whole party back into the host's applicant
		// queue to be re-admitted one by one. Admission is client-asserted either way (the server takes
		// `invited` on trust), so this claims nothing an ordinary reconnect could not already claim.
		boolean admitted = localInvited || localStatus == Status.MEMBER;
		socket.send(new JoinFrame(roomKey, currentActivityId, localRole, localLearner, admitted,
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
		if (!isConnected() || localMemberId == 0 || onDifferentWorldThanHost()) {
			return;
		}
		long id = (localMemberId << 16) | (++readyCheckSeq & 0xFFFF);
		beginReadyCheck(id, localName, localMemberId);
		socket.send(new ReadyStartFrame(id, readyCheckStarter));
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
		socket.send(new ReadyFrame(readyCheckId));
		checkAllReady();
		fire();
	}

	@Override
	public void onReadyCheck(net.osparty.party.ReadyCheckMessage message) {
		// No-op in V2: ready checks arrive as socket frames (see applyReadyStart / applyReady).
	}

	private void applyReadyStart(PartyV2Socket.Frame frame) {
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

	private void applyReady(PartyV2Socket.Frame frame) {
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
		for (PartyV2Socket.RosterEntry entry : rosterEntries) {
			if (!"PENDING".equals(entry.status)) {
				ids.add(entry.memberId);
			}
		}
		return ids;
	}

	@Override
	public LiveParty.ReadyCheckStatus readyCheck() {
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
		return new LiveParty.ReadyCheckStatus(readyCheckStarter, ready, required.size(), (int) left,
			readyMembers.contains(localMemberId));
	}

	// ---- spec drains --------------------------------------------------------

	/** Broadcast a defence-draining spec so every member's defence tracker sees the whole party's drain. */
	@Override
	public void sendSpecDrain(int npcIndex, String weapon, int hit, int world) {
		if (mode == Mode.NONE) {
			return;
		}
		socket.send(new SpecDrainFrame(npcIndex, weapon, hit, world));
	}

	private void applySpecDrain(PartyV2Socket.Frame frame) {
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
		net.osparty.party.SpecDrainMessage message = new net.osparty.party.SpecDrainMessage(
			frame.npcIndex == null ? -1 : frame.npcIndex, weapon,
			frame.hit == null ? 0 : frame.hit, frame.world == null ? 0 : frame.world);
		message.setMemberId(frame.memberId);
		eventBus.post(message);
	}

	// ---- friends-chat / join prompts ----------------------------------------

	@Override
	public void requestFriendsChat(long targetMemberId, String friendsChat) {
		sendJoinPrompt(targetMemberId, "FC", friendsChat);
	}

	@Override
	public void sendJoinPrompt(long targetMemberId, String kind, String friendsChat) {
		if (mode != Mode.HOST) {
			return;
		}
		socket.send(new FcRequestFrame(targetMemberId, kind, friendsChat));
	}

	private void applyFcRequest(PartyV2Socket.Frame frame) {
		net.osparty.party.FcRequestMessage request = new net.osparty.party.FcRequestMessage();
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
		localDirty = true;
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
		socket.send(new TransferHostFrame("OFFER", targetMemberId, newHostKey, newHostName, hostStays));
	}

	@Override
	public void acceptHostTransfer(long oldHostMemberId) {
		socket.send(new TransferHostFrame("ACCEPT", oldHostMemberId, null, null, false));
	}

	@Override
	public void commitHostTransfer(long targetMemberId, String newHostKey, boolean hostStays) {
		socket.send(new TransferHostFrame("COMMIT", targetMemberId, newHostKey, null, hostStays));
	}

	@Override
	public void abortHostTransfer(long targetMemberId) {
		socket.send(new TransferHostFrame("ABORT", targetMemberId, null, null, false));
	}

	private void applyTransferHost(PartyV2Socket.Frame frame) {
		if (frame.kind == null || frame.memberId == null) {
			return;
		}
		net.osparty.party.HostTransferMessage message = new net.osparty.party.HostTransferMessage();
		try {
			message.setKind(net.osparty.party.HostTransferMessage.Kind.valueOf(frame.kind));
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

	// ---- P3 remainder -------------------------------------------------------

	@Override
	public void generatePassphrase(Consumer<String> onGenerated) {
		String token = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		SwingUtilities.invokeLater(() -> onGenerated.accept(token));
	}

	@Override
	public void rememberResumedRoster(List<Member> members) {
		// Not needed in V2: the room lives on the owner node, so a host restart rejoins the existing room
		// with its roster intact rather than rebuilding it from applicants.
	}

	@Override
	public void onPlayerUpdate(PlayerUpdate update) {
		// No-op: V2 receives state over the socket, not via RuneLite's event bus.
	}

	@Override
	public void onPartyState(net.osparty.party.PartyStateMessage state) {
		// No-op in V2 (RuneLite relay message).
	}

	@Override
	public void onMemberCommand(net.osparty.party.MemberCommand command) {
		// No-op in V2 (RuneLite relay message).
	}

	@Override
	public void onPing(net.osparty.party.PingMessage message) {
		// No-op in V2: pings arrive as socket frames (see applyPing).
	}

	@Override
	public void onPeerJoined(long memberId) {
		// No-op in V2 (RuneLite party event).
	}

	@Override
	public void onPeerLeft(long memberId) {
		// No-op in V2 (RuneLite party event).
	}

	// ---- outbound frame shapes (Gson omits nulls) ---------------------------

	private static final class HelloFrame {
		final String type = "hello";
		final long accountHash;
		final String name;

		HelloFrame(long accountHash, String name) {
			this.accountHash = accountHash;
			this.name = name;
		}
	}

	private static final class HostFrame {
		final String type = "host";
		final String room;
		final String hostName;
		final String activityId;
		final Integer capacity;
		final Boolean locked;
		final String role;
		final Boolean learner;
		final Boolean teacher;
		final long accountHash;

		HostFrame(String room, String hostName, String activityId, int capacity, boolean locked, String role,
			boolean learner, boolean teacher, long accountHash) {
			this.room = room;
			this.hostName = hostName;
			this.activityId = activityId;
			this.capacity = capacity;
			this.locked = locked;
			this.role = role;
			this.learner = learner;
			this.teacher = teacher;
			this.accountHash = accountHash;
		}
	}

	private static final class JoinFrame {
		final String type = "join";
		final String room;
		final String activityId;
		final String role;
		final Boolean learner;
		final Boolean invited;
		final String name;
		final long accountHash;

		JoinFrame(String room, String activityId, String role, boolean learner, boolean invited, String name,
			long accountHash) {
			this.room = room;
			this.activityId = activityId;
			this.role = role;
			this.learner = learner;
			this.invited = invited;
			this.name = name;
			this.accountHash = accountHash;
		}
	}

	private static final class StateFrame {
		final String type = "state";
		final Object state;

		StateFrame(Object state) {
			this.state = state;
		}
	}

	private static final class PingFrame {
		final String type = "ping";
		final int x;
		final int y;
		final int plane;
		final int color;
		final String name;

		PingFrame(int x, int y, int plane, int color, String name) {
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.color = color;
			this.name = name;
		}
	}

	private static final class CommandFrame {
		final String type = "command";
		final String action;
		final long target;
		final String name;

		CommandFrame(String action, long target, String name) {
			this.action = action;
			this.target = target;
			this.name = name;
		}
	}

	private static final class CapacityFrame {
		final String type = "setCapacity";
		final int capacity;

		CapacityFrame(int capacity) {
			this.capacity = capacity;
		}
	}

	private static final class LockedFrame {
		final String type = "setLocked";
		final boolean locked;

		LockedFrame(boolean locked) {
			this.locked = locked;
		}
	}

	private static final class MetaFrame {
		final String type = "setMeta";
		final Object meta;

		MetaFrame(Object meta) {
			this.meta = meta;
		}
	}

	private static final class DiscordFrame {
		final String type = "setDiscord";
		final String url;

		DiscordFrame(String url) {
			this.url = url;
		}
	}

	private static final class LeaveFrame {
		final String type = "leave";
	}

	private static final class ReadyStartFrame {
		final String type = "readyStart";
		final long checkId;
		final String starter;

		ReadyStartFrame(long checkId, String starter) {
			this.checkId = checkId;
			this.starter = starter;
		}
	}

	private static final class ReadyFrame {
		final String type = "ready";
		final long checkId;

		ReadyFrame(long checkId) {
			this.checkId = checkId;
		}
	}

	private static final class SpecDrainFrame {
		final String type = "specDrain";
		final int npcIndex;
		final String weapon;
		final int hit;
		final int world;

		SpecDrainFrame(int npcIndex, String weapon, int hit, int world) {
			this.npcIndex = npcIndex;
			this.weapon = weapon;
			this.hit = hit;
			this.world = world;
		}
	}

	private static final class FcRequestFrame {
		final String type = "fcRequest";
		final long target;
		final String kind;
		final String friendsChat;

		FcRequestFrame(long target, String kind, String friendsChat) {
			this.target = target;
			this.kind = kind;
			this.friendsChat = friendsChat;
		}
	}

	private static final class TransferHostFrame {
		final String type = "transferHost";
		final String kind;
		final long target;
		final String newHostKey;
		final String newHostName;
		final boolean hostStays;

		TransferHostFrame(String kind, long target, String newHostKey, String newHostName, boolean hostStays) {
			this.kind = kind;
			this.target = target;
			this.newHostKey = newHostKey;
			this.newHostName = newHostName;
			this.hostStays = hostStays;
		}
	}
}
