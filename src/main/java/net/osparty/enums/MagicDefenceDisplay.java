package net.osparty.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * How the monster's live magic defence is written out. The magic-defence bonus is the
 * number the Eye of ayak drains directly, so it reads like the Defence row beside it,
 * but it doesn't move when the accursed sceptre or Seercull drain the Magic
 * <em>level</em> — the percentage of the starting magic-defence roll catches both.
 */
@Getter
@RequiredArgsConstructor
public enum MagicDefenceDisplay
{
	BONUS("Magic defence bonus"),
	PERCENT("Percent of starting roll"),
	BOTH("Both");

	private final String label;

	@Override
	public String toString()
	{
		return label;
	}
}
