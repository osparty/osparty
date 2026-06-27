package net.osparty.ui;

import net.osparty.model.Party;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared, single-source-of-truth for the one party the player is currently in
 * — whether hosting it or joined as a member. A player can be in at most one
 * party at a time. UI panels register listeners and re-render when it changes.
 *
 * <p>All mutation happens on the EDT (from Swing callbacks), so no
 * synchronisation is needed.
 */
class PartyState
{
	private Party currentParty;
	private boolean host;
	/** Host opted to advertise the live CoX raid layout (sent via heartbeat). */
	private boolean advertiseLayout;
	private final List<Runnable> listeners = new ArrayList<>();

	Party getCurrentParty()
	{
		return currentParty;
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

	/** Enter a party as its host. */
	void setHosting(Party party)
	{
		currentParty = party;
		host = true;
		fire();
	}

	/** Enter a party as a (applied/joined) member. */
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

	/** Leave / clear the current party. */
	void clear()
	{
		currentParty = null;
		host = false;
		advertiseLayout = false;
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
