package net.osparty.model;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Theatre of Blood composition: fixed by party size in both modes (HMT is laid out
 * exactly like normal ToB, just with its own role set), with a three-man's lone freezer
 * getting the combined Freeze role instead of a north/south split.
 */
public class TheatreOfBloodRolesTest
{
	private static final Activity TOB = Activity.THEATRE_OF_BLOOD;

	@Test
	public void bothModesHaveAFixedComposition()
	{
		assertTrue(TOB.hasFixedComposition());
		assertFalse(TOB.hasFlexibleRoles());
	}

	@Test
	public void threeManCombinesTheFreezersIntoOneRole()
	{
		assertEquals(Arrays.asList(Role.TOB_MELEE, Role.TOB_RANGED, Role.TOB_FRZ),
			TOB.fixedComposition(3, false));
		assertEquals(Arrays.asList(Role.TOB_HM_MELEE, Role.TOB_HM_RANGED, Role.TOB_HM_FRZ),
			TOB.fixedComposition(3, true));
	}

	@Test
	public void fourAndFiveManSplitTheFreezersNorthAndSouth()
	{
		assertEquals(Arrays.asList(Role.TOB_MELEE, Role.TOB_RANGED, Role.TOB_NFRZ, Role.TOB_SFRZ),
			TOB.fixedComposition(4, false));
		assertEquals(Arrays.asList(Role.TOB_MELEE, Role.TOB_MELEE, Role.TOB_RANGED,
			Role.TOB_NFRZ, Role.TOB_SFRZ), TOB.fixedComposition(5, false));
	}

	@Test
	public void hardModeFollowsNormalModeAtEverySize()
	{
		for (int size = TOB.getMinPartySize(); size <= TOB.getMaxPartySize(); size++)
		{
			List<Role> normal = TOB.fixedComposition(size, false);
			List<Role> hard = TOB.fixedComposition(size, true);
			assertEquals(size, normal.size());
			assertEquals(normal.size(), hard.size());
			for (int i = 0; i < normal.size(); i++)
			{
				// Same slot, same display name - only the mode-specific id differs.
				assertEquals(normal.get(i).getDisplayName(), hard.get(i).getDisplayName());
				assertFalse(normal.get(i).getId().equals(hard.get(i).getId()));
			}
		}
	}

	@Test
	public void myRoleOptionsFollowThePartySize()
	{
		assertEquals(Arrays.asList(Role.TOB_MELEE, Role.TOB_RANGED, Role.TOB_FRZ), TOB.roles(false, 3));
		assertEquals(Arrays.asList(Role.TOB_HM_MELEE, Role.TOB_HM_RANGED, Role.TOB_HM_NFRZ,
			Role.TOB_HM_SFRZ), TOB.roles(true, 5));
	}

	@Test
	public void freezeRolesAreInterchangeableWithinAMode()
	{
		assertTrue(Role.TOB_NFRZ.canFill(Role.TOB_FRZ.getId()));
		assertTrue(Role.TOB_FRZ.canFill(Role.TOB_SFRZ.getId()));
		assertTrue(Role.TOB_HM_FRZ.canFill(Role.TOB_HM_NFRZ.getId()));
		// Never across modes, and never across roles.
		assertFalse(Role.TOB_FRZ.canFill(Role.TOB_HM_FRZ.getId()));
		assertFalse(Role.TOB_FRZ.canFill(Role.TOB_MELEE.getId()));
		assertTrue(Role.TOB_MELEE.canFill(Role.TOB_MELEE.getId()));
	}
}
