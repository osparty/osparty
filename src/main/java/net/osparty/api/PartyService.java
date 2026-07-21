package net.osparty.api;

import net.osparty.model.Member;
import net.osparty.model.Party;
import net.osparty.model.PartyEditRequest;
import net.osparty.model.PartyRequest;
import java.util.List;
import java.util.function.Consumer;

/**
 * Source of party advertisements, implemented by {@link PartyApiClient} over the live WebSocket.
 * Results may arrive off the EDT, so UI callers must marshal back themselves.
 */
public interface PartyService
{
	/**
	 * Subscribe to live updates of the open-party list; {@code onParties} gets the full list on each
	 * change. Reconnects automatically. Returns a handle to close when done.
	 */
	PartySubscription subscribeParties(Consumer<List<Party>> onParties, Consumer<Throwable> onError);

	/**
	 * Like {@link #subscribeParties(Consumer, Consumer)} but scopes the feed to one activity id
	 * ({@code null} = all). Re-scope later via {@link PartySubscription#setActivity}.
	 */
	PartySubscription subscribeParties(Consumer<List<Party>> onParties, Consumer<Throwable> onError, String activityId);

	/** One-shot lookup of a party by invite code (public or private). */
	void getPartyByCode(String code, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	/** One-shot lookup of the ad hosted by a player (used to rejoin after a restart). */
	void getPartyByHost(String host, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	/**
	 * Register a callback fired (off the EDT) with the party id when the server reports the
	 * hosted ad no longer exists (stale purge, manual cleanup, or expiry before a resume).
	 */
	default void setOnHostedPartyGone(Consumer<String> callback)
	{
	}

	/** Whether the API socket is currently connected (lookups return null instantly when it isn't). */
	default boolean isApiConnected()
	{
		return true;
	}

	/**
	 * Create an advertised party. {@code hostKey} is a secret the caller mints; the server requires it on
	 * later host-only mutations, so only the real host can change or close the ad.
	 */
	void createParty(PartyRequest partyRequest, String hostKey, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	/**
	 * Report live occupancy/world/layout/roles for the hosted ad; only genuine changes are sent. A
	 * non-positive/null/blank field means "unknown" and is left unchanged. {@code members} is the live
	 * roster (host first, with accountHashes) for block/favourite matching. {@code hostKey} authorises it.
	 */
	void heartbeat(String partyId, int size, int world, String layout, String roles, List<Member> members,
		String hostKey, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	/**
	 * Host action: edit the advertised party settings. Unlike {@link #heartbeat} this carries every
	 * editable field so values can be cleared as well as set. {@code hostKey} authorises it.
	 */
	void editParty(String partyId, String hostKey, PartyEditRequest edit, Consumer<Party> onSuccess,
		Consumer<Throwable> onError);

	/** Host action: close the ad. {@code hostKey} authorises it. */
	void disbandParty(String partyId, String host, String hostKey, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	/**
	 * Host action: reassign the ad to {@code newHost} in place (same id/code/channel). {@code newHostKey}
	 * becomes the ad's credential. {@code onSuccess} fires on the ack; {@code onError} on failure.
	 */
	void transferHost(String partyId, String currentHostKey, String newHost, String newHostKey,
		Consumer<Party> onSuccess, Consumer<Throwable> onError);

	/** New host: adopt an ad handed to us via {@link #transferHost} so the socket owns and resumes it. */
	void adoptHostedParty(String partyId, String hostKey);

	/** Old host: drop local hosting state for a handed-away party WITHOUT disbanding it. */
	void releaseHostedParty(String partyId);

	/**
	 * Host action: provision a Discord voice channel via the backend bot. {@code onUrl} gets the invite
	 * URL, {@code onError} on failure. Idempotent. {@code hostKey} authorises it. Callbacks may be off the EDT.
	 */
	void createVoiceChannel(String partyId, String hostKey, Consumer<String> onUrl, Consumer<Throwable> onError);

	/**
	 * Begin an OAuth2 Discord link for {@code accountHash}. {@code onUrl} gets the authorize URL,
	 * {@code onError} on failure. Poll {@link #getDiscordLink} to learn when it completes.
	 */
	void startDiscordLink(long accountHash, Consumer<String> onUrl, Consumer<Throwable> onError);

	/** Look up whether {@code accountHash} is linked to a Discord account; result may be null if offline. */
	void getDiscordLink(long accountHash, Consumer<DiscordLinkStatus> onResult);

	/** Remove the Discord binding for {@code accountHash} server-side. Fire-and-forget. */
	void unlinkDiscord(long accountHash);

	/**
	 * Badge privacy: when {@code visible} is false the server strips this account's Discord-role badges
	 * from party ads. {@code onResult} gets the refreshed link status (or null if offline).
	 */
	void setBadgeVisibility(long accountHash, boolean visible, Consumer<DiscordLinkStatus> onResult);

	/**
	 * Host action: disconnect a kicked member from the party's voice channel. Fire-and-forget; no-ops
	 * unless they're linked and in that channel. {@code hostKey} authorises it.
	 */
	void kickVoiceMember(String partyId, String hostKey, long accountHash);

	/**
	 * Member action: request per-user access to the party's voice channel, then open the invite.
	 * {@code onGranted} fires on success; {@code onError} if refused or offline.
	 */
	void requestVoiceAccess(String partyId, long accountHash, Runnable onGranted, Consumer<Throwable> onError);

	/**
	 * Register our OSRS identity so the backend can route incoming invites to us. Remembered and re-sent
	 * across reconnects; safe to call repeatedly (e.g. once per login).
	 */
	void identify(long accountHash, String name);

	/**
	 * Invite an online friend to a party we're in. {@code onDelivered} gets true if the invite reached the
	 * friend's client, false if they weren't online in OSParty (or we're offline). May fire off the EDT.
	 */
	void inviteFriend(String partyId, String fromName, long fromAccountHash, String targetName,
		Consumer<Boolean> onDelivered);

	/** Register where inbound invites are delivered; replaces any previous listener. May fire off the EDT. */
	void setInviteListener(Consumer<PartyInvite> listener);

	/** @return the server-reported number of connected plugin users, or {@code -1} if not yet known. */
	int onlineUsers();
}
