package net.osparty.ui;

import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import net.osparty.model.Advertisement;
import net.osparty.model.Member;
import net.osparty.service.PlayerFlagService;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Favorites tab matches a whole party, not just its host, and says on each card who put it in the
 * list — so a friend or favourite sitting in someone else's party is both found and explained.
 */
public class FavoritesMatchTest
{
	private static Advertisement ad(String host, String... members)
	{
		Advertisement ad = new Advertisement();
		ad.setId("p1");
		ad.setHost(host);
		Member[] listed = new Member[members.length];
		for (int i = 0; i < members.length; i++)
		{
			// Id-less, as an older server reports them: matching then falls back to the name.
			listed[i] = new Member(members[i]);
		}
		ad.setMembers(List.of(listed));
		return ad;
	}

	/** Friends are known by name only: the client's list carries no player ids. */
	private static List<String> matching(Advertisement ad, String... flaggedNames)
	{
		Set<String> flagged = Set.of(flaggedNames);
		BiPredicate<String, String> byName = (id, name) -> flagged.contains(PlayerFlagService.normalize(name));
		return FavoritesPanel.matches(ad, byName);
	}

	@Test
	public void matchesAMemberWhoIsNotTheHost()
	{
		assertEquals(List.of("Bob"), matching(ad("OldHost", "OldHost", "Bob"), "bob"));
	}

	@Test
	public void namesTheHostFirstAndOnlyOnce()
	{
		// The host is listed among the members too, so a naive walk would name them twice.
		assertEquals(List.of("Zezima", "Bob"), matching(ad("Zezima", "Zezima", "Bob"), "zezima", "bob"));
	}

	@Test
	public void matchesNobodyWhenNoOneIsFlagged()
	{
		assertTrue(matching(ad("Host", "Host", "Bob")).isEmpty());
	}

	@Test
	public void noteSaysWhetherTheyHostOrJustSitIn()
	{
		Advertisement party = ad("Zezima", "Zezima", "Bob");

		assertEquals("Favorite: Zezima (host)", FavoritesPanel.note("Favorite", party, List.of("Zezima")));
		assertEquals("Friend: Bob (in party)", FavoritesPanel.note("Friend", party, List.of("Bob")));
	}

	@Test
	public void notePluralisesTheLabelAndCapsTheNames()
	{
		Advertisement party = ad("Zezima", "Zezima", "Bob", "Amy", "Cat");

		assertEquals("Friends: Zezima (host), Bob (in party) +2 more",
			FavoritesPanel.note("Friend", party, List.of("Zezima", "Bob", "Amy", "Cat")));
	}
}
