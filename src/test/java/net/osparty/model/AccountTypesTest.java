package net.osparty.model;

import net.runelite.api.vars.AccountType;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The account-type varbit maps to an ironman ruling every state can live with. What this really guards
 * is value 6: RuneLite's deprecated enum has no unranked-group-ironman, and reading that state as a
 * normal account is what locked unranked group ironmen out of ironman-only parties.
 */
public class AccountTypesTest
{
	@Test
	public void everyVarbitStateMapsToItsAccountType()
	{
		assertEquals(AccountType.NORMAL, AccountTypes.fromVarbit(0));
		assertEquals(AccountType.IRONMAN, AccountTypes.fromVarbit(1));
		assertEquals(AccountType.ULTIMATE_IRONMAN, AccountTypes.fromVarbit(2));
		assertEquals(AccountType.HARDCORE_IRONMAN, AccountTypes.fromVarbit(3));
		assertEquals(AccountType.GROUP_IRONMAN, AccountTypes.fromVarbit(4));
		assertEquals(AccountType.HARDCORE_GROUP_IRONMAN, AccountTypes.fromVarbit(5));
	}

	@Test
	public void anUnrankedGroupIronmanIsStillAGroupIronman()
	{
		assertEquals(AccountType.GROUP_IRONMAN, AccountTypes.fromVarbit(6));
		assertTrue(AccountTypes.isIronman(AccountTypes.fromVarbit(6)));
	}

	@Test
	public void everyIronmanVariantPassesTheIronmanOnlyRule()
	{
		for (int value = 1; value <= 6; value++)
		{
			assertTrue("varbit " + value, AccountTypes.isIronman(AccountTypes.fromVarbit(value)));
		}
	}

	@Test
	public void unknownStatesReadAsNormal()
	{
		assertEquals(AccountType.NORMAL, AccountTypes.fromVarbit(7));
		assertEquals(AccountType.NORMAL, AccountTypes.fromVarbit(-1));
		assertFalse(AccountTypes.isIronman(AccountTypes.fromVarbit(0)));
	}
}
