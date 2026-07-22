package net.osparty.party;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.osparty.tools.PersonalBests;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;

/**
 * The plugin's view of the live RuneLite P2P party, plus a host-authoritative roster layer. The
 * creator hosts and broadcasts the admitted roster via {@link PartyStateMessage}; enforcement is
 * cooperative. Messages arrive off-EDT, so shared state is concurrent and listeners marshal to the EDT.
 */
@Slf4j
@Singleton
public class LiveParty
{
	public enum Status
	{
		HOST, MEMBER, PENDING
	}

	@Value
	public static class RosterMember
	{
		long memberId;
		String name;
		Status status;
		PlayerUpdate data; // nullable until they sync
		boolean local;
		boolean online; // recently heard from (or ourselves)
	}

	@Value
	public static class ReadyCheckStatus
	{
		String starter;
		int ready;
		int total;
		int secondsLeft;
		boolean localReady;
	}

	private static final long READY_CHECK_TIMEOUT_MS = 30_000;

	private static final long ONLINE_TIMEOUT_MS = 20_000;
	/** Host drops a member after this long with no contact. */
	private static final long PRUNE_TIMEOUT_MS = 60_000;

	private final PartyService partyService;
	private final WSClient wsClient;
	private final Client client;
	private final ClientThread clientThread;
	private final ConfigManager configManager;
	private final net.osparty.OSPartyConfig config;

	private volatile String currentActivityId;
	private volatile int currentTeamSize;

	private volatile String localRole;
	private volatile boolean localLearner;
	/** True while we're an invited joiner awaiting the host's (automatic) admission. */
	private volatile boolean localInvited;
	private volatile boolean localTeacher;

	private final Map<Long, PlayerUpdate> playerData = new ConcurrentHashMap<>();
	/** memberId -> epoch millis last heard from (presence + pruning). */
	private final Map<Long, Long> lastSeen = new ConcurrentHashMap<>();
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

	// Host-authoritative state (only meaningful while hosting).
	private volatile boolean hosting;
	private volatile String hostName;
	private volatile int capacity;
	private volatile boolean locked;
	/** Discord voice-channel invite URL for this party once the host provisions one; null otherwise. */
	private volatile String discordInviteUrl;
	/** Excludes the host. memberId -> display name. */
	private final Map<Long, String> admitted = new ConcurrentHashMap<>();

	/** Kicked/declined members still briefly in the relay room; hidden so they don't flash back as pending. */
	private final Set<Long> leaving = ConcurrentHashMap.newKeySet();

	/**
	 * Identities (accountHash / normalised name) of members admitted before a host restart. When we
	 * reclaim our own party, members still in the room are re-admitted silently instead of re-appearing
	 * as applicants the host has to approve again.
	 */
	private final Set<Long> resumedHashes = ConcurrentHashMap.newKeySet();
	private final Set<String> resumedNames = ConcurrentHashMap.newKeySet();

	private volatile PartyStateMessage lastState;

	private volatile boolean stateDirty;
	private volatile boolean localDirty;

	/** Ticks since our last self-broadcast; we re-broadcast on a slow cadence since the relay has no replay. */
	private int ticksSinceLocalBroadcast;
	private static final int LOCAL_REBROADCAST_TICKS = 10; // ~6s at 0.6s/tick

	/** Invoked off-EDT when our membership ends. */
	private volatile Runnable onEnded;

	// ---- ready check (one active per party) ----------------------------------
	private volatile long readyCheckId;
	private volatile long readyCheckStartedAt;
	private volatile String readyCheckStarter;
	private long readyCheckSeq;
	private final Set<Long> readyMembers = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean readyAllNotified = new AtomicBoolean();
	private volatile Consumer<String> onReadyCheckStarted;
	private volatile Runnable onAllReady;
	private volatile Runnable onReadyExpired;

	// ---- map pings -----------------------------------------------------------
	private final List<TilePing> pings = new CopyOnWriteArrayList<>();

	@Inject
	private LiveParty(PartyService partyService, WSClient wsClient, Client client, ClientThread clientThread,
		ConfigManager configManager, net.osparty.OSPartyConfig config)
	{
		this.partyService = partyService;
		this.wsClient = wsClient;
		this.client = client;
		this.clientThread = clientThread;
		this.configManager = configManager;
		this.config = config;
	}

	public void register()
	{
		wsClient.registerMessage(PlayerUpdate.class);
		wsClient.registerMessage(PartyStateMessage.class);
		wsClient.registerMessage(MemberCommand.class);
		wsClient.registerMessage(FcRequestMessage.class);
		wsClient.registerMessage(ReadyCheckMessage.class);
		wsClient.registerMessage(PingMessage.class);
		wsClient.registerMessage(HostTransferMessage.class);
		wsClient.registerMessage(SpecDrainMessage.class);
	}

	public void unregister()
	{
		wsClient.unregisterMessage(PlayerUpdate.class);
		wsClient.unregisterMessage(PartyStateMessage.class);
		wsClient.unregisterMessage(MemberCommand.class);
		wsClient.unregisterMessage(FcRequestMessage.class);
		wsClient.unregisterMessage(ReadyCheckMessage.class);
		wsClient.unregisterMessage(PingMessage.class);
		wsClient.unregisterMessage(HostTransferMessage.class);
		wsClient.unregisterMessage(SpecDrainMessage.class);
		reset();
	}

