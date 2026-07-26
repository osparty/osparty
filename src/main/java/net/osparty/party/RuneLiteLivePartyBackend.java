package net.osparty.party;

import java.awt.Color;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.osparty.model.Member;
import net.runelite.api.coords.WorldPoint;

/**
 * {@link LivePartyBackend} backed by the existing {@link LiveParty} (RuneLite's built-in P2P party relay).
 * Pure delegation — it adds no behaviour, so this is byte-for-byte the current live party. It exists so the
 * UI can depend on {@link LivePartyBackend} instead of the concrete class, letting {@code LivePartyV2} slot
 * in behind the same seam. Remove together with {@link LiveParty} at P6. See PARTY_V2_MIGRATION.md.
 */
@Singleton
public class RuneLiteLivePartyBackend implements LivePartyBackend
{
	private final LiveParty delegate;

	@Inject
	private RuneLiteLivePartyBackend(LiveParty delegate)
	{
		this.delegate = delegate;
	}

	@Override
	public void register()
	{
		delegate.register();
	}

	@Override
	public void unregister()
	{
		delegate.unregister();
	}

	@Override
	public void addListener(Runnable listener)
	{
		delegate.addListener(listener);
	}

	@Override
	public void setOnEnded(Runnable onEnded)
	{
		delegate.setOnEnded(onEnded);
	}

	@Override
	public void setOnReadyCheckStarted(Consumer<String> onReadyCheckStarted)
	{
		delegate.setOnReadyCheckStarted(onReadyCheckStarted);
	}

	@Override
	public void setOnAllReady(Runnable onAllReady)
	{
		delegate.setOnAllReady(onAllReady);
	}

	@Override
	public void setOnReadyExpired(Runnable onReadyExpired)
	{
		delegate.setOnReadyExpired(onReadyExpired);
	}

	@Override
	public void generatePassphrase(Consumer<String> onGenerated)
	{
		delegate.generatePassphrase(onGenerated);
	}

	@Override
	public void hostParty(String passphrase, String hostName, String activityId, int capacity,
		boolean locked, String role, boolean learner, boolean teacher)
	{
		delegate.hostParty(passphrase, hostName, activityId, capacity, locked, role, learner, teacher);
	}

	@Override
	public void joinParty(String passphrase, String activityId, int teamSize, String role, boolean learner)
	{
		delegate.joinParty(passphrase, activityId, teamSize, role, learner);
	}

	@Override
	public void joinParty(String passphrase, String activityId, int teamSize, String role, boolean learner,
		boolean invited)
	{
		delegate.joinParty(passphrase, activityId, teamSize, role, learner, invited);
	}

	@Override
	public void leave()
	{
		delegate.leave();
	}

	@Override
	public void leaveForSwitch()
	{
		delegate.leaveForSwitch();
	}

	@Override
	public void rememberResumedRoster(List<Member> members)
	{
		delegate.rememberResumedRoster(members);
	}

	@Override
	public boolean isConnected()
	{
		return delegate.isConnected();
	}

	@Override
	public boolean isHosting()
	{
		return delegate.isHosting();
	}

	@Override
	public String passphrase()
	{
		return delegate.passphrase();
	}

	@Override
	public void setLocalRole(String role)
	{
		delegate.setLocalRole(role);
	}

	@Override
	public String getLocalRole()
	{
		return delegate.getLocalRole();
	}

	@Override
	public void setLocalLearner(boolean learner)
	{
		delegate.setLocalLearner(learner);
	}

	@Override
	public boolean isLocalLearner()
	{
		return delegate.isLocalLearner();
	}

	@Override
	public void setLocalTeacher(boolean teacher)
	{
		delegate.setLocalTeacher(teacher);
	}

	@Override
	public boolean isLocalTeacher()
	{
		return delegate.isLocalTeacher();
	}

	@Override
	public void markItemsDirty()
	{
		// The relay carries whole snapshots, so there is no finer state to mark.
		delegate.markLocalDirty();
	}

	@Override
	public void markStatsDirty(net.runelite.api.Skill skill, int realLevel)
	{
		// V1 re-sent on any stat change, boosted included; keep that exactly.
		delegate.markLocalDirty();
	}

	@Override
	public void markLocalDirty()
	{
		delegate.markLocalDirty();
	}

	@Override
	public void broadcastOffline(String name)
	{
		delegate.broadcastOffline(name);
	}

	@Override
	public void setCapacity(int capacity)
	{
		delegate.setCapacity(capacity);
	}

	@Override
	public void setDiscordInviteUrl(String url)
	{
		delegate.setDiscordInviteUrl(url);
	}

	@Override
	public String discordInviteUrl()
	{
		return delegate.discordInviteUrl();
	}

	@Override
	public void setLocked(boolean locked)
	{
		delegate.setLocked(locked);
	}

