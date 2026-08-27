package net.osparty.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * How the low-defence threshold is read: as a number of levels above the monster's floor, or as
 * a percent of everything that can be drained, which holds up when starting levels scale with
 * party size.
 */
@Getter
@RequiredArgsConstructor
public enum DefenceThresholdUnit
{
	LEVELS("Levels"),
	PERCENT("Percent of drainable");

	private final String label;

	@Override
	public String toString()
	{
		return label;
	}
}
