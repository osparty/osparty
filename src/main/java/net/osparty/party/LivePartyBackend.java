package net.osparty.party;

import java.awt.Color;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.osparty.model.Member;
import net.runelite.api.coords.WorldPoint;

/**
 * The plugin's view of the live party, independent of transport. The UI, overlays and trackers talk to
 * this seam rather than to the implementation behind it.
 *
 * <p>One implementation: {@link LiveParty}, OSParty's own node-affine, server-authoritative live party. The
 * seam remains because everything above it is written against an interface and mocks one in tests, not
 * because there is still a choice to make.
 */
public interface LivePartyBackend
{
	// ---- lifecycle / listeners ----------------------------------------------
	void register();

	void unregister();

	void addListener(Runnable listener);

	void setOnEnded(Runnable onEnded);

	/**
	 * The host removed us from the party. Fired in addition to {@link #setOnEnded}, which cannot tell a kick
	 * from a disband — and only a kick is worth a sound.
	 */
	void setOnKicked(Runnable onKicked);

	void setOnReadyCheckStarted(Consumer<String> onReadyCheckStarted);

	/**
	 * Another member dropped a map ping. Only their pings fire this — our own is already
	 * handled where it is sent, so the sound isn't doubled up.
	 */
	void setOnPingReceived(Consumer<WorldPoint> onPingReceived);

	void setOnAllReady(Runnable onAllReady);

	void setOnReadyExpired(Runnable onReadyExpired);

	// ---- connection lifecycle -----------------------------------------------
	void generatePassphrase(Consumer<String> onGenerated);

	void hostParty(String passphrase, String hostName, String activityId, int capacity,
		boolean locked, String role, boolean learner, boolean teacher);

	/**
	 * Where the party we are about to join keeps its live room, if the advertisement said.
	 *
	 * <p>Called before {@link #joinParty}, so the connection can move to that pod first rather than landing
	 * anywhere and being redirected off it. An advertisement that does not name a node leaves this unsaid.
	 */
	void hintLiveNode(String node);

	void joinParty(String passphrase, String activityId, int teamSize, String role, boolean learner);

	void joinParty(String passphrase, String activityId, int teamSize, String role, boolean learner,
		boolean invited);

	/**
	 * Attend the ambient room for a group detected in the game — a friends chat at an activity — rather than
	 * one advertised on the board. There is no host and no application: everyone arrives unseated, and the
	 * server seats an attendee once another attendee reports standing next to it and it reports the same back.
	 *
	 * @param room the room key both ends derive from the group; see {@code AmbientGroups}
	 * @param name our own player name, which is what the rest of the group has to name us by — passed in
	 *     rather than read from the live state so the very first frame carries it
	 * @param seen the players from that group our own scene can currently see
	 */
	void attendGroup(String room, String activityId, int capacity, String name, List<String> seen);

	/** Report a change in who we can see, which is what an ambient room seats people on. */
	void reportSighted(List<String> seen);

	/** Whether the party we are in is an ambient one, which nobody hosts and nothing advertises. */
	boolean isAmbient();

	void leave();

	void leaveForSwitch();

	/** Whether we are in a party at all, as host or member. Says nothing about the socket. */
	boolean isInParty();

	boolean isHosting();

	String passphrase();

	// ---- local self-report --------------------------------------------------
	void setLocalRole(String role);

	String getLocalRole();

	void setLocalLearner(boolean learner);

	boolean isLocalLearner();

	void setLocalTeacher(boolean teacher);

	boolean isLocalTeacher();

	void markLocalDirty();

	/**
	 * Our inventory or worn gear changed. Separate from {@link #markLocalDirty} because only what changed is
	 * sent — resending 500 bytes of item ids because run energy ticked is most of what made the live stream
	 * expensive.
	 */
	void markItemsDirty();

	/**
	 * A skill's <em>real</em> level was reported. Fired for boosts too, so implementations must compare
	 * against what they last sent rather than trusting the event: a levelled skill is rare, a boosted one is
	 * constant, and only the former belongs in a live update.
	 */
	void markStatsDirty(net.runelite.api.Skill skill, int realLevel);

	void broadcastOffline(String name);

	// ---- host state / actions -----------------------------------------------
	void setCapacity(int capacity);

	void setDiscordInviteUrl(String url);

	String discordInviteUrl();

	void setLocked(boolean locked);

	boolean isLocked();

	/**
	 * Host: publish the advertised party's settings to the room, so members track edits to the ad instead of
	 * being stuck with the copy they took when they applied.
	 */
	void setPartyMeta(net.osparty.model.PartyMeta meta);

	/** Member: the host's last published ad settings, or null if none have arrived. */
	net.osparty.model.PartyMeta partyMeta();

	boolean canAdmitMore();

	boolean admit(long memberId, String name);

	void kick(long memberId);

	void reject(long memberId);

	void sendJoinPrompt(long targetMemberId, String kind, String friendsChat);

	/**
	 * Broadcast a defence-draining special attack we just landed, so every member's defence tracker
	 * reflects the whole party's draining. {@code weapon} is a {@link net.osparty.enums.SpecWeapon} name.
	 */
	void sendSpecDrain(int npcIndex, String weapon, int hit, int world);

	// ---- host transfer ------------------------------------------------------
	void promoteToHost(String hostName);

	void demoteToMember();

	void offerHostTransfer(long targetMemberId, String newHostKey, String newHostName, boolean hostStays);

	void acceptHostTransfer(long oldHostMemberId);

	void commitHostTransfer(long targetMemberId, String newHostKey, boolean hostStays);

	void abortHostTransfer(long targetMemberId);

	// ---- ready check --------------------------------------------------------
	String currentActivityId();

	void startReadyCheck();

	void markReady();

	ReadyCheckStatus readyCheck();

	// ---- map pings ----------------------------------------------------------
	boolean sendPing(WorldPoint point, Color color);

	List<TilePing> activePings();

	// ---- markers ------------------------------------------------------------
	Map<String, PartyMarker> learnerMarkers();

	// ---- per-tick -----------------------------------------------------------
	void tick();

	// ---- queries ------------------------------------------------------------
	boolean isForLocalMember(long memberId);

	boolean isLocalAdmitted();

	boolean isPendingApplicant(long memberId);

	long accountHashForMember(long memberId);

	/**
	 * The public, non-reversible id the server derived for this member's account -- stable across a rename,
	 * and safe to persist or show, unlike {@link #accountHashForMember}. Null when the roster has no entry
	 * for the member (not yet seated) or the entry predates this field.
	 */
	String playerIdForMember(long memberId);

	List<Member> rosterMembers();

	List<Member> currentMembers();

	List<RosterMember> roster();

	int hostWorld();

	boolean onDifferentWorldThanHost();

	/** Whether we are inside one of the three raids, where ready checks are neither sent nor received. */
	boolean insideRaid();

	List<String> neededRoles(List<String> requiredRoles);
}
