package net.osparty.tools;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** What counts as a {@code !party} share line, and what of the host's own words is kept from it. */
public class PartyShareTest
{
	@Test
	public void triggerAloneIsAShare()
	{
		assertTrue(PartyShare.isShare("!party"));
	}

	@Test
	public void caseDoesNotMatter()
	{
		// The game reformats sent chat, so the line rarely comes back in the case it was typed in.
		assertTrue(PartyShare.isShare("!Party"));
		assertTrue(PartyShare.isShare("!PARTY need one more"));
	}

	@Test
	public void hostsOwnWordsMayFollow()
	{
		assertTrue(PartyShare.isShare("!party experienced only"));
	}

	@Test
	public void triggerMustBeAWordOfItsOwn()
	{
		assertFalse(PartyShare.isShare("!partytime"));
		assertFalse(PartyShare.isShare("!part"));
	}

	@Test
	public void triggerMustStartTheLine()
	{
		assertFalse(PartyShare.isShare("join my !party"));
	}

	@Test
	public void nullAndEmptyAreNotShares()
	{
		assertFalse(PartyShare.isShare(null));
		assertFalse(PartyShare.isShare(""));
	}

	@Test
	public void tailIsTheHostsWords()
	{
		assertEquals("need 1 more", PartyShare.tailOf("!party need 1 more"));
	}

	@Test
	public void tailOfBareTriggerIsEmpty()
	{
		assertEquals("", PartyShare.tailOf("!party"));
	}
}
