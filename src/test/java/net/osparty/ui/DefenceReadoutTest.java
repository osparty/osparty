package net.osparty.ui;

import java.awt.Color;
import net.osparty.OSPartyConfig;
import net.osparty.enums.DefenceDrainFormat;
import net.osparty.enums.DefenceThresholdUnit;
import net.osparty.enums.DefenceValueFormat;
import net.osparty.tools.DefenceTracker.DefenceState;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The text and colour the scene overlay and info box derive from a tracked defence level. */
public class DefenceReadoutTest
{
	@Test
	public void valueFormats()
	{
		assertEquals("142", DefenceReadout.value(DefenceValueFormat.CURRENT, 142, 200));
		assertEquals("142/200", DefenceReadout.value(DefenceValueFormat.CURRENT_OF_BASE, 142, 200));
		assertEquals("71%", DefenceReadout.value(DefenceValueFormat.PERCENT, 142, 200));
		assertEquals("142 (71%)", DefenceReadout.value(DefenceValueFormat.CURRENT_AND_PERCENT, 142, 200));
	}

	@Test
	public void drainFormats()
	{
		assertEquals("58", DefenceReadout.drain(DefenceDrainFormat.AMOUNT, 142, 200));
		assertEquals("29%", DefenceReadout.drain(DefenceDrainFormat.PERCENT, 142, 200));
		assertNull(DefenceReadout.drain(DefenceDrainFormat.OFF, 142, 200));
	}

	@Test
	public void noDrainSuffixUntilSomethingIsDrained()
	{
		assertNull(DefenceReadout.drain(DefenceDrainFormat.AMOUNT, 200, 200));
		assertNull(DefenceReadout.drain(DefenceDrainFormat.PERCENT, 200, 200));
	}

	@Test
	public void percentTreatsEmptyStartAsUntouched()
	{
		assertEquals(100, DefenceReadout.percentRemaining(0, 0));
		assertEquals("100%", DefenceReadout.value(DefenceValueFormat.PERCENT, 0, 0));
	}

	@Test
	public void floorComesOffBothNumbersUnlessShowingTheFullLevel()
	{
		DefenceState akkha = state(73, 70, 80);
		assertEquals(3, DefenceReadout.shownDefence(akkha, false));
		assertEquals(10, DefenceReadout.shownBaseDefence(akkha, false));
		assertEquals(73, DefenceReadout.shownDefence(akkha, true));
		assertEquals(80, DefenceReadout.shownBaseDefence(akkha, true));
	}

	@Test
	public void thresholdReadsAsLevelsOrAsPercentOfWhatCanBeDrained()
	{
		OSPartyConfig config = mock(OSPartyConfig.class);
		when(config.defenceHighColor()).thenReturn(Color.WHITE);
		when(config.defenceLowColor()).thenReturn(Color.YELLOW);
		when(config.defenceCappedColor()).thenReturn(Color.GREEN);
		when(config.defenceLowThreshold()).thenReturn(10);

		// Nex: base 260, floor 250, so only 10 levels can ever come off.
		when(config.defenceLowThresholdUnit()).thenReturn(DefenceThresholdUnit.LEVELS);
		assertEquals(Color.YELLOW, DefenceReadout.defenceColor(state(258, 250, 260), config));

		when(config.defenceLowThresholdUnit()).thenReturn(DefenceThresholdUnit.PERCENT);
		assertEquals(Color.WHITE, DefenceReadout.defenceColor(state(258, 250, 260), config));
		assertEquals(Color.YELLOW, DefenceReadout.defenceColor(state(251, 250, 260), config));
		assertEquals(Color.GREEN, DefenceReadout.defenceColor(state(250, 250, 260), config));
	}

	private static DefenceState state(long current, long min, long base)
	{
		return new DefenceState(1, "Boss", current, min, base, 0, 0, 0, 0, 0, 0);
	}
}
