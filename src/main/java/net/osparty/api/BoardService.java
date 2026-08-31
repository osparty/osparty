package net.osparty.api;

import net.osparty.model.Member;
import net.osparty.model.Advertisement;
import net.osparty.model.AdvertisementEditRequest;
import net.osparty.model.AdvertisementRequest;
import java.util.List;
import java.util.function.Consumer;

/**
 * The advertisement board: everything the plugin asks of the listing service. Implemented by
 * {@link BoardApiClient} over the shared WebSocket. Results may arrive off the EDT, so UI callers must
 * marshal back themselves.
 */
public interface BoardService
{
	/**
	 * Subscribe to live updates of the open-ad list; {@code onAds} gets the full list on each
	 * change. Reconnects automatically. Returns a handle to close when done.
	 */
	BoardSubscription subscribeAds(Consumer<List<Advertisement>> onAds, Consumer<Throwable> onError);

	/**
	 * Like {@link #subscribeAds(Consumer, Consumer)} but scopes the feed to one activity id
	 * ({@code null} = all). Re-scope later via {@link BoardSubscription#setActivity}.
	 */
	BoardSubscription subscribeAds(Consumer<List<Advertisement>> onAds, Consumer<Throwable> onError, String activityId);

	/** One-shot lookup of an ad by invite code (public or private). */
	void fetchAdByCode(String code, Consumer<Advertisement> onSuccess, Consumer<Throwable> onError);

	/** One-shot lookup of the ad hosted by a player (used to rejoin after a restart). */
	void fetchAdByHost(String host, Consumer<Advertisement> onSuccess, Consumer<Throwable> onError);

	/**
	 * Parties already running {@code activityId} in the same mode, closest to full first, so a host can be
	 * asked whether they would rather join one than advertise the same thing beside it. Empty when there
	 * are none, when the connection is down, or when the server predates the lookup.
	 */
	void fetchSimilarParties(String activityId, boolean hardMode, Consumer<java.util.List<Advertisement>> onResult);

	/**
	 * Register a callback fired (off the EDT) with the ad id when the server reports the
	 * hosted ad no longer exists (stale purge, manual cleanup, or expiry before a resume).
	 */
	default void setOnHostedAdGone(Consumer<String> callback)
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
	void createAd(AdvertisementRequest request, String hostKey, Consumer<Advertisement> onSuccess, Consumer<Throwable> onError);

	/**
	 * Report live occupancy/world/layout/roles for the hosted ad; only genuine changes are sent. A
	 * non-positive/null/blank field means "unknown" and is left unchanged. {@code members} is the live
	 * roster (host first, each by public id) for block/favourite matching. {@code hostKey} authorises it.
	 */
	void heartbeat(String adId, int size, int world, String layout, String roles, List<Member> members,
		String hostKey, Consumer<Advertisement> onSuccess, Consumer<Throwable> onError);

	/**
	 * Host action: edit the advertised party settings. Unlike {@link #heartbeat} this carries every
	 * editable field so values can be cleared as well as set. {@code hostKey} authorises it.
	 */
	void editAd(String adId, String hostKey, AdvertisementEditRequest edit, Consumer<Advertisement> onSuccess,
		Consumer<Throwable> onError);

	/** Host action: take the ad down (what the UI calls disbanding). {@code hostKey} authorises it. */
	void removeAd(String adId, String host, String hostKey, Consumer<Advertisement> onSuccess, Consumer<Throwable> onError);

	/**
	 * Host action: reassign the ad to {@code newHost} in place (same id/code/channel). {@code newHostKey}
	 * becomes the ad's credential. {@code newHostAccountType} re-stamps the ad's account-type badge, which
	 * belongs to whoever runs it; null means "not an ironman" (the board treats it as a normal account).
	 * {@code onSuccess} fires on the ack; {@code onError} on failure.
	 */
	void transferHost(String adId, String currentHostKey, String newHost, String newHostAccountType,
		String newHostKey, Consumer<Advertisement> onSuccess, Consumer<Throwable> onError);

	/** New host: adopt an ad handed to us via {@link #transferHost} so the socket owns and resumes it. */
	void adoptHostedAd(String adId, String hostKey);

	/** Old host: drop local hosting state for a handed-away ad WITHOUT disbanding it. */
	void releaseHostedAd(String adId);

	/**
	 * Host action: provision a Discord voice channel via the backend bot. {@code onUrl} gets the invite
	 * URL, {@code onError} on failure. Idempotent. {@code hostKey} authorises it. Callbacks may be off the EDT.
	 */
	void createVoiceChannel(String adId, String hostKey, Consumer<String> onUrl, Consumer<Throwable> onError);

	/**
	 * Begin an OAuth2 Discord link for {@code accountHash}. {@code onUrl} gets the authorize URL,
	 * {@code onError} on failure. Poll {@link #fetchDiscordLink} to learn when it completes.
	 */
	void startDiscordLink(long accountHash, Consumer<String> onUrl, Consumer<Throwable> onError);

	/** Look up whether {@code accountHash} is linked to a Discord account; result may be null if offline. */
	void fetchDiscordLink(long accountHash, Consumer<DiscordLinkStatus> onResult);

	/** Remove the Discord binding for {@code accountHash} server-side. Fire-and-forget. */
	void unlinkDiscord(long accountHash);

	/**
	 * Badge privacy: when {@code visible} is false the server strips this account's Discord-role badges
	 * from party ads. {@code onResult} gets the refreshed link status (or null if offline).
	 */
	void setBadgeVisibility(long accountHash, boolean visible, Consumer<DiscordLinkStatus> onResult);

	/**
	 * Host action: disconnect a kicked member (named by public id) from the party's voice channel.
	 * Fire-and-forget; no-ops unless they're linked and in that channel. {@code hostKey} authorises it.
	 */
	void kickVoiceMember(String adId, String hostKey, String playerId);

	/** Report {@code adId} for moderation, with the reporter's optional description of the problem. */
	void reportAd(String adId, String description);

	/**
	 * Member action: request per-user access to the party's voice channel, then open the invite.
	 * {@code onGranted} fires on success; {@code onError} if refused or offline.
	 */
	void requestVoiceAccess(String adId, long accountHash, Runnable onGranted, Consumer<Throwable> onError);

	/**
	 * Register our OSRS identity so the backend can route incoming invites to us. Remembered and re-sent
	 * across reconnects; safe to call repeatedly (e.g. once per login).
	 */
	void identify(long accountHash, String name);

	/**
	 * Invite an online friend to a party we're in. {@code onDelivered} gets true if the invite reached the
	 * friend's client, false if they weren't online in OSParty (or we're offline). May fire off the EDT.
	 */
	void inviteFriend(String adId, String fromName, long fromAccountHash, String targetName,
		Consumer<Boolean> onDelivered);

	/** Register where inbound invites are delivered; replaces any previous listener. May fire off the EDT. */
	void setInviteListener(Consumer<PartyInvite> listener);

	/** @return the server-reported number of connected plugin users, or {@code -1} if not yet known. */
	int onlineUserCount();
}