	public void setOnReadyCheckStarted(Consumer<String> onReadyCheckStarted)
	{
		this.onReadyCheckStarted = onReadyCheckStarted;
	}

	public void setOnAllReady(Runnable onAllReady)
	{
		this.onAllReady = onAllReady;
	}

	public void setOnReadyExpired(Runnable onReadyExpired)
	{
		this.onReadyExpired = onReadyExpired;
	}

	public void addListener(Runnable listener)
	{
		listeners.add(listener);
	}

	public void setOnEnded(Runnable onEnded)
	{
		this.onEnded = onEnded;
	}

	// ---- connection lifecycle ------------------------------------------------

	/** Generate a fresh passphrase (RuneLite requires the client thread) and deliver it on the EDT. */
	public void generatePassphrase(Consumer<String> onGenerated)
	{
		clientThread.invoke(() -> {
			String passphrase = partyService.generatePassphrase();
			SwingUtilities.invokeLater(() -> onGenerated.accept(passphrase));
		});
	}

	public void hostParty(String passphrase, String hostName, String activityId, int capacity,
		boolean locked, String role, boolean learner, boolean teacher)
	{
		reset();
		hosting = true;
		this.hostName = hostName;
		this.capacity = capacity;
		this.locked = locked;
		this.currentActivityId = activityId;
		this.currentTeamSize = capacity;
		this.localRole = role;
		this.localLearner = learner;
		this.localTeacher = teacher;
		stateDirty = true;
		localDirty = true;
		partyService.changeParty(passphrase);
		fire();
	}

	/** Join an advertised room as an applicant (pending until the host admits). */
	public void joinParty(String passphrase, String activityId, int teamSize, String role, boolean learner)
	{
		joinParty(passphrase, activityId, teamSize, role, learner, false);
	}

	/**
	 * Join an advertised room. When {@code invited} is true we broadcast that we were invited, so the host
	 * auto-admits us instead of prompting for approval.
	 */
	public void joinParty(String passphrase, String activityId, int teamSize, String role, boolean learner,
		boolean invited)
	{
		reset();
		this.currentActivityId = activityId;
		this.currentTeamSize = teamSize;
		this.localRole = role;
		this.localLearner = learner;
		this.localInvited = invited;
		localDirty = true;
		partyService.changeParty(passphrase);
		fire();
	}

	/** Change the role we're filling and re-broadcast. No-op when unchanged. */
	public void setLocalRole(String role)
	{
		if (java.util.Objects.equals(role, localRole))
		{
			return;
		}
		localRole = role;
		localDirty = true;
		fire();
	}

	public String getLocalRole()
	{
		return localRole;
	}

	/** Mark/unmark ourselves as a learner and re-broadcast so the host sees it. No-op when unchanged. */
	public void setLocalLearner(boolean learner)
	{
		if (learner == localLearner)
		{
			return;
		}
		localLearner = learner;
		localDirty = true;
		fire();
	}

	public boolean isLocalLearner()
	{
		return localLearner;
	}

	/** Mark/unmark ourselves as a teacher and re-broadcast so the host sees it. No-op when unchanged. */
	public void setLocalTeacher(boolean teacher)
	{
		if (teacher == localTeacher)
		{
			return;
		}
		localTeacher = teacher;
		localDirty = true;
		fire();
	}

	public boolean isLocalTeacher()
	{
		return localTeacher;
	}

	/** Host edit: change the party's capacity. No-op when unchanged or not hosting. */
	public void setCapacity(int capacity)
	{
		if (!hosting || capacity == this.capacity)
		{
			return;
		}
		this.capacity = capacity;
		this.currentTeamSize = capacity;
		stateDirty = true;
		fire();
	}

	/** Host: record the Discord voice-channel invite URL and re-broadcast. No-op when not hosting or unchanged. */
	public void setDiscordInviteUrl(String url)
	{
		if (!hosting || java.util.Objects.equals(url, discordInviteUrl))
		{
			return;
		}
		discordInviteUrl = url;
		stateDirty = true;
		fire();
	}

	/** The party's Discord voice-channel invite URL, or null. Local state if host, else the host's last state. */
	public String discordInviteUrl()
	{
		if (hosting)
		{
			return discordInviteUrl;
		}
		PartyStateMessage state = lastState;
		return state != null ? state.getDiscordInviteUrl() : null;
	}

	/** Overhead marker for an in-game player: teacher, learner, or none. */
	public enum Marker
	{
		NONE, LEARNER, TEACHER
	}

	/** Learner/teacher markers for admitted members, keyed by normalised name for scene matching. */
	public Map<String, Marker> learnerMarkers()
	{
		Map<String, Marker> markers = new HashMap<>();
		for (RosterMember member : roster())
		{
			if (member.getStatus() == Status.PENDING || member.getName() == null)
			{
				continue;
			}
			boolean teacher;
			boolean learner;
			if (member.isLocal())
			{
				teacher = localTeacher;
				learner = localLearner;
			}
			else
			{
				PlayerUpdate data = member.getData();
				teacher = data != null && data.isTeacher();
				learner = data != null && data.isLearner();
			}
			Marker marker = teacher ? Marker.TEACHER : learner ? Marker.LEARNER : Marker.NONE;
			if (marker != Marker.NONE)
			{
				markers.put(normalizeName(member.getName()), marker);
			}
		}
		return markers;
	}

