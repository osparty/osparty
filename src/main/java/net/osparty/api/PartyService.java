package net.osparty.api;

import net.osparty.model.Activity;
import net.osparty.model.Party;
import net.osparty.model.PartyRequest;
import java.util.List;
import java.util.function.Consumer;

/**
 * Source of party advertisements, implemented by {@link PartyApiClient} (the
 * HTTP bulletin-board API). Membership/roster is not handled here — that runs
 * peer-to-peer; only the discovery/advertising calls (search/create/disband)
 * are used by the UI. All calls are asynchronous; results may arrive on a
 * background thread, so UI callers must marshal back onto the EDT themselves.
 */
public interface PartyService
{
	void searchParties(Activity activity, String player, Consumer<List<Party>> onSuccess, Consumer<Throwable> onError);

	/** Fetch a single party (public or private) by its invite code. */
	void getPartyByCode(String code, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	/** Fetch the ad currently hosted by {@code host}, if any (for rejoin-on-restart). */
	void getPartyByHost(String host, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	void createParty(PartyRequest partyRequest, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	/**
	 * Host keep-alive: tell the bulletin board the advertised party is still live,
	 * and report the current live member count so search results show the real
	 * occupancy (the API only tracks the ad; membership is peer-to-peer).
	 */
	void heartbeat(String partyId, int size, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	/** Submit an application for the logged in player to the given party. */
	void applyToParty(String partyId, String player, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	/** Withdraw the logged in player's pending application to the given party. */
	void cancelApplication(String partyId, String player, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	/** Host action: admit an applicant into the party. Returns the updated party. */
	void acceptApplicant(String partyId, String host, String applicant, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	/** Host action: remove a member from the party. Returns the updated party. */
	void kickPlayer(String partyId, String host, String target, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	/** Host action: close the party. */
	void disbandParty(String partyId, String host, Consumer<Party> onSuccess, Consumer<Throwable> onError);
}
