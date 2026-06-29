package net.osparty.api;

import net.osparty.model.Party;
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
	 * means "unknown" and is left unchanged. {@code hostKey} authorises the mutation.
	 */
	void heartbeat(String partyId, int size, int world, String layout, String roles, String hostKey,
		Consumer<Party> onSuccess, Consumer<Throwable> onError);

	/** Host action: close the ad. {@code hostKey} authorises it. */
	void disbandParty(String partyId, String host, String hostKey, Consumer<Party> onSuccess, Consumer<Throwable> onError);
}