	/** RuneLite renders spaces in names as a non-breaking space; fold them for matching. */
	public static String normalizeName(String name)
	{
		return name == null ? "" : name.replace(' ', ' ').trim().toLowerCase();
	}

	/** Leave the room. Hosts send a closing state first, best effort. */
	public void leave()
	{
		if (hosting && isLocalReady())
		{
			PartyStateMessage closing = buildState();
			closing.setClosed(true);
			partyService.send(closing);
		}
		partyService.changeParty(null);
		reset();
		fire();
	}

	/**
	 * Leave the current room to immediately join another. Unlike {@link #leave()} this skips
	 * {@code changeParty(null)}: closing and reopening in the same turn races WSClient's close
	 * handshake, nulling the fresh socket so our applicant broadcast never reaches the new host.
	 */
	public void leaveForSwitch()
	{
		if (hosting && isLocalReady())
		{
			PartyStateMessage closing = buildState();
			closing.setClosed(true);
			partyService.send(closing);
		}
		reset();
		fire();
	}

	private void reset()
	{
		hosting = false;
		hostName = null;
		capacity = 0;
		locked = false;
		discordInviteUrl = null;
		admitted.clear();
		playerData.clear();
		lastSeen.clear();
		leaving.clear();
		clearReadyCheck();
		pings.clear();
		ticksSinceLocalBroadcast = 0;
		currentActivityId = null;
		currentTeamSize = 0;
		localRole = null;
		localLearner = false;
		localTeacher = false;
		localInvited = false;
		lastState = null;
		stateDirty = false;
		localDirty = false;
		resumedHashes.clear();
		resumedNames.clear();
	}

	/**
	 * Seed the pre-restart roster (from the reclaimed ad) so members still in the room are re-admitted
	 * silently when we resume hosting, instead of re-appearing as applicants the host must re-approve.
	 * Call after {@link #hostParty} (which resets state) when reclaiming an existing party.
	 */
	public void rememberResumedRoster(List<net.osparty.model.Member> members)
	{
		resumedHashes.clear();
		resumedNames.clear();
		if (members == null)
		{
			return;
		}
		for (net.osparty.model.Member m : members)
		{
			if (m == null || m.getName() == null)
			{
				continue;
			}
			// Don't try to re-admit ourselves (the host).
			if (hostName != null && normalizeName(m.getName()).equals(normalizeName(hostName)))
			{
				continue;
			}
			if (m.getAccountHash() != 0)
			{
				resumedHashes.add(m.getAccountHash());
			}
			resumedNames.add(normalizeName(m.getName()));
		}
	}

	public boolean isConnected()
	{
		return partyService.isInParty();
	}

	public boolean isHosting()
	{
		return hosting;
	}

	public String passphrase()
	{
		return partyService.getPartyPassphrase();
	}

	// ---- host transfer -------------------------------------------------------

	/**
	 * Take over as host without leaving the room (end of a {@link HostTransferMessage} handshake).
	 * Adopts the last host state's roster/settings and marks dirty to rebroadcast as host.
	 */
	public void promoteToHost(String hostName)
	{
		PartyStateMessage state = lastState;
		hosting = true;
		this.hostName = hostName;
		long localId = localId();
		admitted.clear();
		if (state != null)
		{
			this.capacity = state.getCapacity();
			this.currentTeamSize = state.getCapacity();
			this.locked = state.isLocked();
			this.discordInviteUrl = state.getDiscordInviteUrl();
			for (RosterEntry entry : state.getRoster())
			{
				if (entry.getMemberId() != localId)
				{
					admitted.put(entry.getMemberId(), entry.getName());
				}
			}
		}
		lastState = null; // we are the authority now; ignore our own prior view
		stateDirty = true;
		localDirty = true;
		fire();
	}

	/** Old host: offer the party to {@code targetMemberId}, carrying the key it'll use to own the ad. */
	public void offerHostTransfer(long targetMemberId, String newHostKey, String newHostName, boolean hostStays)
	{
		HostTransferMessage message = new HostTransferMessage();
		message.setKind(HostTransferMessage.Kind.OFFER);
		message.setTargetMemberId(targetMemberId);
		message.setNewHostKey(newHostKey);
		message.setNewHostName(newHostName);
		message.setHostStays(hostStays);
		partyService.send(message);
	}

	/** New host: confirm to {@code oldHostMemberId} that we're here and able to take over. */
	public void acceptHostTransfer(long oldHostMemberId)
	{
		HostTransferMessage message = new HostTransferMessage();
		message.setKind(HostTransferMessage.Kind.ACCEPT);
		message.setTargetMemberId(oldHostMemberId);
		partyService.send(message);
	}

