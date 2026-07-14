package net.osparty.enums;

import net.osparty.OSPartyConfig;

/**
 * How an incoming party invite is surfaced to the player.
 * Configurable via {@link OSPartyConfig#inviteDisplay()}.
 */
public enum InviteDisplay
{
	/** Blink the OSParty sidebar button and post an in-game chat line. */
	BOTH("Sidebar + in-game"),
	/** Only blink the OSParty sidebar button. */
	SIDEBAR_ONLY("Sidebar only"),
	/** Only post an in-game chat line. */
	INGAME_ONLY("In-game only"),
	/** Ignore invites entirely. */
	DISABLED("Disabled");

	private final String label;

	InviteDisplay(String label)
	{
		this.label = label;
	}

	public boolean showsSidebar()
	{
		return this == BOTH || this == SIDEBAR_ONLY;
	}

	public boolean showsInGame()
	{
		return this == BOTH || this == INGAME_ONLY;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
