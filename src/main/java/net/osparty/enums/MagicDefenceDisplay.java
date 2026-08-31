package net.osparty.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * How the monster's live magic defence is written out. The magic-defence bonus is the
 * number the Eye of ayak drains directly, and the Magic level is what the accursed
 * sceptre and Seercull drain, so each reads like the Defence row beside it but only
 * moves for its own weapons — the percentage of the starting magic-defence roll catches
 * both.
 */
@Getter
@RequiredArgsConstructor
public enum MagicDefenceDisplay
{
	BONUS("Magic defence bonus"),
	LEVEL("Magic level"),
	PERCENT("Percent of starting roll"),
	BOTH("Bonus and percent");

	private final String label;

	@Override
	public String toString()
	{
		return label;
	}
}
