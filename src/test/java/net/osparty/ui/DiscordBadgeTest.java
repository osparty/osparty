package net.osparty.ui;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Badges render in fixed priority order (developer, content creator, beta tester, backer)
 * regardless of wire order, and unknown badge strings from a newer server are skipped.
 */
public class DiscordBadgeTest
{
	@Test
	public void ordersByPriorityNotWireOrder()
	{
		assertEquals(
			List.of(DiscordBadge.DEVELOPER, DiscordBadge.BETA_TESTER, DiscordBadge.BACKER),
			DiscordBadge.fromWire(List.of("backer", "beta_tester", "developer")));
	}

	@Test
	public void skipsUnknownBadges()
	{
		assertEquals(
			List.of(DiscordBadge.CONTENT_CREATOR),
			DiscordBadge.fromWire(List.of("celebrity", "content_creator")));
	}

	@Test
	public void emptyAndNullYieldNothing()
	{
		assertTrue(DiscordBadge.fromWire(null).isEmpty());
		assertTrue(DiscordBadge.fromWire(List.of()).isEmpty());
	}
}
