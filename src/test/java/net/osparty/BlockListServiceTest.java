package net.osparty;

import com.google.gson.Gson;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import net.osparty.model.Member;
import net.osparty.model.Party;
import net.osparty.store.JsonPartyStore;
import net.osparty.store.PartyStore;
import net.osparty.store.PlayerFlag;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Exercises {@link PlayerFlagService} logic (via {@link BlockListService}) over a real
 * {@link JsonPartyStore}: name/hash matching, persistence across a reopen, name-change
 * rename and hash backfill, and party-level matching.
 */
public class BlockListServiceTest
{
	private File dir;
	private PartyStore store;
	private BlockListService blocks;

	@Before
	public void setUp() throws Exception
	{
		dir = Files.createTempDirectory("osparty-store-test").toFile();
		store = new JsonPartyStore(dir, new Gson());
		blocks = new BlockListService(store);
	}

	@After
	public void tearDown()
	{
		if (store != null)
		{
			store.close();
		}
	}

	@Test
	public void togglesByNameWhenHashUnknown()
	{
		assertFalse(blocks.isBlocked(0L, "Zezima"));
		blocks.toggle(0L, "Zezima");
		assertTrue(blocks.isBlocked("Zezima"));
		assertTrue("case/whitespace-insensitive", blocks.isBlocked(0L, "  zezima "));

		blocks.toggle(0L, "Zezima");
		assertFalse(blocks.isBlocked("Zezima"));
	}

	@Test
	public void persistsAcrossReopen()
	{
		blocks.toggle(123L, "Durial321");
		store.close();

		PartyStore reopened = new JsonPartyStore(dir, new Gson());
		try
		{
			BlockListService reloaded = new BlockListService(reopened);
			assertTrue(reloaded.isBlocked(123L, "Durial321"));
			// Matches by hash even under a new name (survives a rename).
			assertTrue(reloaded.isBlocked(123L, "NewName"));
		}
		finally
		{
			reopened.close();
		}
	}

	@Test
	public void observeRenamesByHash()
	{
		blocks.toggle(55L, "OldName");
		blocks.observe(55L, "FreshName");

		assertTrue(blocks.isBlocked(55L, "FreshName"));
		assertFalse("old name no longer matches by name", blocks.isBlocked(0L, "OldName"));
		assertEquals(1, blocks.entries().size());
	}

	@Test
	public void observeBackfillsHashOntoNameOnlyEntry()
	{
		blocks.toggle(0L, "Ghostblade"); // hash unknown
		blocks.observe(77L, "Ghostblade"); // now we learn the hash

		assertTrue(blocks.isBlocked(77L, "Ghostblade"));
		PlayerFlag only = blocks.entries().get(0);
		assertEquals(77L, only.getAccountHash());
		assertEquals(1, blocks.entries().size());
	}

	@Test
	public void handlesNegativeAccountHash()
	{
		// RuneLite account hashes span the full signed-long range; negatives must be
		// treated as real, not folded into the "unknown" (-1) bucket.
		long negative = -8_234_567_890_123_456L;
		blocks.toggle(negative, "protodefend");

		assertTrue(blocks.isBlocked(negative, "protodefend"));
		assertEquals("stored as a real hash, not unknown", negative, blocks.entries().get(0).getAccountHash());

		// Survives the player renaming (matches by the negative hash).
		blocks.observe(negative, "ProtoRenamed");
		assertTrue(blocks.isBlocked(negative, "ProtoRenamed"));
		assertFalse(blocks.isBlocked(0L, "protodefend"));
		assertEquals(1, blocks.entries().size());
	}

	@Test
	public void backfillsNegativeHashOntoNameOnlyEntry()
	{
		blocks.toggle(0L, "protodefend"); // blocked before the host's hash was known
		blocks.observe(-42L, "protodefend"); // ad arrives carrying a negative hash

		assertEquals(-42L, blocks.entries().get(0).getAccountHash());
		assertEquals(1, blocks.entries().size());
	}

	@Test
	public void hasAnyBlockedMatchesHostByHash()
	{
		blocks.toggle(999L, "BadHost");

		Party party = new Party();
		party.setHost("BadHost");
		party.setMembers(Collections.singletonList(new Member("BadHost", 999L)));
		assertTrue(blocks.hasAnyBlocked(party));

		Party renamedHost = new Party();
		renamedHost.setHost("BadHostRenamed");
		renamedHost.setMembers(Arrays.asList(new Member("BadHostRenamed", 999L)));
		assertTrue("still blocked after the host renamed", blocks.hasAnyBlocked(renamedHost));

		Party clean = new Party();
		clean.setHost("GoodHost");
		clean.setMembers(Collections.singletonList(new Member("GoodHost", 1L)));
		assertFalse(blocks.hasAnyBlocked(clean));
	}
}
