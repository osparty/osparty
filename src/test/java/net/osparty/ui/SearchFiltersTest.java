package net.osparty.ui;

import java.util.EnumSet;
import java.util.Set;
import net.osparty.model.Activity;
import net.osparty.model.Advertisement;
import net.osparty.model.LootRule;
import net.runelite.http.api.worlds.WorldRegion;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The filter predicate the party list and the matchmaking watcher share. A party offered in-game has
 * to be one the panel would have shown, so these are the rules that keep the two honest.
 */
public class SearchFiltersTest
{
	private static final WorldRegion[] KNOWN = {
		WorldRegion.UNITED_KINGDOM, WorldRegion.GERMANY, WorldRegion.AUSTRALIA,
	};

	/** Says yes to everything, so each test can fail exactly one thing. */
	private static class Ctx implements SearchFilters.Context
	{
		boolean blocked;
		boolean meetsIronman = true;
		boolean kcBelow;
		boolean roles = true;
		boolean text = true;
		Integer world = 302;
		WorldRegion region = WorldRegion.UNITED_KINGDOM;
		Integer ping = 40;

		@Override
		public boolean blocked(Advertisement ad)
		{
			return blocked;
		}

		@Override
		public boolean meetsIronmanRule(Advertisement ad)
		{
			return meetsIronman;
		}

		@Override
		public boolean killcountBelow(Advertisement ad)
		{
			return kcBelow;
		}

		@Override
		public boolean matchesRoles(Advertisement ad, Activity activity)
		{
			return roles;
		}

		@Override
		public boolean matchesText(Advertisement ad, Activity activity, String query)
		{
			return text;
		}

		@Override
		public Integer worldOf(Advertisement ad)
		{
			return world;
		}

		@Override
		public WorldRegion regionOf(int world)
		{
			return region;
		}

		@Override
		public Integer pingOf(int world)
		{
			return ping;
		}
	}

	private static Advertisement ad()
	{
		Advertisement ad = new Advertisement();
		ad.setId("ad-1");
		ad.setActivity(Activity.THEATRE_OF_BLOOD.getId());
		ad.setHost("Zezima");
		ad.setSize(2);
		ad.setCapacity(5);
		ad.setWorld("302");
		ad.setLootRule(LootRule.SPLIT.name());
		return ad;
	}

	private static SearchFilters.Builder open()
	{
		return SearchFilters.builder()
			.activities(EnumSet.allOf(Activity.class))
			.regions(allKnown(), KNOWN);
	}

	private static Set<WorldRegion> allKnown()
	{
		Set<WorldRegion> regions = EnumSet.noneOf(WorldRegion.class);
		for (WorldRegion region : KNOWN)
		{
			regions.add(region);
		}
		return regions;
	}

	@Test
	public void anOpenPartyPassesAnUnsetFilter()
	{
		assertTrue(open().build().joinable(ad(), new Ctx()));
		assertTrue(open().build().matches(ad(), Activity.THEATRE_OF_BLOOD, new Ctx()));
	}

	@Test
	public void aFullPartyIsNeverJoinable()
	{
		Advertisement full = ad();
		full.setSize(5);
		assertFalse(open().build().joinable(full, new Ctx()));
	}

	@Test
	public void aBlockedHostIsHiddenUnlessAskedFor()
	{
		Ctx ctx = new Ctx();
		ctx.blocked = true;
		assertFalse(open().build().joinable(ad(), ctx));
		assertTrue(open().showBlocked(true).build().joinable(ad(), ctx));
	}

	@Test
	public void anUnselectedActivityIsExcluded()
	{
		SearchFilters coxOnly = open().activities(EnumSet.of(Activity.CHAMBERS_OF_XERIC)).build();
		assertFalse(coxOnly.matches(ad(), Activity.THEATRE_OF_BLOOD, new Ctx()));
	}

	@Test
	public void anUnknownActivityIsExcluded()
	{
		assertFalse(open().build().matches(ad(), null, new Ctx()));
	}

	@Test
	public void theLootFilterExcludesOtherRules()
	{
		assertFalse(open().loot(LootRule.FFA).build().matches(ad(), Activity.THEATRE_OF_BLOOD, new Ctx()));
		assertTrue(open().loot(LootRule.SPLIT).build().matches(ad(), Activity.THEATRE_OF_BLOOD, new Ctx()));
	}

