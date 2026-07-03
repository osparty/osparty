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
import net.osparty.PersonalBests;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;

/**
 * The plugin's view of the live RuneLite peer-to-peer party, plus a
 * host-authoritative user-management layer on top of it.
 *
 * <p>RuneLite's {@link PartyService} is a passphrase-keyed bus with no host or
 * access control. This class adds one: the creator hosts, holds the authoritative
 * admitted roster, and broadcasts it via {@link PartyStateMessage}. Applicants are
 * <em>pending</em> until the host admits them; kicks/declines are {@link MemberCommand}s
 * the target honours by leaving. Each member self-reports via {@link PlayerUpdate}.
 * Enforcement is cooperative — the same trust model as the rest of the party network.
 *
 * <p>Messages arrive on the websocket thread and UI reads on the EDT, so shared
 * state uses concurrent collections and listeners must marshal to the EDT.
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

	private volatile String currentActivityId;
	private volatile int currentTeamSize;

	private volatile String localRole;
	private volatile boolean localLearner;
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

	/**
	 * Members we've kicked/declined who are still briefly in the relay room until
	 * their client processes the command and leaves. Hidden from the roster so a
	 * kicked member doesn't flash back as a "pending applicant" (and re-popup).
	 */
	private final Set<Long> leaving = ConcurrentHashMap.newKeySet();

	private volatile PartyStateMessage lastState;

	private volatile boolean stateDirty;
	private volatile boolean localDirty;

	/**
	 * Ticks since we last broadcast our own snapshot. RuneLite's party relay does
	 * not replay history, so a peer who joins between our broadcasts would never
	 * see our gear/stats. We re-mark ourselves dirty on a slow cadence so every
	 * member's data converges even if a single on-join rebroadcast is missed.
	 */
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
	private static final long PING_DURATION_MS = 2_000;
	private final List<TilePing> pings = new CopyOnWriteArrayList<>();

	@Inject
	private LiveParty(PartyService partyService, WSClient wsClient, Client client, ClientThread clientThread,
		ConfigManager configManager)
	{
		this.partyService = partyService;
		this.wsClient = wsClient;
		this.client = client;
		this.clientThread = clientThread;
		this.configManager = configManager;
	}

	public void register()
	{
		wsClient.registerMessage(PlayerUpdate.class);
		wsClient.registerMessage(PartyStateMessage.class);
		wsClient.registerMessage(MemberCommand.class);
		wsClient.registerMessage(FcRequestMessage.class);
		wsClient.registerMessage(ReadyCheckMessage.class);
		wsClient.registerMessage(PingMessage.class);
	}

	public void unregister()
	{
		wsClient.unregisterMessage(PlayerUpdate.class);
		wsClient.unregisterMessage(PartyStateMessage.class);
		wsClient.unregisterMessage(MemberCommand.class);
		wsClient.unregisterMessage(FcRequestMessage.class);
		wsClient.unregisterMessage(ReadyCheckMessage.class);
		wsClient.unregisterMessage(PingMessage.class);
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

	/**
	 * Generate a fresh passphrase and deliver it on the EDT. RuneLite builds the
	 * passphrase from item names and asserts it runs on the client thread, so we
	 * hop there and marshal the result back for the (Swing) caller.
	 */
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
		reset();
		this.currentActivityId = activityId;
		this.currentTeamSize = teamSize;
		this.localRole = role;
		this.localLearner = learner;
		localDirty = true;
		partyService.changeParty(passphrase);
		fire();
	}

	/**
	 * Change the role we're filling and re-broadcast our self-report so the host
	 * (and everyone) sees the new choice. No-op when unchanged.
	 */
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

	/**
	 * Host edit: change the party's capacity. Updates the authoritative room size so admit
	 * limits and the broadcast state reflect the new value. No-op when unchanged or not hosting.
	 */
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

	/**
	 * Host: record the provisioned Discord voice-channel invite URL and re-broadcast the party state so
	 * every member learns it (and renders a "Join voice" button). No-op when not hosting or unchanged.
	 */
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

	/**
	 * The party's Discord voice-channel invite URL, or null if none. For the host this is the value we
	 * set locally; for a member it's the value carried on the host's last {@link PartyStateMessage}.
	 */
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

	/**
	 * Learner/teacher markers for the admitted party members (host + members, not
	 * pending applicants), keyed by normalised display name so the overlay can match
	 * them to players in the scene. Members with no marking are omitted.
	 */
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
	 * Leave the current room in preparation for immediately joining another (see
	 * {@link #joinParty}). Unlike {@link #leave()} this does NOT call
	 * {@code changeParty(null)} — a follow-up {@code changeParty(passphrase)} parts the
	 * old room and joins the new one on the same open socket. Closing here and reopening
	 * in the same turn races RuneLite's WSClient close handshake: the old socket's
	 * {@code onClosed} nulls the freshly-opened socket, so our applicant broadcast never
	 * reaches the target host (the party still disbands, but no request arrives).
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
		lastState = null;
		stateDirty = false;
		localDirty = false;
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

	/**
	 * Host: ask a specific member to join our friends chat. Delivered as a
	 * targeted message the member's client shows as a brief in-game popup.
	 */
	public void requestFriendsChat(long targetMemberId, String friendsChat)
	{
		sendJoinPrompt(targetMemberId, "FC", friendsChat);
	}

	/**
	 * Host: send a member a join prompt. {@code kind} is "FC" (join the host's friends
	 * chat — {@code friendsChat} required), "NOTICE_BOARD" (ToB) or "OBELISK" (ToA).
	 */
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
		if (!isConnected())
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

		// Fire locally too: we don't receive our own broadcast, but the starter
		// should also get the start notification/sound.
		Consumer<String> cb = onReadyCheckStarted;
		if (cb != null)
		{
			cb.accept(readyCheckStarter);
		}
		fire();
	}

	public void markReady()
	{
		if (readyCheckId == 0)
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
		long left = Math.max(0, READY_CHECK_TIMEOUT_MS - (System.currentTimeMillis() - readyCheckStartedAt)) / 1000;
		return new ReadyCheckStatus(readyCheckStarter, ready, required.size(), (int) left,
			readyMembers.contains(localId()));
	}

	// ---- map pings -----------------------------------------------------------

	/**
	 * Broadcast a tile ping to the party in {@code color}, labelled with our name.
	 * We don't receive our own party messages, so the ping is also shown locally.
	 */
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
		pings.removeIf(p -> now - p.getCreatedAt() > PING_DURATION_MS);
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

	/**
	 * Push any pending host state and our own live snapshot. Reads client item
	 * containers, so call only on the client thread (e.g. from {@code GameTick}).
	 */
	public void tick()
	{
		if (!isConnected())
		{
			return;
		}
		pruneStaleMembers();
		expireReadyCheck();
		flushState();
		// Live vitals (HP/prayer/spec/run) change often and aren't all covered by StatChanged
		// (run energy and special attack fire no stat event), so detect changes here and
		// re-broadcast so peers see a live figure.
		if (!localDirty && vitalsChanged())
		{
			localDirty = true;
		}
		// Periodically re-announce ourselves so a peer who joined after our last
		// broadcast still converges on our gear/stats (the relay has no replay).
		if (++ticksSinceLocalBroadcast >= LOCAL_REBROADCAST_TICKS)
		{
			localDirty = true;
		}
		if (localDirty)
		{
			PlayerUpdate update = LocalPlayerSync.snapshot(client);
			if (update != null)
			{
				// Our personal best for this party's activity+size (read locally).
				update.setPbSeconds(PersonalBests.read(configManager, currentActivityId, currentTeamSize));
				// The role we've chosen to fill (a user choice, not read from the client).
				update.setRole(localRole);
				update.setLearner(localLearner);
				update.setTeacher(localTeacher);
				broadcastLocal(update);
			}
		}
	}

	/**
	 * Host: drop members we haven't heard from in a while (e.g. they closed their
	 * client without the relay reporting a clean part). This frees the slot and
	 * removes the ghost so the player can rejoin fresh. We only hide them locally
	 * and via the broadcast roster; if they do come back the next update un-hides
	 * them (see {@link #onPlayerUpdate}).
	 */
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

	/**
	 * @return whether our live vitals differ from what we last broadcast, so peers should
	 * be refreshed. Reads the client, so must run on the client thread (called from {@link #tick()}).
	 */
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

	/**
	 * Tell peers we've gone offline (e.g. logged out) by broadcasting a world-0
	 * update, so our dot clears immediately rather than waiting to go stale. We'll
	 * re-send full data once we're back in-game.
	 */
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

	/**
	 * The live roster to advertise on the party ad: the host first, then admitted members
	 * (sorted by id for a stable order so the heartbeat dedups), each with their accountHash.
	 * Excludes pending applicants. Only meaningful while hosting.
	 */
	public List<net.osparty.model.Member> rosterMembers()
	{
		List<net.osparty.model.Member> out = new ArrayList<>();
		out.add(new net.osparty.model.Member(hostName, accountHashFor(localId())));
		admitted.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(e -> out.add(new net.osparty.model.Member(e.getValue(), accountHashFor(e.getKey()))));
		return out;
	}

	// ---- inbound message handlers (called from the plugin's @Subscribe) ------

	public void onPlayerUpdate(PlayerUpdate update)
	{
		long id = update.getMemberId();
		playerData.put(id, update);
		lastSeen.put(id, System.currentTimeMillis());
		// A fresh update means they're alive - un-hide if we'd pruned them as stale.
		leaving.remove(id);
		fire();
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
			// Online = us, or a peer we've heard from recently who reports being
			// logged in (world > 0). A logout broadcasts world 0; a crash/close just
			// goes stale. Either way the green dot clears.
			boolean online = id == localId || (isRecent(now, id) && data != null && data.getWorld() > 0);
			out.add(new RosterMember(id, name, status, data, id == localId, online));
		}
		out.sort(Comparator.comparingInt((RosterMember m) -> m.getStatus().ordinal())
			.thenComparing(RosterMember::getName, Comparator.nullsLast(String::compareToIgnoreCase)));
		return out;
	}

	private boolean isRecent(long now, long memberId)
	{
		Long seen = lastSeen.get(memberId);
		return seen != null && now - seen < ONLINE_TIMEOUT_MS;
	}

	/**
	 * The roles still open given the host's full {@code requiredRoles} composition:
	 * the required multiset minus the role each admitted member (host included) has
	 * chosen. Applicants only ever pick a currently-needed role, so each admitted
	 * member's role removes exactly one matching slot. Returns the input unchanged
	 * when there's no composition.
	 */
	public List<String> neededRoles(List<String> requiredRoles)
	{
		if (requiredRoles == null || requiredRoles.isEmpty())
		{
			return requiredRoles;
		}
		List<String> remaining = new ArrayList<>(requiredRoles);
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
				remaining.remove(role);
			}
		}
		return remaining;
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
