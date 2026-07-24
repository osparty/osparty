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

	private volatile Mode mode = Mode.NONE;
	private volatile String roomKey;
	private volatile String currentActivityId;
	private volatile int capacity;
	private volatile boolean locked;

	// Server-authoritative roster (last roster frame) + room meta.
	private volatile List<PartyV2Socket.RosterEntry> rosterEntries = List.of();
	private volatile String hostName;
	private volatile String discordUrl;

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

	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private volatile Runnable onEnded;
	private final List<TilePing> pings = new CopyOnWriteArrayList<>();

	@Inject
	private LivePartyV2(Client client, ConfigManager configManager, OSPartyConfig config,
		PartyV2Socket socket, Gson gson) {
		this.client = client;
		this.configManager = configManager;
		this.config = config;
		this.socket = socket;
		this.gson = gson;
	}

	// ---- lifecycle ----------------------------------------------------------

	@Override
	public void register() {
		socket.setListener(this::onFrame);
		socket.setOnOpen(this::onOpen);
		socket.start();
	}

	@Override
	public void unregister() {
		socket.stop();
		reset();
	}

	/** On every (re)connect: re-announce identity, re-assert host/join, and re-send our state next tick. */
	private void onOpen() {
		socket.send(new HelloFrame(localAccountHash, localName));
		if (mode == Mode.HOST) {
			sendHost();
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
		sendJoin();
		fire();
	}

	@Override
	public void leave() {
		if (mode != Mode.NONE) {
			socket.send(new LeaveFrame());
		}
		reset();
		fire();
	}

	@Override
	public void leaveForSwitch() {
		// The subsequent join re-keys our session server-side; no explicit leave needed.
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

	private void applyPing(PartyV2Socket.Frame frame) {
		if (frame.x == null || frame.y == null || frame.memberId == null || frame.memberId == localMemberId) {
			return;
		}
		WorldPoint point = new WorldPoint(frame.x, frame.y, frame.plane == null ? 0 : frame.plane);
		Color color = new Color(frame.color == null ? Color.CYAN.getRGB() : frame.color, true);
		addPing(new TilePing(point, frame.name, color, System.currentTimeMillis()));
		fire();
	}

	private void end() {
		reset();
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
			out.add(new Member(host.name, accountHashFor(host)));
		}
		others.sort(Comparator.comparingLong(e -> e.memberId));
		for (PartyV2Socket.RosterEntry entry : others) {
			out.add(new Member(entry.name, accountHashFor(entry)));
		}
		return out;
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
		socket.send(new JoinFrame(roomKey, currentActivityId, localRole, localLearner, localInvited,
			localName, localAccountHash));
	}

	private void fire() {
		for (Runnable listener : listeners) {
			listener.run();
		}
	}

	// ---- P3 (not yet implemented) -------------------------------------------
	// Ready checks, host transfer, spec drains and friends-chat prompts arrive in P3. The RuneLite inbound
	// @Subscribe handlers are no-ops in V2 (those events never fire; state comes over the socket).

	@Override
	public void generatePassphrase(Consumer<String> onGenerated) {
		String token = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		SwingUtilities.invokeLater(() -> onGenerated.accept(token));
	}

	@Override
	public void rememberResumedRoster(List<Member> members) {
		// P3: silent re-admission of members present before a host restart.
	}

	@Override
	public void setOnReadyCheckStarted(Consumer<String> onReadyCheckStarted) {
		// P3
	}

	@Override
	public void setOnAllReady(Runnable onAllReady) {
		// P3
	}

	@Override
	public void setOnReadyExpired(Runnable onReadyExpired) {
		// P3
	}

	@Override
	public void startReadyCheck() {
		// P3
	}

	@Override
	public void markReady() {
		// P3
	}

	@Override
	public void onReadyCheck(net.osparty.party.ReadyCheckMessage message) {
		// P3
	}

	@Override
	public LiveParty.ReadyCheckStatus readyCheck() {
		return null; // P3
	}

	@Override
	public void requestFriendsChat(long targetMemberId, String friendsChat) {
		// P3
	}

	@Override
	public void sendJoinPrompt(long targetMemberId, String kind, String friendsChat) {
		// P3
	}

	@Override
	public void promoteToHost(String hostName) {
		// P3
	}

	@Override
	public void demoteToMember() {
		// P3
	}

	@Override
	public void offerHostTransfer(long targetMemberId, String newHostKey, String newHostName, boolean hostStays) {
		// P3
	}

	@Override
	public void acceptHostTransfer(long oldHostMemberId) {
		// P3
	}

	@Override
	public void commitHostTransfer(long targetMemberId, String newHostKey, boolean hostStays) {
		// P3
	}

	@Override
	public void abortHostTransfer(long targetMemberId) {
		// P3
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
}
