package net.osparty.api;

import net.osparty.model.Member;
import net.osparty.model.Party;
import net.osparty.model.PartyEditRequest;
import net.osparty.model.PartyRequest;
import java.util.List;
import java.util.function.Consumer;

/**
 * Source of party advertisements, implemented by {@link PartyApiClient} over the live
 * WebSocket. Only discovery/advertising is handled here; membership/roster runs
 * peer-to-peer. Results may arrive on a background thread, so UI callers must marshal
 * back onto the EDT themselves.
 */
public interface PartyService
{
	/**
	 * Subscribe to live updates of the open-party list. The server pushes a full snapshot
	 * on (re)connect and incremental changes after; {@code onParties} is invoked with the
	 * complete current list each time it changes. Reconnects automatically. Returns a
	 * handle to close when done.
	 */
	PartySubscription subscribeParties(Consumer<List<Party>> onParties, Consumer<Throwable> onError);

	/**
	 * Like {@link #subscribeParties(Consumer, Consumer)} but scopes the server feed to a single
	 * activity id ({@code null} = all), so the server only fans out the matching ads. Re-scope later
	 * via {@link PartySubscription#setActivity}.
	 */
	PartySubscription subscribeParties(Consumer<List<Party>> onParties, Consumer<Throwable> onError, String activityId);

	/** One-shot lookup of a party by invite code (public or private). */
	void getPartyByCode(String code, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	/** One-shot lookup of the ad hosted by a player (used to rejoin after a restart). */
	void getPartyByHost(String host, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	/**
	 * Create an advertised party. {@code hostKey} is a secret the caller mints; the server
	 * stores it in the party's session and requires it on later host-only mutations, so
	 * only the real host can change or close the ad.
	 */
	void createParty(PartyRequest partyRequest, String hostKey, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	/**
	 * Report live occupancy/world/layout/roles for the hosted ad. With the socket open the
	 * connection itself is the keep-alive, so this only carries genuine field changes. A
	 * non-positive {@code size}/{@code world} or null/blank {@code layout}/{@code roles}
	 * means "unknown" and is left unchanged. {@code members} is the live roster (host first,
	 * with accountHashes) advertised so search clients can block/favourite-match members;
	 * null/empty leaves it unchanged. {@code hostKey} authorises the mutation.
	 */
	void heartbeat(String partyId, int size, int world, String layout, String roles, List<Member> members,
		String hostKey, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	/**
	 * Host action: edit the advertised settings of a party (description, capacity, loot rule,
	 * requirements, roles, etc.). Unlike {@link #heartbeat}, this carries every editable field
	 * so values can be cleared as well as set. {@code hostKey} authorises the mutation; the
	 * updated ad arrives back via the live list (and a roster broadcast for joined members).
	 */
	void editParty(String partyId, String hostKey, PartyEditRequest edit, Consumer<Party> onSuccess,
		Consumer<Throwable> onError);

	/** Host action: close the ad. {@code hostKey} authorises it. */
	void disbandParty(String partyId, String host, String hostKey, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	/**
	 * Host action: provision a Discord voice channel for the party via the backend bot. {@code onUrl}
	 * receives the invite URL to share with members; {@code onError} fires if the socket is down or the
	 * server can't create one. Idempotent — repeated calls return the same channel's URL. {@code hostKey}
	 * authorises the request. Callbacks may arrive off the EDT.
	 */
	void createVoiceChannel(String partyId, String hostKey, Consumer<String> onUrl, Consumer<Throwable> onError);

	/**
	 * Begin an OAuth2 Discord account link for {@code accountHash}. {@code onUrl} receives the Discord
	 * authorize URL to open in a browser; {@code onError} fires if the socket is down or linking is
	 * disabled server-side. The binding is one-time and stored server-side; poll {@link #getDiscordLink}
	 * to learn when it completes.
	 */
	void startDiscordLink(long accountHash, Consumer<String> onUrl, Consumer<Throwable> onError);

	/** Look up whether {@code accountHash} is linked to a Discord account; result may be null if offline. */
	void getDiscordLink(long accountHash, Consumer<DiscordLinkStatus> onResult);

	/**
	 * Host action: disconnect the kicked member from the party's Discord voice channel. Fire-and-forget;
	 * the backend no-ops unless the member is linked and currently in that channel. {@code hostKey}
	 * authorises it.
	 */
	void kickVoiceMember(String partyId, String hostKey, long accountHash);

	/**
	 * Member action: request per-user access to the party's voice channel (for someone who joined/linked
	 * after it was created), then open the invite. {@code onGranted} fires on success; {@code onError} if
	 * refused or offline. Verified server-side by roster membership + Discord link.
	 */
	void requestVoiceAccess(String partyId, long accountHash, Runnable onGranted, Consumer<Throwable> onError);

	/** @return the server-reported number of connected plugin users, or {@code -1} if not yet known. */
	int onlineUsers();
}
