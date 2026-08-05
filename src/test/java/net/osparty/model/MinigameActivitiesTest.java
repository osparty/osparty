package net.osparty.model;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The three group minigames. Their ids travel to the board API and back on every advertisement, so a typo
 * here is a party nobody can find; their regions are what puts the right activity in front of a player
 * standing at one.
 */
public class MinigameActivitiesTest
{
	@Test
	public void resolvesByTheIdTheBoardCarries()
	{
		assertEquals(Activity.CASTLE_WARS, Activity.fromId("castlewars"));
		assertEquals(Activity.GUARDIANS_OF_THE_RIFT, Activity.fromId("gotr"));
		assertEquals(Activity.WINTERTODT, Activity.fromId("wintertodt"));
	}

	@Test
	public void foundFromTheRegionsLoadedAtEachOne()
	{
		assertEquals(Activity.CASTLE_WARS, Activity.nearby(new int[]{9776}));
		assertEquals(Activity.GUARDIANS_OF_THE_RIFT, Activity.nearby(new int[]{14484}));
		assertEquals(Activity.WINTERTODT, Activity.nearby(new int[]{6462}));
	}

	@Test
	public void onlyTheScoredOnesOfferAKcRequirement()
	{
		// Castle Wars is not on the hiscores at all; the other two are, as rifts closed and kills.
		assertFalse(Activity.CASTLE_WARS.hasKillcount());
		assertTrue(Activity.GUARDIANS_OF_THE_RIFT.hasKillcount());
		assertTrue(Activity.WINTERTODT.hasKillcount());
	}

	@Test
	public void haveNoRolesOrHardMode()
	{
		for (Activity activity : new Activity[]{Activity.CASTLE_WARS, Activity.GUARDIANS_OF_THE_RIFT,
			Activity.WINTERTODT})
		{
			assertFalse(activity.hasRoles());
			assertFalse(activity.hasHardMode());
			assertFalse(activity.isRaid());
			assertTrue(activity.roles(false).isEmpty());
			assertNull(activity.fixedComposition(4, false));
		}
	}
}