	@Test
	public void theLearnerFilterCutsBothWays()
	{
		Advertisement learner = ad();
		learner.setLearner(true);
		SearchFilters only = open().learner(SearchFilters.Learner.ONLY).build();
		SearchFilters hide = open().learner(SearchFilters.Learner.HIDE).build();

		assertTrue(only.matches(learner, Activity.THEATRE_OF_BLOOD, new Ctx()));
		assertFalse(only.matches(ad(), Activity.THEATRE_OF_BLOOD, new Ctx()));
		assertFalse(hide.matches(learner, Activity.THEATRE_OF_BLOOD, new Ctx()));
		assertTrue(hide.matches(ad(), Activity.THEATRE_OF_BLOOD, new Ctx()));
	}

	@Test
	public void hideIneligibleAppliesTheKillcountAndIronmanRules()
	{
		SearchFilters hiding = open().hideIneligible(true).build();

		Ctx below = new Ctx();
		below.kcBelow = true;
		assertFalse(hiding.matches(ad(), Activity.THEATRE_OF_BLOOD, below));
		// The same party is shown when the player has not asked for ineligible ones to be hidden.
		assertTrue(open().build().matches(ad(), Activity.THEATRE_OF_BLOOD, below));

		Ctx wrongAccount = new Ctx();
		wrongAccount.meetsIronman = false;
		assertFalse(hiding.matches(ad(), Activity.THEATRE_OF_BLOOD, wrongAccount));
	}

	@Test
	public void aPendingKillcountCheckIsNotBelow()
	{
		Ctx pending = new Ctx();
		pending.kcBelow = false;
		assertTrue(open().hideIneligible(true).build().matches(ad(), Activity.THEATRE_OF_BLOOD, pending));
	}

	@Test
	public void aDeselectedRegionIsExcludedOnlyWhileTheFilterIsOn()
	{
		Ctx german = new Ctx();
		german.region = WorldRegion.GERMANY;

		Set<WorldRegion> withoutGermany = EnumSet.of(WorldRegion.UNITED_KINGDOM, WorldRegion.AUSTRALIA);
		SearchFilters filtered = SearchFilters.builder().regions(withoutGermany, KNOWN).build();
		assertFalse(filtered.matches(ad(), Activity.THEATRE_OF_BLOOD, german));

		// Every known region selected means the filter is off, so the same party passes.
		assertTrue(open().build().matches(ad(), Activity.THEATRE_OF_BLOOD, german));
	}

	@Test
	public void anUnmeasuredWorldIsNeverExcludedByThePingLimit()
	{
		Ctx unmeasured = new Ctx();
		unmeasured.ping = null;
		assertTrue(open().maxPing(50).build().matches(ad(), Activity.THEATRE_OF_BLOOD, unmeasured));

		Ctx slow = new Ctx();
		slow.ping = 200;
		assertFalse(open().maxPing(50).build().matches(ad(), Activity.THEATRE_OF_BLOOD, slow));
	}

	@Test
	public void aPartyWithNoWorldSurvivesTheRegionAndPingFilters()
	{
		Ctx worldless = new Ctx();
		worldless.world = null;
		SearchFilters strict = SearchFilters.builder()
			.regions(EnumSet.of(WorldRegion.AUSTRALIA), KNOWN)
			.maxPing(1)
			.build();
		assertTrue(strict.matches(ad(), Activity.THEATRE_OF_BLOOD, worldless));
	}

	@Test
	public void theTextAndRoleFiltersAreDelegated()
	{
		Ctx noRoleMatch = new Ctx();
		noRoleMatch.roles = false;
		assertFalse(open().build().matches(ad(), Activity.THEATRE_OF_BLOOD, noRoleMatch));

		Ctx noTextMatch = new Ctx();
		noTextMatch.text = false;
		assertFalse(open().text("zezima").build().matches(ad(), Activity.THEATRE_OF_BLOOD, noTextMatch));
		// An empty query never consults the context at all.
		assertTrue(open().text("   ").build().matches(ad(), Activity.THEATRE_OF_BLOOD, noTextMatch));
	}
}
