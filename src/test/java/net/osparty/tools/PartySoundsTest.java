package net.osparty.tools;

import net.osparty.enums.EventSound;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The volume arithmetic behind the sound setting: a percentage into a mixer gain. */
public class PartySoundsTest
{
	@Test
	public void fullIsUnityAndEachHalvingTakesAboutSixDecibels()
	{
		assertEquals(0f, PartySounds.gainDb(100), 0.0001f);
		assertEquals(-6.02f, PartySounds.gainDb(50), 0.01f);
		assertEquals(-12.04f, PartySounds.gainDb(25), 0.01f);
		assertEquals(-20f, PartySounds.gainDb(10), 0.01f);
	}

	@Test
	public void theQuietEndStopsAtTheMixersFloor()
	{
		assertEquals(PartySounds.MIN_GAIN_DB, PartySounds.gainDb(0), 0f);
		assertEquals(PartySounds.MIN_GAIN_DB, PartySounds.gainDb(-1), 0f);
		assertTrue(PartySounds.gainDb(1) >= PartySounds.MIN_GAIN_DB);
		assertTrue(PartySounds.gainDb(1) < PartySounds.gainDb(2));
		assertEquals(0f, PartySounds.gainDb(250), 0f);
	}

	@Test
	public void onlyThePingIsTheGamesOwnEffect()
	{
		for (EventSound sound : EventSound.values())
		{
			assertEquals(sound == EventSound.PING, sound.isInGame());
			assertFalse(sound != EventSound.PING && sound.getResource() == null);
		}
	}
}
