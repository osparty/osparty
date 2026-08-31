package net.osparty.enums;

/** What OSParty does when the local player makes a raid party in-game (CoX, ToB or ToA). */
public enum RaidPartyAutoCreate
{
	OFF("Off"),
	ASK("Ask me"),
	ALWAYS("Always advertise");

	private final String label;

	RaidPartyAutoCreate(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
