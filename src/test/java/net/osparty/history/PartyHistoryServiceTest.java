package net.osparty.history;

import com.google.gson.Gson;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import net.osparty.model.Member;
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
		return new PartyHistoryService(dir, () -> limit, new Gson());
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
	public void updateRosterAddsJoinersAndFlagsLeavers()
	{
		PartyHistoryService history = open();
		Party p = party("1", "cox", "Alice");
		p.setMembers(Arrays.asList(new Member("Alice", 1L)));
		history.record(p, true);

		// Bob joins.
		assertTrue(history.updateRoster("1", Arrays.asList(new Member("Alice", 1L), new Member("Bob", 2L))));
		PartyHistoryEntry entry = history.list().get(0);
		assertEquals("both present", 2, entry.getSize());
		assertEquals(2, entry.getMembers().size());
		assertTrue(byName(entry, "Bob").isPresent());

		// Bob leaves: kept in the list, flagged, and no longer counted in size.
		assertTrue(history.updateRoster("1", Arrays.asList(new Member("Alice", 1L))));
		entry = history.list().get(0);
		assertEquals("only Alice present", 1, entry.getSize());
		assertEquals("Bob is retained, not removed", 2, entry.getMembers().size());
		HistoryMember bob = byName(entry, "Bob");
		assertFalse("Bob flagged as left", bob.isPresent());
		assertTrue("leftAt stamped", bob.getLeftAt() > 0);
		assertTrue("Alice still present", byName(entry, "Alice").isPresent());
	}

	@Test
	public void updateRosterClearsLeftAtOnRejoin()
	{
		PartyHistoryService history = open();
		Party p = party("1", "cox", "Alice");
		p.setMembers(Arrays.asList(new Member("Alice", 1L), new Member("Bob", 2L)));
		history.record(p, true);

		history.updateRoster("1", Arrays.asList(new Member("Alice", 1L)));      // Bob leaves
		assertFalse(byName(history.list().get(0), "Bob").isPresent());

		history.updateRoster("1", Arrays.asList(new Member("Alice", 1L), new Member("Bob", 2L))); // Bob rejoins
		HistoryMember bob = byName(history.list().get(0), "Bob");
		assertTrue("rejoin clears the left flag", bob.isPresent());
		assertEquals("leftAt reset", 0, bob.getLeftAt());
	}

	@Test
	public void updateRosterUpgradesUnknownHashByName()
	{
		PartyHistoryService history = open();
		Party p = party("1", "cox", "Alice");
		p.setMembers(Arrays.asList(new Member("Alice", 1L), new Member("Bob", 0L))); // Bob's hash not yet synced
		history.record(p, true);

		// Same Bob, now with a resolved hash — matched by name, not recorded as a second member.
		history.updateRoster("1", Arrays.asList(new Member("Alice", 1L), new Member("Bob", 2L)));
		PartyHistoryEntry entry = history.list().get(0);
		assertEquals("no duplicate Bob", 2, entry.getMembers().size());
		assertEquals("hash upgraded", 2L, byName(entry, "Bob").getAccountHash());
	}

	@Test
	public void updateRosterIsNoOpWhenUnchangedOrEmptyOrUnknown()
	{
		PartyHistoryService history = open();
		Party p = party("1", "cox", "Alice");
		List<Member> roster = Arrays.asList(new Member("Alice", 1L), new Member("Bob", 2L));
		p.setMembers(roster);
		history.record(p, true);

		assertFalse("same roster is not rewritten", history.updateRoster("1", roster));
		assertFalse("empty roster must not flag everyone left", history.updateRoster("1", List.of()));
		assertFalse("unknown party id is ignored", history.updateRoster("nope", roster));

		// The recorded roster survived the no-ops intact and everyone is still present.
		PartyHistoryEntry entry = history.list().get(0);
		assertEquals(2, entry.getMembers().size());
		assertTrue(byName(entry, "Alice").isPresent());
		assertTrue(byName(entry, "Bob").isPresent());
	}

	@Test
	public void updateRosterPersistsAcrossReopen()
	{
		PartyHistoryService history = open();
		Party p = party("1", "cox", "Alice");
		p.setMembers(Arrays.asList(new Member("Alice", 1L)));
		history.record(p, true);
		history.updateRoster("1", Arrays.asList(new Member("Alice", 1L), new Member("Bob", 2L)));
		history.updateRoster("1", Arrays.asList(new Member("Alice", 1L))); // Bob leaves

		PartyHistoryEntry reloaded = open().list().get(0);
		assertEquals("left member persisted", 2, reloaded.getMembers().size());
		assertFalse("left flag persisted", byName(reloaded, "Bob").isPresent());
		assertTrue(byName(reloaded, "Bob").getLeftAt() > 0);
	}

	@Test
	public void closePartyStampsPresentMembersAsLeft()
	{
		PartyHistoryService history = open();
		Party p = party("1", "cox", "Alice");
		p.setMembers(Arrays.asList(new Member("Alice", 1L), new Member("Bob", 2L)));
		history.record(p, true);
		history.updateRoster("1", Arrays.asList(new Member("Alice", 1L))); // Bob leaves early

		long bobLeftAt = byName(history.list().get(0), "Bob").getLeftAt();

		assertTrue(history.closeParty("1", 9_999L));
		PartyHistoryEntry entry = history.list().get(0);
		assertFalse("host no longer shown present", byName(entry, "Alice").isPresent());
		assertEquals("host stamped at close time", 9_999L, byName(entry, "Alice").getLeftAt());
		assertEquals("Bob's earlier leftAt is untouched", bobLeftAt, byName(entry, "Bob").getLeftAt());
	}

	@Test
	public void closePartyIsNoOpWhenAlreadyEndedOrUnknown()
	{
		PartyHistoryService history = open();
		Party p = party("1", "cox", "Alice");
		p.setMembers(Arrays.asList(new Member("Alice", 1L)));
		history.record(p, true);

		assertTrue(history.closeParty("1", 9_999L));
		assertFalse("already ended — nothing present to stamp", history.closeParty("1", 12_345L));
		assertFalse("unknown party id ignored", history.closeParty("nope", 9_999L));

		// A closed row persists as ended across a reopen.
		assertFalse(byName(open().list().get(0), "Alice").isPresent());
	}

	private static HistoryMember byName(PartyHistoryEntry entry, String name)
	{
		return entry.getMembers().stream()
			.filter(m -> name.equals(m.getName()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no member named " + name));
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