	/** Old host: the backend re-key succeeded — tell {@code targetMemberId} to take over. */
	public void commitHostTransfer(long targetMemberId, String newHostKey, boolean hostStays)
	{
		HostTransferMessage message = new HostTransferMessage();
		message.setKind(HostTransferMessage.Kind.COMMIT);
		message.setTargetMemberId(targetMemberId);
		message.setNewHostKey(newHostKey);
		message.setHostStays(hostStays);
		partyService.send(message);
	}

	/** Old host: the transfer failed — tell {@code targetMemberId} to stay a member. */
	public void abortHostTransfer(long targetMemberId)
	{
		HostTransferMessage message = new HostTransferMessage();
		message.setKind(HostTransferMessage.Kind.ABORT);
		message.setTargetMemberId(targetMemberId);
		partyService.send(message);
	}

	/** Step down from hosting while staying in the room; drops host state so we accept the new host's. */
	public void demoteToMember()
	{
		hosting = false;
		hostName = null;
		capacity = 0;
		locked = false;
		discordInviteUrl = null;
		admitted.clear();
		stateDirty = false;
		fire();
	}

	// ---- host actions --------------------------------------------------------

	/** @return true if another applicant can still be admitted (host + admitted < capacity). */
	public boolean canAdmitMore()
	{
		return capacity <= 0 || admitted.size() + 1 < capacity;
	}

	public boolean admit(long memberId, String name)
	{
		if (!hosting || !canAdmitMore())
		{
			return false;
		}
		admitted.put(memberId, name);
		stateDirty = true;
		fire();
		return true;
	}

	public void kick(long memberId)
	{
		if (!hosting)
		{
			return;
		}
		admitted.remove(memberId);
		leaving.add(memberId);
		sendCommand(MemberCommand.Action.KICK, memberId);
		stateDirty = true;
		fire();
	}

	public void reject(long memberId)
	{
		if (!hosting)
		{
			return;
		}
		leaving.add(memberId);
		sendCommand(MemberCommand.Action.REJECT, memberId);
		fire();
	}

	/** Host: ask a member to join our friends chat (a targeted in-game popup on their client). */
	public void requestFriendsChat(long targetMemberId, String friendsChat)
	{
		sendJoinPrompt(targetMemberId, "FC", friendsChat);
	}

	/** Host: send a member a join prompt. {@code kind} is "FC", "NOTICE_BOARD" (ToB) or "OBELISK" (ToA). */
	public void sendJoinPrompt(long targetMemberId, String kind, String friendsChat)
	{
		if (!hosting)
		{
			return;
		}
		FcRequestMessage request = new FcRequestMessage();
		request.setTargetMemberId(targetMemberId);
		request.setHostName(hostName);
		request.setKind(kind);
		request.setFriendsChat(friendsChat);
		partyService.send(request);
	}

	public void setLocked(boolean locked)
	{
		if (!hosting)
		{
			return;
		}
		this.locked = locked;
		stateDirty = true;
		fire();
	}

	public boolean isLocked()
	{
		return hosting ? locked : (lastState != null && lastState.isLocked());
	}

	private void sendCommand(MemberCommand.Action action, long targetMemberId)
	{
		MemberCommand command = new MemberCommand();
		command.setAction(action);
		command.setTargetMemberId(targetMemberId);
		partyService.send(command);
	}

	// ---- ready check ---------------------------------------------------------

	public String currentActivityId()
	{
		return currentActivityId;
	}

	/** Start a ready check (anyone in the party may). The starter counts as ready. */
	public void startReadyCheck()
	{
		// The starter counts as ready, so starting is world-gated exactly like readying up.
		if (!isConnected() || onDifferentWorldThanHost())
		{
			return;
		}
		long id = (localId() << 16) | (++readyCheckSeq & 0xFFFF);
		beginReadyCheck(id, localName(), localId());

		ReadyCheckMessage message = new ReadyCheckMessage();
		message.setKind(ReadyCheckMessage.Type.START);
		message.setCheckId(id);
		message.setStarter(readyCheckStarter);
		partyService.send(message);

		// Fire locally too: we don't receive our own broadcast.
		Consumer<String> cb = onReadyCheckStarted;
		if (cb != null)
		{
			cb.accept(readyCheckStarter);
		}
		fire();
	}

	public void markReady()
	{
		// Guard against a stale enabled button: never ready up from another world than the host.
		if (readyCheckId == 0 || onDifferentWorldThanHost())
		{
			return;
		}
		readyMembers.add(localId());
		ReadyCheckMessage message = new ReadyCheckMessage();
		message.setKind(ReadyCheckMessage.Type.READY);
		message.setCheckId(readyCheckId);
		partyService.send(message);
		checkAllReady();
		fire();
	}

	public void onReadyCheck(ReadyCheckMessage message)
	{
		if (message.getKind() == ReadyCheckMessage.Type.START)
		{
			beginReadyCheck(message.getCheckId(), message.getStarter(), message.getMemberId());
			Consumer<String> cb = onReadyCheckStarted;
			if (cb != null)
			{
				cb.accept(message.getStarter());
			}
		}
		else if (message.getCheckId() == readyCheckId && readyCheckId != 0)
		{
			readyMembers.add(message.getMemberId());
			checkAllReady();
		}
		fire();
	}

