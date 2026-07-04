package net.osparty.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Barbarian Assault flexible composition: a team of five, one of each of the four
 * roles plus one "extra" that doubles any role, and a role capped at two so once it's
 * been picked twice it's no longer offered.
 */
public class BarbarianAssaultRolesTest
{
	private static final Activity BA = Activity.BARBARIAN_ASSAULT;
	private static final String ATT = Role.BA_ATTACKER.getId();
	private static final String DEF = Role.BA_DEFENDER.getId();
	private static final String COL = Role.BA_COLLECTOR.getId();
	private static final String HEAL = Role.BA_HEALER.getId();

	@Test
	public void teamIsFiveOnly()
	{
		assertEquals(5, BA.getMinPartySize());
		assertEquals(5, BA.getMaxPartySize());
	}

	@Test
	public void hasFlexibleRolesWithCapOfTwo()
	{
		assertTrue(BA.hasRoles());
		assertTrue(BA.hasFlexibleRoles());
		assertEquals(2, BA.roleCap());
	}

	@Test
	public void emptyTeamNeedsEveryRole()
	{
		List<String> needed = BA.flexibleNeededRoles(Collections.emptyList(), 5);
		assertEquals(Arrays.asList(ATT, DEF, COL, HEAL), needed);
	}

	@Test
	public void lastSlotMayBeAnyRoleUpToCap()
	{
		// Four distinct roles taken, one slot left: any of the four may still double up.
		List<String> needed = BA.flexibleNeededRoles(Arrays.asList(ATT, DEF, COL, HEAL), 5);
		assertEquals(Arrays.asList(ATT, DEF, COL, HEAL), needed);
	}

	@Test
	public void roleDroppedOncePickedTwice()
	{
		// Attacker taken twice spends the extra slot: it's capped out, and with just two slots
		// left for the two still-empty roles there's no room to double defender either.
		List<String> needed = BA.flexibleNeededRoles(Arrays.asList(ATT, ATT, DEF), 5);
		assertEquals(Arrays.asList(COL, HEAL), needed);
	}

	@Test
	public void doublingIsHeldBackWhileBaseRolesStillEmpty()
	{
		// Two slots left but three empty base roles: no room for an extra yet, so the one
		// already-filled role (defender) is not offered a second time.
		List<String> needed = BA.flexibleNeededRoles(Arrays.asList(ATT, DEF, DEF), 5);
		assertEquals(Arrays.asList(COL, HEAL), needed);
	}

	@Test
	public void fullTeamNeedsNothing()
	{
		List<String> needed = BA.flexibleNeededRoles(Arrays.asList(ATT, DEF, COL, HEAL, ATT), 5);
		assertTrue(needed.isEmpty());
	}

	@Test
	public void fillIsSearchOnlyNotAComposedRole()
	{
		// "Fill / Any" is the search wildcard, never a composition slot the host lays out.
		assertEquals(Role.BA_FILL, BA.anyRole(false));
		assertTrue(BA.roles(false).contains(Role.BA_ATTACKER));
		assertTrue(!BA.roles(false).contains(Role.BA_FILL));
		assertTrue(BA.filterRoles(false).contains(Role.BA_FILL));
	}
}
