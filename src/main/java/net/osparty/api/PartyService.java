package net.osparty.api;

import net.osparty.model.Activity;
import net.osparty.model.Party;
import net.osparty.model.PartyRequest;
import java.util.List;
import java.util.function.Consumer;

/**
 * Source of party advertisements, implemented by {@link PartyApiClient}. Only
 * discovery/advertising is handled here; membership/roster runs peer-to-peer.
 * Results may arrive on a background thread, so UI callers must marshal back
 * onto the EDT themselves.
 */
public interface PartyService
{
	void searchParties(Activity activity, String player, Consumer<List<Party>> onSuccess, Consumer<Throwable> onError);

	void getPartyByCode(String code, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	void getPartyByHost(String host, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	/**
	 * Create an advertised party. {@code hostKey} is a secret the caller mints; the
	 * server stores it in the party's session and requires it on later host-only
	 * mutations (see {@link #heartbeat}/{@link #disbandParty}), so only the real host
	 * can change or close the ad.
	 */
	void createParty(PartyRequest partyRequest, String hostKey, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	/**
	 * Host keep-alive: keep the ad live and report live occupancy/world/layout/roles
	 * (membership and whereabouts are peer-to-peer, so the ad alone can't track them).
	 * A non-positive {@code size}/{@code world} or null/blank {@code layout}/{@code roles}
	 * means "unknown" and is left unchanged. {@code roles} is a comma-separated list of
	 * role ids the host is still looking for. {@code hostKey} authorises the mutation.
	 */
	void heartbeat(String partyId, int size, int world, String layout, String roles, String hostKey,
		Consumer<Party> onSuccess, Consumer<Throwable> onError);

	void applyToParty(String partyId, String player, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	void cancelApplication(String partyId, String player, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	void acceptApplicant(String partyId, String host, String applicant, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	void kickPlayer(String partyId, String host, String target, Consumer<Party> onSuccess, Consumer<Throwable> onError);

	/** Host action: close the ad. {@code hostKey} authorises it. */
	void disbandParty(String partyId, String host, String hostKey, Consumer<Party> onSuccess, Consumer<Throwable> onError);
}