	private void beginReadyCheck(long id, String starter, long starterMemberId)
	{
		readyCheckId = id;
		readyCheckStartedAt = System.currentTimeMillis();
		readyCheckStarter = starter != null ? starter : "Someone";
		readyAllNotified.set(false);
		readyMembers.clear();
		readyMembers.add(starterMemberId);
	}

	private void clearReadyCheck()
	{
		readyCheckId = 0;
		readyCheckStartedAt = 0;
		readyCheckStarter = null;
		readyMembers.clear();
		readyAllNotified.set(false);
	}

	/** Fire the all-ready callback once when every (non-pending) member is ready. */
	private void checkAllReady()
	{
		if (readyCheckId == 0)
		{
			return;
		}
		Set<Long> required = activeMemberIds();
		if (required.isEmpty() || !readyMembers.containsAll(required))
		{
			return;
		}
		if (readyAllNotified.compareAndSet(false, true))
		{
			Runnable cb = onAllReady;
			if (cb != null)
			{
				cb.run();
			}
			clearReadyCheck();
		}
	}

	/** Member ids that must ready up: everyone admitted (host + members), not pending. */
	private Set<Long> activeMemberIds()
	{
		Set<Long> ids = new HashSet<>();
		for (RosterMember member : roster())
		{
			if (member.getStatus() != Status.PENDING)
			{
				ids.add(member.getMemberId());
			}
		}
		return ids;
	}

	/** @return the active ready check for the UI, or null when none is running. */
	public ReadyCheckStatus readyCheck()
	{
		long id = readyCheckId;
		if (id == 0)
		{
			return null;
		}
		Set<Long> required = activeMemberIds();
		int ready = 0;
		for (long memberId : required)
		{
			if (readyMembers.contains(memberId))
			{
				ready++;
			}
		}
		// Round up: the check reads "30s" for its whole first second and only hits 0 at expiry.
		long leftMs = Math.max(0, READY_CHECK_TIMEOUT_MS - (System.currentTimeMillis() - readyCheckStartedAt));
		long left = (leftMs + 999) / 1000;
		return new ReadyCheckStatus(readyCheckStarter, ready, required.size(), (int) left,
			readyMembers.contains(localId()));
	}

	// ---- map pings -----------------------------------------------------------

	/** Broadcast a tile ping in {@code color}, labelled with our name; also shown locally. */
	public void sendPing(WorldPoint point, Color color)
	{
		if (!isConnected() || point == null)
		{
			return;
		}
		long id = localId();
		if (id == 0)
		{
			return;
		}
		String name = localName();

		PingMessage message = new PingMessage();
		message.setWorldX(point.getX());
		message.setWorldY(point.getY());
		message.setPlane(point.getPlane());
		message.setName(name);
		message.setColor(color.getRGB());
		partyService.send(message);

		addPing(new TilePing(point, name, color, System.currentTimeMillis()));
	}

	/** Handle an inbound ping from a peer (ignores our own echo, if any). */
	public void onPing(PingMessage message)
	{
		if (message.getMemberId() == localId() && localId() != 0)
		{
			return;
		}
		WorldPoint point = new WorldPoint(message.getWorldX(), message.getWorldY(), message.getPlane());
		Color color = new Color(message.getColor(), true);
		addPing(new TilePing(point, message.getName(), color, System.currentTimeMillis()));
	}

	private void addPing(TilePing ping)
	{
		expirePings();
		pings.add(ping);
	}

	public List<TilePing> activePings()
	{
		expirePings();
		return pings;
	}

	private void expirePings()
	{
		long now = System.currentTimeMillis();
		// Same window TilePingOverlay animates over, so a ping is never culled mid-fade or left lingering.
		long duration = Math.max(1, config.pingAnimMs());
		pings.removeIf(p -> now - p.getCreatedAt() > duration);
	}

	private String localName()
	{
		PlayerUpdate self = playerData.get(localId());
		if (self != null && self.getName() != null)
		{
			return self.getName();
		}
		return hosting && hostName != null ? hostName : "Someone";
	}

	// ---- per-tick flushing (must be called from the client thread) -----------

	/** Push pending host state and our own live snapshot. Client thread only (reads item containers). */
	public void tick()
	{
		if (!isConnected())
		{
			return;
		}
		pruneStaleMembers();
		expireReadyCheck();
		flushState();
		// Vitals (run energy, spec) fire no StatChanged event; detect changes here and re-broadcast.
		if (!localDirty && vitalsChanged())
		{
			localDirty = true;
		}
		// Periodically re-announce ourselves so late-joining peers converge (the relay has no replay).
		if (++ticksSinceLocalBroadcast >= LOCAL_REBROADCAST_TICKS)
		{
			localDirty = true;
		}
		if (localDirty)
		{
			PlayerUpdate update = LocalPlayerSync.snapshot(client);
			if (update != null)
			{
				// Privacy settings: strip inventory/gear before they ever leave this client,
				// so peers simply receive an update without that data.
				if (config.hideInventory())
				{
					update.setInventory(null);
					update.setInventoryQuantities(null);
					update.setRunePouch(null);
					update.setRunePouchAmounts(null);
					update.setRunePouchNames(null);
				}
				if (config.hideGear())
				{
					update.setEquipment(null);
				}
				// Our personal best for this party's activity+size (read locally).
				update.setPbSeconds(PersonalBests.read(configManager, currentActivityId, currentTeamSize));
				// The role we've chosen to fill (a user choice, not read from the client).
				update.setRole(localRole);
				update.setLearner(localLearner);
				update.setTeacher(localTeacher);
				update.setInvited(localInvited);
				broadcastLocal(update);
			}
		}
	}

