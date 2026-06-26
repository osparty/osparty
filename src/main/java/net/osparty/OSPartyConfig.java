package net.osparty;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(OSPartyConfig.GROUP)
public interface OSPartyConfig extends Config
{
	String GROUP = "osparty";

	@ConfigItem(
		keyName = "apiBaseUrl",
		name = "API base URL",
		description = "Base URL of the party advertising API (no trailing slash).",
		position = 1
	)
	default String apiBaseUrl()
	{
		return "https://api.osparty.net";
	}

	@ConfigItem(
		keyName = "defaultCapacity",
		name = "Default party size",
		description = "Capacity pre-filled in the create-party form.",
		position = 2
	)
	default int defaultCapacity()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "runeWatch",
		name = "RuneWatch warnings",
		description = "Warn when a party member or applicant is on the RuneWatch / We Do Raids scammer watchlist.",
		position = 3
	)
	default boolean runeWatch()
	{
		return true;
	}

	@ConfigItem(
		keyName = "inGamePrompts",
		name = "In-game join prompts",
		description = "As a host, show Accept/Decline for new applicants in the in-game chatbox (not just the side panel).",
		position = 4
	)
	default boolean inGamePrompts()
	{
		return true;
	}
}
