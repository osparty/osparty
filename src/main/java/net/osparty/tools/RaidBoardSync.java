package net.osparty.tools;

import java.util.Objects;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.osparty.model.Activity;
import net.osparty.model.Advertisement;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * Keeps a hosted raid ad in step with what is set on the in-game party board: the Chambers of Xeric
 * scaling and the Tombs of Amascut invocation level. Both are chosen on the board after the party exists
 * and change as the team firms up, and both are things the ad advertises, so a host who sets them in-game
 * should not have to set them again in the form.
 *
 * <p>Polled once a tick on the client thread. A change is pushed only once the board has held its value
 * for a few ticks, so the ad follows what was settled on rather than every step of setting it, and a
 * value is pushed once: the ad catching up is what allows the next one.
 */
@Slf4j
@Singleton
public class RaidBoardSync
{
	/** Ticks the board must hold a value before the ad follows it. */
	static final int SETTLE_TICKS = 3;

	private static final int NO_COX_PARTY = -1;
	private static final int NOTHING = -1;

	/** What the board says the ad should carry; only the field for the ad's raid is meaningful. */
	public interface Listener
	{
		/**
		 * @param coxScale the size the Chambers raid is scaled to, or "" when it is not scaled
		 * @param invocation the Tombs raid level
		 */
		void onBoardChanged(Activity activity, String coxScale, int invocation);
	}

	private final Client client;
	private Supplier<Advertisement> hostedAd = () -> null;
	private Listener listener;

	private String adId;
	private String pendingScale;
	private int pendingInvocation = NOTHING;
	private int settledTicks;
	private String sentScale;
	private int sentInvocation = NOTHING;

	@Inject
	RaidBoardSync(Client client)
	{
		this.client = client;
	}

	/** The ad the local player hosts, or null; read on the client thread each tick. */
	public void setHostedAd(Supplier<Advertisement> hostedAd)
	{
		this.hostedAd = hostedAd == null ? () -> null : hostedAd;
	}

	public void setListener(Listener listener)
	{
		this.listener = listener;
	}

	public void reset()
	{
		adId = null;
		forget();
	}

	/** Compare the board with the ad and push a settled difference. Client thread. */
	public void update()
	{
		Advertisement ad = hostedAd.get();
		if (ad == null || client.getGameState() != GameState.LOGGED_IN)
		{
			reset();
			return;
		}
		if (!Objects.equals(ad.getId(), adId))
		{
			adId = ad.getId();
			forget();
		}
		Activity activity = Activity.fromId(ad.getActivity());
		if (activity == Activity.CHAMBERS_OF_XERIC)
		{
			followScale(ad);
		}
		else if (activity == Activity.TOMBS_OF_AMASCUT)
		{
			followInvocation(ad);
		}
	}

	private void followScale(Advertisement ad)
	{
		if (client.getVarpValue(VarPlayerID.RAIDS_PARTY_GROUPHOLDER) == NO_COX_PARTY)
		{
			clearPending();
			return;
		}
		int scaling = client.getVarbitValue(VarbitID.RAIDS_SCALING);
		String want = scaling > 0 ? Integer.toString(scaling) : "";
		String have = ad.getCoxScale() == null ? "" : ad.getCoxScale().trim();
		if (want.equals(have))
		{
			sentScale = null;
			clearPending();
			return;
		}
		if (want.equals(sentScale))
		{
			return;
		}
		if (!want.equals(pendingScale))
		{
			pendingScale = want;
			settledTicks = 0;
			return;
		}
		if (++settledTicks < SETTLE_TICKS)
		{
			return;
		}
		sentScale = want;
		clearPending();
		log.debug("CoX board scaling is {}; the ad had '{}', updating it", want, have);
		if (listener != null)
		{
			listener.onBoardChanged(Activity.CHAMBERS_OF_XERIC, want, ad.getInvocation());
		}
	}

	private void followInvocation(Advertisement ad)
	{
		if (client.getVarbitValue(VarbitID.TOA_CLIENT_PARTYSTATUS) == 0)
		{
			clearPending();
			return;
		}
		int level = Math.max(0, client.getVarbitValue(VarbitID.TOA_CLIENT_RAID_LEVEL));
		if (level == ad.getInvocation())
		{
			sentInvocation = NOTHING;
			clearPending();
			return;
		}
		if (level == sentInvocation)
		{
			return;
		}
		if (level != pendingInvocation)
		{
			pendingInvocation = level;
			settledTicks = 0;
			return;
		}
		if (++settledTicks < SETTLE_TICKS)
		{
			return;
		}
		sentInvocation = level;
		clearPending();
		log.debug("ToA raid level is {}; the ad had {}, updating it", level, ad.getInvocation());
		if (listener != null)
		{
			listener.onBoardChanged(Activity.TOMBS_OF_AMASCUT, ad.getCoxScale(), level);
		}
	}

	private void clearPending()
	{
		pendingScale = null;
		pendingInvocation = NOTHING;
		settledTicks = 0;
	}

	private void forget()
	{
		clearPending();
		sentScale = null;
		sentInvocation = NOTHING;
	}
}