	/** Host: drop members we haven't heard from in a while (freeing the slot); a later update un-hides them. */
	private void pruneStaleMembers()
	{
		if (!hosting)
		{
			return;
		}
		long now = System.currentTimeMillis();
		long localId = localId();
		boolean changed = false;
		for (PartyMember member : partyService.getMembers())
		{
			long id = member.getMemberId();
			if (id == localId || leaving.contains(id))
			{
				continue;
			}
			Long seen = lastSeen.get(id);
			if (seen == null)
			{
				// First sighting without a presence stamp yet - start the clock.
				lastSeen.put(id, now);
				continue;
			}
			if (now - seen > PRUNE_TIMEOUT_MS)
			{
				leaving.add(id);
				playerData.remove(id);
				if (admitted.remove(id) != null)
				{
					changed = true;
				}
			}
		}
		if (changed)
		{
			stateDirty = true;
			fire();
		}
	}

	private void expireReadyCheck()
	{
		if (readyCheckId == 0)
		{
			return;
		}
		if (System.currentTimeMillis() - readyCheckStartedAt > READY_CHECK_TIMEOUT_MS)
		{
			clearReadyCheck();
			Runnable cb = onReadyExpired;
			if (cb != null)
			{
				cb.run();
			}
			fire();
		}
	}

	/** Host: rebroadcast the authoritative state if it changed and we're connected. */
	private void flushState()
	{
		if (hosting && stateDirty && isLocalReady())
		{
			partyService.send(buildState());
			stateDirty = false;
		}
	}

	/** Broadcast our own snapshot; clears the dirty flag only once actually sent. */
	private void broadcastLocal(PlayerUpdate update)
	{
		PartyMember local = partyService.getLocalMember();
		if (local == null)
		{
			return; // not connected yet — retry next tick
		}
		update.setMemberId(local.getMemberId());
		playerData.put(local.getMemberId(), update);
		partyService.send(update);
		localDirty = false;
		ticksSinceLocalBroadcast = 0;
		fire();
	}

	public void markLocalDirty()
	{
		localDirty = true;
	}

	/** @return whether our live vitals differ from the last broadcast. Client thread only. */
	private boolean vitalsChanged()
	{
		PlayerUpdate self = playerData.get(localId());
		if (self == null)
		{
			return false; // nothing sent yet; the normal dirty path handles the first broadcast
		}
		return self.getCurrentHp() != client.getBoostedSkillLevel(Skill.HITPOINTS)
			|| self.getCurrentPrayer() != client.getBoostedSkillLevel(Skill.PRAYER)
			|| self.getSpecialPercent() != client.getVarpValue(300) / 10
			|| self.getRunEnergy() != client.getEnergy() / 100;
	}

	/** Tell peers we've gone offline via a world-0 update so our dot clears immediately. */
	public void broadcastOffline(String name)
	{
		PartyMember local = partyService.getLocalMember();
		if (local == null)
		{
			return;
		}
		PlayerUpdate update = new PlayerUpdate();
		update.setName(name);
		update.setWorld(0);
		update.setMemberId(local.getMemberId());
		playerData.put(local.getMemberId(), update);
		partyService.send(update);
		localDirty = true;
		fire();
	}

	private PartyStateMessage buildState()
	{
		PartyStateMessage state = new PartyStateMessage();
		long localId = localId();
		state.setHostMemberId(localId);
		state.setHostName(hostName);
		state.setCapacity(capacity);
		state.setLocked(locked);
		state.setDiscordInviteUrl(discordInviteUrl);

		List<RosterEntry> roster = new ArrayList<>();
		roster.add(new RosterEntry(localId, hostName, accountHashFor(localId)));
		for (Map.Entry<Long, String> e : admitted.entrySet())
		{
			roster.add(new RosterEntry(e.getKey(), e.getValue(), accountHashFor(e.getKey())));
		}
		state.setRoster(roster);
		return state;
	}

	/** Public accessor for a member's last self-reported accountHash (0 when unknown). */
	public long accountHashForMember(long memberId)
	{
		return accountHashFor(memberId);
	}

	/** The accountHash last self-reported by {@code memberId}, or {@code 0} when unknown. */
	private long accountHashFor(long memberId)
	{
		PlayerUpdate update = playerData.get(memberId);
		return update != null ? update.getAccountHash() : 0L;
	}

	/** The live roster to advertise: host first, then admitted members sorted by id. Hosting only. */
	public List<net.osparty.model.Member> rosterMembers()
	{
		// Our PlayerUpdate may not have round-tripped yet; fall back to the client's own hash so the
		// ad never advertises the host hash-less (which would drop block/favourite/badge matching).
		long hostHash = accountHashFor(localId());
		if (hostHash == 0)
		{
			long own = client.getAccountHash();
			if (own != -1)
			{
				hostHash = own;
			}
		}
		List<net.osparty.model.Member> out = new ArrayList<>();
		out.add(new net.osparty.model.Member(hostName, hostHash));
		admitted.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(e -> out.add(new net.osparty.model.Member(e.getValue(), accountHashFor(e.getKey()))));
		return out;
	}

