package net.osparty.api;

import net.osparty.model.Party;

/** A party invite pushed to us by the backend: the party to join and who sent it. */
public final class PartyInvite
{
	private final Party party;
	private final String fromName;

	public PartyInvite(Party party, String fromName)
	{
		this.party = party;
		this.fromName = fromName;
	}

	public Party getParty()
	{
		return party;
	}

	/** The player who invited us (host name when the sender didn't identify). May be null. */
	public String getFromName()
	{
		return fromName;
	}
}
