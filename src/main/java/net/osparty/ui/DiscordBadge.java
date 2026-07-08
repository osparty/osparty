package net.osparty.ui;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The Discord-role badges the API can assert on a party member, in display-priority order
 * (declaration order = render order). Wire names come from the backend's canonical set;
 * unknown strings from a newer server are simply skipped by {@link #fromWire}.
 *
 * <p>Sprite ids are the in-game cache sprites rendered by {@link BadgeIcons};
 * {@link #CONTENT_CREATOR} has none ({@code spriteId == -1}) because no YouTube icon exists
 * in the game cache — it uses the bundled {@code icons/youtube.png} instead.
 */
@Getter
@RequiredArgsConstructor
enum DiscordBadge
{
	DEVELOPER("developer", 908, "OSParty Developer"),           // SpriteID.TAB_OPTIONS — wrench
	CONTENT_CREATOR("content_creator", -1, "Content Creator"),  // bundled youtube.png
	BETA_TESTER("beta_tester", 3231, "Beta Tester"),            // ClanRankIcons._169 — blue flask
	BACKER("backer", 3289, "Backer");                           // ClanRankIcons._227 — gold heart

	private final String wireName;
	private final int spriteId;
	private final String tooltip;

	/** Map wire badge strings to badges in display order; unknown strings are ignored. */
	static List<DiscordBadge> fromWire(List<String> wireNames)
	{
		if (wireNames == null || wireNames.isEmpty())
		{
			return List.of();
		}
		List<DiscordBadge> badges = new ArrayList<>(values().length);
		for (DiscordBadge badge : values())
		{
			if (wireNames.contains(badge.wireName))
			{
				badges.add(badge);
			}
		}
		return badges;
	}
}