	/** RuneLite's placeholder display name for a party member that hasn't synced its identity yet. */
	private static final String UNKNOWN_MEMBER_NAME = "<unknown>";

	/**
	 * The confirmed roster (host + admitted, no pending) as name+accountHash pairs, host first. Works
	 * whether we host or joined. Members that haven't synced ("<unknown>") are skipped to avoid logging
	 * a phantom join+leave; they're recorded a tick later once their name resolves.
	 */
	public List<net.osparty.model.Member> currentMembers()
	{
		List<net.osparty.model.Member> out = new ArrayList<>();
		for (RosterMember m : roster())
		{
			if (m.getStatus() == Status.PENDING || m.getData() == null || isUnresolvedName(m.getName()))
			{
				continue;
			}
			out.add(new net.osparty.model.Member(m.getName(), m.getData().getAccountHash()));
		}
		return out;
	}

	/** True for a member we can't yet identify: no name, blank, or the {@code "<unknown>"} placeholder. */
	private static boolean isUnresolvedName(String name)
	{
		return name == null || name.trim().isEmpty() || UNKNOWN_MEMBER_NAME.equalsIgnoreCase(name.trim());
	}

	// ---- inbound message handlers (called from the plugin's @Subscribe) ------

	public void onPlayerUpdate(PlayerUpdate update)
	{
		long id = update.getMemberId();
		playerData.put(id, update);
		lastSeen.put(id, System.currentTimeMillis());
		// A fresh update means they're alive - un-hide if we'd pruned them as stale.
		leaving.remove(id);
		reAdmitIfResumed(id, update);
		fire();
	}

	/** Re-admit a member who was in our party before a restart, without the host re-approving them. */
	private void reAdmitIfResumed(long memberId, PlayerUpdate update)
	{
		if (!hosting || memberId == localId() || admitted.containsKey(memberId)
			|| (resumedHashes.isEmpty() && resumedNames.isEmpty()))
		{
			return;
		}
		boolean match = (update.getAccountHash() != 0 && resumedHashes.contains(update.getAccountHash()))
			|| (update.getName() != null && resumedNames.contains(normalizeName(update.getName())));
		if (match && admit(memberId, update.getName()))
		{
			// One-shot: don't keep the identity around to re-admit a later, different joiner by the same name.
			resumedHashes.remove(update.getAccountHash());
			if (update.getName() != null)
			{
				resumedNames.remove(normalizeName(update.getName()));
			}
		}
	}

	public void onPartyState(PartyStateMessage state)
	{
		if (hosting)
		{
			return; // we are the authority; ignore others claiming to be host
		}
		if (state.isClosed())
		{
			end();
			return;
		}
		lastState = state;
		fire();
	}

	public void onMemberCommand(MemberCommand command)
	{
		if (command.getTargetMemberId() == localId() && localId() != 0)
		{
			end();
		}
	}

	public boolean isForLocalMember(long memberId)
	{
		long id = localId();
		return id != 0 && memberId == id;
	}

	/**
	 * Whether the local player is admitted (the host, or in the host's authoritative roster). A player
	 * who has applied but not yet been admitted returns {@code false}, so the UI can hide party internals
	 * from them until the host lets them in. Also {@code false} until we've received the host's state.
	 */
	public boolean isLocalAdmitted()
	{
		if (hosting)
		{
			return true;
		}
		PartyStateMessage state = lastState;
		long localId = localId();
		if (state == null || localId == 0)
		{
			return false;
		}
		if (state.getHostMemberId() == localId)
		{
			return true;
		}
		for (RosterEntry entry : state.getRoster())
		{
			if (entry.getMemberId() == localId)
			{
				return true;
			}
		}
		return false;
	}

	public boolean isPendingApplicant(long memberId)
	{
		if (!hosting)
		{
			return false;
		}
		for (RosterMember member : roster())
		{
			if (member.getMemberId() == memberId)
			{
				return member.getStatus() == Status.PENDING;
			}
		}
		return false;
	}

	public void onPeerJoined(long memberId)
	{
		// Start their presence clock and clear any stale-hide from a previous id.
		lastSeen.put(memberId, System.currentTimeMillis());
		leaving.remove(memberId);
		// Re-announce ourselves so the newcomer sees our data, and (if host) push
		// the current state so they learn who's admitted.
		localDirty = true;
		if (hosting)
		{
			stateDirty = true;
		}
		fire();
	}

	public void onPeerLeft(long memberId)
	{
		playerData.remove(memberId);
		leaving.remove(memberId);
		lastSeen.remove(memberId);
		if (hosting && admitted.remove(memberId) != null)
		{
			stateDirty = true;
		}
		fire();
	}

	/** Our membership ended externally (kicked, or host closed). */
	private void end()
	{
		partyService.changeParty(null);
		reset();
		Runnable cb = onEnded;
		if (cb != null)
		{
			cb.run();
		}
		fire();
	}

	// ---- roster view for the UI ----------------------------------------------

