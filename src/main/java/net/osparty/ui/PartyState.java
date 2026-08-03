package net.osparty.ui;

import net.osparty.OSPartyConfig;
import net.osparty.model.Advertisement;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.config.ConfigManager;

/**
 * Single source of truth for the one party the player is in (host or member), held as the
 * {@link Advertisement} that describes it. Also holds the persisted host credential sent on
 * host-only mutations. EDT-only, so no synchronisation.
 */
class PartyState
{
	private static final String KEY_HOST_KEY = "hostKey";
	private static final String KEY_HOST_KEY_AD = "hostKeyPartyId";
	/** Persisted with the credential so a resumed host keeps advertising the CoX layout. */
	private static final String KEY_ADVERTISE_LAYOUT = "hostAdvertiseLayout";

	private final ConfigManager configManager;

	private Advertisement currentAd;
	private boolean host;
	private boolean advertiseLayout;
	/** Secret authorising host-only API mutations for the party we host; null otherwise. */
	private String hostKey;
	private final List<Runnable> listeners = new ArrayList<>();

	PartyState(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	Advertisement getCurrentAd()
	{
		return currentAd;
	}

	/** @return the host credential for the party we host, or null when not hosting / unknown. */
	String getHostKey()
	{
		return hostKey;
	}

	boolean isHost()
	{
		return host;
	}

	boolean isAdvertiseLayout()
	{
		return advertiseLayout;
	}

	void setAdvertiseLayout(boolean advertiseLayout)
	{
		this.advertiseLayout = advertiseLayout;
		// Persisted so resumeHosting() can restore it after a restart.
		configManager.setConfiguration(OSPartyConfig.GROUP, KEY_ADVERTISE_LAYOUT, advertiseLayout);
	}

	boolean isInParty()
	{
		return currentAd != null;
	}

	void addListener(Runnable listener)
	{
		listeners.add(listener);
	}

	/** Host a freshly created party with its new credential, persisting it for resume. */
	void setHosting(Advertisement ad, String hostKey)
	{
		currentAd = ad;
		host = true;
		this.hostKey = hostKey;
		configManager.setConfiguration(OSPartyConfig.GROUP, KEY_HOST_KEY_AD, ad.getId());
		configManager.setConfiguration(OSPartyConfig.GROUP, KEY_HOST_KEY, hostKey);
		fire();
	}

	/** Resume hosting after a restart, recovering the saved credential for this ad. */
	void resumeHosting(Advertisement ad)
	{
		currentAd = ad;
		host = true;
		this.hostKey = loadHostKey(ad.getId());
		// Restore the layout-advertising choice, but only when the saved key is for this ad.
		this.advertiseLayout = hostKey != null && Boolean.parseBoolean(
			configManager.getConfiguration(OSPartyConfig.GROUP, KEY_ADVERTISE_LAYOUT));
		fire();
	}

	private String loadHostKey(String adId)
	{
		String savedAd = configManager.getConfiguration(OSPartyConfig.GROUP, KEY_HOST_KEY_AD);
		if (adId != null && adId.equals(savedAd))
		{
			return configManager.getConfiguration(OSPartyConfig.GROUP, KEY_HOST_KEY);
		}
		return null;
	}

	void setMember(Advertisement ad)
	{
		currentAd = ad;
		host = false;
		advertiseLayout = false;
		hostKey = null;
		forgetHostKey();
		fire();
	}

	/** Step down from host to member after transferring the party; drops and unpersists the host key. */
	void demoteToMember(Advertisement ad)
	{
		setMember(ad);
	}

	/** Replace the current ad (e.g. after a roster change), keeping the role. */
	void update(Advertisement ad)
	{
		currentAd = ad;
		fire();
	}

	void clear()
	{
		currentAd = null;
		host = false;
		advertiseLayout = false;
		hostKey = null;
		forgetHostKey();
		fire();
	}

	/** Drop the persisted host credential so a party we no longer host can't be resumed later. */
	private void forgetHostKey()
	{
		configManager.unsetConfiguration(OSPartyConfig.GROUP, KEY_HOST_KEY);
		configManager.unsetConfiguration(OSPartyConfig.GROUP, KEY_HOST_KEY_AD);
		configManager.unsetConfiguration(OSPartyConfig.GROUP, KEY_ADVERTISE_LAYOUT);
	}

	private void fire()
	{
		for (Runnable listener : listeners)
		{
			listener.run();
		}
	}
}
