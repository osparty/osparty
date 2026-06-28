package net.osparty.ui;

import net.osparty.OSPartyConfig;
import net.osparty.model.Party;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.config.ConfigManager;

/**
 * Shared, single-source-of-truth for the one party the player is currently in
 * — whether hosting it or joined as a member. A player can be in at most one
 * party at a time. UI panels register listeners and re-render when it changes.
 *
 * <p>Also holds the host credential (a secret key) for the party we host: the
 * server stores it in the party's session, and we send it on host-only mutations
 * (heartbeat/disband) so only the real host can change or close the ad. It's
 * persisted via {@link ConfigManager} so a hosted party survives a client restart
 * (see {@link #resumeHosting}); leaving/disbanding wipes it.
 *
 * <p>All mutation happens on the EDT (from Swing callbacks), so no
 * synchronisation is needed.
 */
class PartyState
{
	/** ConfigManager keys for the persisted host credential and the party it belongs to. */
	private static final String KEY_HOST_KEY = "hostKey";
	private static final String KEY_HOST_KEY_PARTY = "hostKeyPartyId";

	private final ConfigManager configManager;

	private Party currentParty;
	private boolean host;
	private boolean advertiseLayout;
	/** Secret authorising host-only API mutations for the party we host; null otherwise. */
	private String hostKey;
	private final List<Runnable> listeners = new ArrayList<>();

	PartyState(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	Party getCurrentParty()
	{
		return currentParty;
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
	}

	boolean isInParty()
	{
		return currentParty != null;
	}

	void addListener(Runnable listener)
	{
		listeners.add(listener);
	}

	/** Host a freshly created party with its new credential, persisting it for resume. */
	void setHosting(Party party, String hostKey)
	{
		currentParty = party;
		host = true;
		this.hostKey = hostKey;
		configManager.setConfiguration(OSPartyConfig.GROUP, KEY_HOST_KEY_PARTY, party.getId());
		configManager.setConfiguration(OSPartyConfig.GROUP, KEY_HOST_KEY, hostKey);
		fire();
	}

	/** Resume hosting after a restart, recovering the saved credential for this party. */
	void resumeHosting(Party party)
	{
		currentParty = party;
		host = true;
		this.hostKey = loadHostKey(party.getId());
		fire();
	}

	private String loadHostKey(String partyId)
	{
		String savedParty = configManager.getConfiguration(OSPartyConfig.GROUP, KEY_HOST_KEY_PARTY);
		if (partyId != null && partyId.equals(savedParty))
		{
			return configManager.getConfiguration(OSPartyConfig.GROUP, KEY_HOST_KEY);
		}
		return null;
	}

	void setMember(Party party)
	{
		currentParty = party;
		host = false;
		advertiseLayout = false;
		fire();
	}

	/** Replace the current party object (e.g. after a roster change), keeping the role. */
	void update(Party party)
	{
		currentParty = party;
		fire();
	}

	void clear()
	{
		currentParty = null;
		host = false;
		advertiseLayout = false;
		hostKey = null;
		// The party is over; drop the persisted credential so it isn't resumed later.
		configManager.unsetConfiguration(OSPartyConfig.GROUP, KEY_HOST_KEY);
		configManager.unsetConfiguration(OSPartyConfig.GROUP, KEY_HOST_KEY_PARTY);
		fire();
	}

	private void fire()
	{
		for (Runnable listener : listeners)
		{
			listener.run();
		}
	}
}
