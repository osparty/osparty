package net.osparty.party;

/**
 * Player-name handling shared across the live party.
 */
public final class PlayerNames
{
	/** RuneLite renders spaces in names as a non-breaking space; fold them for matching. */
	public static String normalize(String name)
	{
		return name == null ? "" : name.replace(' ', ' ').trim().toLowerCase();
	}

	private PlayerNames()
	{
	}
}
