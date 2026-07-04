package net.osparty.history;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import net.osparty.model.Party;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers {@link PartyHistoryService}: newest-first ordering, host/join flag, the
 * configurable cap, de-duplication by party id, and persistence across a reopen.
 */
public class PartyHistoryServiceTest
{
	private File dir;
	private int limit;

	@Before
	public void setUp() throws Exception
	{
		dir = Files.createTempDirectory("osparty-history-test").toFile();
		limit = 50;
	}

	private PartyHistoryService open()
	{
		return new PartyHistoryService(dir, () -> limit);
	}

	private static Party party(String id, String activity, String host)
	{
		Party p = new Party();
		p.setId(id);
		p.setActivity(activity);
		p.setHost(host);
		return p;
	}

	@Test
	public void recordsNewestFirstWithRole()
	{
		PartyHistoryService history = open();
		history.record(party("1", "cox", "Alice"), false);
		history.record(party("2", "toa", "Bob"), true);

		List<PartyHistoryEntry> list = history.list();
		assertEquals(2, list.size());
		assertEquals("2", list.get(0).getPartyId());
		assertTrue("hosted flag preserved", list.get(0).isHosted());
		assertFalse(list.get(1).isHosted());
		assertEquals("Alice", list.get(1).getHost());
	}

	@Test
	public void dedupesSamePartyId()
	{
		PartyHistoryService history = open();
		history.record(party("1", "cox", "Alice"), false);
		history.record(party("1", "cox", "Alice"), false); // e.g. resume after restart

		assertEquals(1, history.list().size());
	}

	@Test
	public void enforcesCap()
	{
		limit = 3;
		PartyHistoryService history = open();
		for (int i = 0; i < 10; i++)
		{
			history.record(party("p" + i, "nex", "Host" + i), false);
		}

		List<PartyHistoryEntry> list = history.list();
		assertEquals(3, list.size());
		// Newest three survive (p9, p8, p7).
		assertEquals("p9", list.get(0).getPartyId());
		assertEquals("p7", list.get(2).getPartyId());
	}

	@Test
	public void persistsAcrossReopenAndRetrims()
	{
		PartyHistoryService history = open();
		for (int i = 0; i < 5; i++)
		{
			history.record(party("p" + i, "nex", "Host" + i), false);
		}
		assertEquals(5, history.list().size());

		// Lower the cap and reopen: load() should trim to the new limit.
		limit = 2;
		PartyHistoryService reopened = open();
		List<PartyHistoryEntry> list = reopened.list();
		assertEquals(2, list.size());
		assertEquals("p4", list.get(0).getPartyId());
	}

	@Test
	public void clearEmptiesHistory()
	{
		PartyHistoryService history = open();
		history.record(party("1", "cox", "Alice"), false);
		history.clear();

		assertTrue(history.list().isEmpty());
		assertTrue("clear persists", open().list().isEmpty());
	}
}
