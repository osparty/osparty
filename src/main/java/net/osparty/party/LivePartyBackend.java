package net.osparty.party;

import java.awt.Color;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.osparty.model.Member;
import net.runelite.api.coords.WorldPoint;

/**
 * The plugin's view of the live party, independent of transport. The UI, overlays and trackers talk to
 * this seam so the underlying live-party implementation can be swapped without touching them.
 *
 * <p>Two implementations exist in parallel during the V2 migration (see PARTY_V2_MIGRATION.md):
 * <ul>
 *   <li>{@link RuneLiteLivePartyBackend} — wraps the existing {@link LiveParty} (RuneLite's built-in P2P
 *       party relay). The default; unchanged behaviour.</li>
 *   <li>{@code LivePartyV2} — OSParty's own node-affine, server-authoritative live party (built in P1+).</li>
 * </ul>
 *
 * <p>The value types this exposes ({@link LiveParty.RosterMember}, {@link LiveParty.Status},
 * {@link LiveParty.Marker}, {@link LiveParty.ReadyCheckStatus}) deliberately still live on {@link LiveParty}
 * so no call site had to move them; they can be lifted to a neutral home when the RuneLite backend is
 * removed at P6.
 */
public interface LivePartyBackend
{
	// ---- lifecycle / listeners ----------------------------------------------
	void register();

	void unregister();

	void addListener(Runnable listener);

	void setOnEnded(Runnable onEnded);

	void setOnReadyCheckStarted(Consumer<String> onReadyCheckStarted);

	void setOnAllReady(Runnable onAllReady);

	void setOnReadyExpired(Runnable onReadyExpired);

	// ---- connection lifecycle -----------------------------------------------
	void generatePassphrase(Consumer<String> onGenerated);

	void hostParty(String passphrase, String hostName, String activityId, int capacity,
		boolean locked, String role, boolean learner, boolean teacher);

	void joinParty(String passphrase, String activityId, int teamSize, String role, boolean learner);

	void joinParty(String passphrase, String activityId, int teamSize, String role, boolean learner,
		boolean invited);

	void leave();

	void leaveForSwitch();

	void rememberResumedRoster(List<Member> members);

	boolean isConnected();

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
	 * Our inventory or worn gear changed. Separate from {@link #markLocalDirty} because a backend that sends
	 * only what changed needs to know <em>what</em> changed — resending 500 bytes of item ids because run
	 * energy ticked is most of what makes the live stream expensive.
	 *
	 * <p>The RuneLite-relay backend ignores the distinction and marks everything dirty, as it always has.
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
	 * being stuck with the copy they took when they applied. No-op on backends with no channel for it.
	 */
	default void setPartyMeta(net.osparty.model.PartyMeta meta)
	{
	}

	/** Member: the host's last published ad settings, or null if none have arrived. */
	default net.osparty.model.PartyMeta partyMeta()
	{
		return null;
	}

	boolean canAdmitMore();

	boolean admit(long memberId, String name);

	void kick(long memberId);

	void reject(long memberId);

	void requestFriendsChat(long targetMemberId, String friendsChat);

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

	void onReadyCheck(ReadyCheckMessage message);

	LiveParty.ReadyCheckStatus readyCheck();

	// ---- map pings ----------------------------------------------------------
	void sendPing(WorldPoint point, Color color);

	void onPing(PingMessage message);

	List<TilePing> activePings();

	// ---- markers ------------------------------------------------------------
	Map<String, LiveParty.Marker> learnerMarkers();

	// ---- per-tick -----------------------------------------------------------
	void tick();

	// ---- inbound message handlers -------------------------------------------
	void onPlayerUpdate(PlayerUpdate update);

	void onPartyState(PartyStateMessage state);

	void onMemberCommand(MemberCommand command);

	void onPeerJoined(long memberId);

	void onPeerLeft(long memberId);

	// ---- queries ------------------------------------------------------------
	boolean isForLocalMember(long memberId);

	boolean isLocalAdmitted();

	boolean isPendingApplicant(long memberId);

	long accountHashForMember(long memberId);

	List<Member> rosterMembers();

	List<Member> currentMembers();

	List<LiveParty.RosterMember> roster();

	int hostWorld();

	boolean onDifferentWorldThanHost();

	List<String> neededRoles(List<String> requiredRoles);
}