	@Override
	public boolean isLocked()
	{
		return delegate.isLocked();
	}

	@Override
	public boolean canAdmitMore()
	{
		return delegate.canAdmitMore();
	}

	@Override
	public boolean admit(long memberId, String name)
	{
		return delegate.admit(memberId, name);
	}

	@Override
	public void kick(long memberId)
	{
		delegate.kick(memberId);
	}

	@Override
	public void reject(long memberId)
	{
		delegate.reject(memberId);
	}

	@Override
	public void requestFriendsChat(long targetMemberId, String friendsChat)
	{
		delegate.requestFriendsChat(targetMemberId, friendsChat);
	}

	@Override
	public void sendJoinPrompt(long targetMemberId, String kind, String friendsChat)
	{
		delegate.sendJoinPrompt(targetMemberId, kind, friendsChat);
	}

	/**
	 * No-op: on the RuneLite relay, spec drains are broadcast by {@code SpecialAttackTracker} straight over
	 * {@code PartyService}, exactly as before. This seam exists for V2, which has no RuneLite party bus.
	 */
	@Override
	public void sendSpecDrain(int npcIndex, String weapon, int hit, int world)
	{
	}

	@Override
	public void promoteToHost(String hostName)
	{
		delegate.promoteToHost(hostName);
	}

	@Override
	public void demoteToMember()
	{
		delegate.demoteToMember();
	}

	@Override
	public void offerHostTransfer(long targetMemberId, String newHostKey, String newHostName, boolean hostStays)
	{
		delegate.offerHostTransfer(targetMemberId, newHostKey, newHostName, hostStays);
	}

	@Override
	public void acceptHostTransfer(long oldHostMemberId)
	{
		delegate.acceptHostTransfer(oldHostMemberId);
	}

	@Override
	public void commitHostTransfer(long targetMemberId, String newHostKey, boolean hostStays)
	{
		delegate.commitHostTransfer(targetMemberId, newHostKey, hostStays);
	}

	@Override
	public void abortHostTransfer(long targetMemberId)
	{
		delegate.abortHostTransfer(targetMemberId);
	}

	@Override
	public String currentActivityId()
	{
		return delegate.currentActivityId();
	}

	@Override
	public void startReadyCheck()
	{
		delegate.startReadyCheck();
	}

	@Override
	public void markReady()
	{
		delegate.markReady();
	}

	@Override
	public void onReadyCheck(ReadyCheckMessage message)
	{
		delegate.onReadyCheck(message);
	}

	@Override
	public LiveParty.ReadyCheckStatus readyCheck()
	{
		return delegate.readyCheck();
	}

	@Override
	public void sendPing(WorldPoint point, Color color)
	{
		delegate.sendPing(point, color);
	}

	@Override
	public void onPing(PingMessage message)
	{
		delegate.onPing(message);
	}

	@Override
	public List<TilePing> activePings()
	{
		return delegate.activePings();
	}

	@Override
	public Map<String, LiveParty.Marker> learnerMarkers()
	{
		return delegate.learnerMarkers();
	}

	@Override
	public void tick()
	{
		delegate.tick();
	}

	@Override
	public void onPlayerUpdate(PlayerUpdate update)
	{
		delegate.onPlayerUpdate(update);
	}

	@Override
	public void onPartyState(PartyStateMessage state)
	{
		delegate.onPartyState(state);
	}

	@Override
	public void onMemberCommand(MemberCommand command)
	{
		delegate.onMemberCommand(command);
	}

	@Override
	public void onPeerJoined(long memberId)
	{
		delegate.onPeerJoined(memberId);
	}

	@Override
	public void onPeerLeft(long memberId)
	{
		delegate.onPeerLeft(memberId);
	}

	@Override
	public boolean isForLocalMember(long memberId)
	{
		return delegate.isForLocalMember(memberId);
	}

	@Override
	public boolean isLocalAdmitted()
	{
		return delegate.isLocalAdmitted();
	}

	@Override
	public boolean isPendingApplicant(long memberId)
	{
		return delegate.isPendingApplicant(memberId);
	}

	@Override
	public long accountHashForMember(long memberId)
	{
		return delegate.accountHashForMember(memberId);
	}

	@Override
	public List<Member> rosterMembers()
	{
		return delegate.rosterMembers();
	}

	@Override
	public List<Member> currentMembers()
	{
		return delegate.currentMembers();
	}

	@Override
	public List<LiveParty.RosterMember> roster()
	{
		return delegate.roster();
	}

	@Override
	public int hostWorld()
	{
		return delegate.hostWorld();
	}

	@Override
	public boolean onDifferentWorldThanHost()
	{
		return delegate.onDifferentWorldThanHost();
	}

	@Override
	public List<String> neededRoles(List<String> requiredRoles)
	{
		return delegate.neededRoles(requiredRoles);
	}
}
