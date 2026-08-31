package net.osparty.ui;

import java.awt.Color;
import net.osparty.OSPartyConfig;
import net.osparty.enums.DefenceDrainFormat;
import net.osparty.enums.DefenceThresholdUnit;
import net.osparty.enums.DefenceValueFormat;
import net.osparty.tools.DefenceTracker.DefenceState;

/**
 * Turns a tracked level into the text the scene overlay and info box draw, so both follow the
 * same "Defence shown as" / "Drain shown as" settings. A level is described by where it is now
 * and where it started; when the display counts from the monster's floor, the caller has
 * already taken the floor off both.
 */
final class DefenceReadout
{
	private static final int PERCENT = 100;

	private DefenceReadout()
	{
	}

	static String value(DefenceValueFormat format, long current, long base)
	{
		switch (format)
		{
			case CURRENT_OF_BASE:
				return current + "/" + base;
			case PERCENT:
				return percentRemaining(current, base) + "%";
			case CURRENT_AND_PERCENT:
				return current + " (" + percentRemaining(current, base) + "%)";
			case CURRENT:
			default:
				return Long.toString(current);
		}
	}

	/** @return the drain suffix, or null when nothing has been drained yet or drains are hidden. */
	static String drain(DefenceDrainFormat format, long current, long base)
	{
		long drained = base - current;
		if (drained <= 0 || format == DefenceDrainFormat.OFF)
		{
			return null;
		}
		return format == DefenceDrainFormat.PERCENT
			? percent(drained, base) + "%"
			: Long.toString(drained);
	}

	/** Percent of the starting value that is left, treating an empty starting value as untouched. */
	static long percentRemaining(long current, long base)
	{
		return base > 0 ? percent(Math.max(0, current), base) : PERCENT;
	}

	/** The Defence level as displayed: the full level, or how far it still sits above the monster's floor. */
	static long shownDefence(DefenceState state, boolean fullLevel)
	{
		return Math.max(0, fullLevel ? state.getCurrent() : state.getCurrent() - state.getMin());
	}

	static long shownBaseDefence(DefenceState state, boolean fullLevel)
	{
		return Math.max(0, fullLevel ? state.getBase() : state.getBase() - state.getMin());
	}

	static Color defenceColor(DefenceState state, OSPartyConfig config)
	{
		long aboveFloor = Math.max(state.getCurrent() - state.getMin(), 0);
		if (aboveFloor == 0)
		{
			return config.defenceCappedColor();
		}
		long threshold = config.defenceLowThresholdUnit() == DefenceThresholdUnit.PERCENT
			? (state.getBase() - state.getMin()) * Math.min(PERCENT, config.defenceLowThreshold()) / PERCENT
			: config.defenceLowThreshold();
		return aboveFloor <= threshold ? config.defenceLowColor() : config.defenceHighColor();
	}

	private static long percent(long part, long whole)
	{
		return Math.round(part * 100.0 / whole);
	}
}