	public List<RosterMember> roster()
	{
		long hostId;
		Set<Long> admittedIds = new HashSet<>();
		if (hosting)
		{
			hostId = localId();
			admittedIds.addAll(admitted.keySet());
		}
		else if (lastState != null)
		{
			hostId = lastState.getHostMemberId();
			for (RosterEntry entry : lastState.getRoster())
			{
				admittedIds.add(entry.getMemberId());
			}
		}
		else
		{
			hostId = 0;
		}

		long localId = localId();
		long now = System.currentTimeMillis();
		List<RosterMember> out = new ArrayList<>();
		for (PartyMember member : partyService.getMembers())
		{
			long id = member.getMemberId();
			if (leaving.contains(id))
			{
				continue; // kicked/declined/stale — don't show until they actually leave
			}
			Status status = id == hostId ? Status.HOST
				: admittedIds.contains(id) ? Status.MEMBER : Status.PENDING;
			PlayerUpdate data = playerData.get(id);
			String name = data != null && data.getName() != null ? data.getName() : member.getDisplayName();
			// Online = us, or a peer heard from recently reporting world > 0 (logout sends world 0).
			boolean online = id == localId || (isRecent(now, id) && data != null && data.getWorld() > 0);
			out.add(new RosterMember(id, name, status, data, id == localId, online));
		}
		out.sort(Comparator.comparingInt((RosterMember m) -> m.getStatus().ordinal())
			.thenComparing(RosterMember::getName, Comparator.nullsLast(String::compareToIgnoreCase)));
		return out;
	}

	/** The world the host is currently on (live player data; our own world when hosting), or 0 when unknown. */
	public int hostWorld()
	{
		if (hosting)
		{
			PlayerUpdate mine = playerData.get(localId());
			return mine != null && mine.getWorld() > 0 ? mine.getWorld() : client.getWorld();
		}
		if (lastState != null)
		{
			PlayerUpdate data = playerData.get(lastState.getHostMemberId());
			return data != null ? data.getWorld() : 0;
		}
		return 0;
	}

	/** True when both worlds are known and the local player is not on the host's world. */
	public boolean onDifferentWorldThanHost()
	{
		int host = hostWorld();
		int mine = client.getWorld();
		return host > 0 && mine > 0 && host != mine;
	}

	private boolean isRecent(long now, long memberId)
	{
		Long seen = lastSeen.get(memberId);
		return seen != null && now - seen < ONLINE_TIMEOUT_MS;
	}

	/** Roles still open: the required multiset minus each admitted member's chosen role. */
	public List<String> neededRoles(List<String> requiredRoles)
	{
		net.osparty.model.Activity activity = net.osparty.model.Activity.fromId(currentActivityId);
		boolean flexible = activity != null && activity.hasFlexibleRoles();
		if (!flexible && (requiredRoles == null || requiredRoles.isEmpty()))
		{
			return requiredRoles;
		}

		// The roles the admitted members (host + members) have already taken.
		List<String> taken = new ArrayList<>();
		for (RosterMember member : roster())
		{
			if (member.getStatus() == Status.PENDING)
			{
				continue; // only admitted members (host + members) occupy slots
			}
			String role = member.isLocal() ? localRole
				: (member.getData() != null ? member.getData().getRole() : null);
			if (role != null)
			{
				taken.add(role);
			}
		}

		// Flexible activities (Barbarian Assault): a role stays open until its per-role cap.
		if (flexible)
		{
			return activity.flexibleNeededRoles(taken, capacity > 0 ? capacity : currentTeamSize);
		}

		// A composition may advertise flexible "Fill / Any" slots (Chambers of Xeric / CM). An
		// applicant can take one with whatever concrete role they picked, so a taken role that
		// matches no exact slot consumes an open Fill slot instead of leaking past the comp
		// (which would leave the Fill stuck in "Needs" and let the room over-admit).
		String fillId = fillRoleId(activity, requiredRoles);
		List<String> remaining = new ArrayList<>(requiredRoles);
		for (String role : taken)
		{
			if (!remaining.remove(role) && fillId != null)
			{
				remaining.remove(fillId);
			}
		}
		return remaining;
	}

	/**
	 * The "Fill / Any" wildcard role id this composition advertises (whichever mode's Fill
	 * appears in {@code requiredRoles}), or null when the composition has no Fill slot — e.g.
	 * Theatre of Blood, whose comp is exact. Lets a concrete-role pick consume a Fill slot.
	 */
	private static String fillRoleId(net.osparty.model.Activity activity, List<String> requiredRoles)
	{
		if (activity == null)
		{
			return null;
		}
		for (boolean hardMode : new boolean[]{false, true})
		{
			net.osparty.model.Role fill = activity.fillRole(hardMode);
			if (fill != null && requiredRoles.contains(fill.getId()))
			{
				return fill.getId();
			}
		}
		return null;
	}

	private long localId()
	{
		PartyMember local = partyService.getLocalMember();
		return local == null ? 0 : local.getMemberId();
	}

	private boolean isLocalReady()
	{
		return partyService.getLocalMember() != null;
	}

	private void fire()
	{
		for (Runnable listener : listeners)
		{
			listener.run();
		}
	}
}
