package net.osparty.service;

import com.google.gson.Gson;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import net.osparty.model.Member;
import net.osparty.model.Advertisement;
import net.osparty.store.JsonPartyStore;
import net.osparty.store.PartyStore;
import net.osparty.store.PlayerFlag;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Exercises {@link PlayerFlagService} logic (via {@link BlockListService}) over a real
 * {@link JsonPartyStore}: name/id matching, persistence across a reopen, name-change
 * rename and id backfill, party-level matching, and the migration off account hashes.
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
	public void togglesByNameWhenIdUnknown()
	{
		assertFalse(blocks.isBlocked(null, "Zezima"));
		blocks.toggle(null, "Zezima");
		assertTrue(blocks.isBlocked("Zezima"));
		assertTrue("case/whitespace-insensitive", blocks.isBlocked(null, "  zezima "));

		blocks.toggle(null, "Zezima");
		assertFalse(blocks.isBlocked("Zezima"));
	}

	@Test
	public void persistsAcrossReopen()
	{
		blocks.toggle("DUR0-0000-0321", "Durial321");
		store.close();

		PartyStore reopened = new JsonPartyStore(dir, new Gson());
		try
		{
			BlockListService reloaded = new BlockListService(reopened);
			assertTrue(reloaded.isBlocked("DUR0-0000-0321", "Durial321"));
			// Matches by id even under a new name (survives a rename).
			assertTrue(reloaded.isBlocked("DUR0-0000-0321", "NewName"));
		}
		finally
		{
			reopened.close();
		}
	}

	@Test
	public void observeRenamesById()
	{
		blocks.toggle("ID00-0000-0055", "OldName");
		blocks.observe("ID00-0000-0055", "FreshName");

		assertTrue(blocks.isBlocked("ID00-0000-0055", "FreshName"));
		assertFalse("old name no longer matches by name", blocks.isBlocked(null, "OldName"));
		assertEquals(1, blocks.entries().size());
	}

	@Test
	public void observeBackfillsIdOntoNameOnlyEntry()
	{
		blocks.toggle(null, "Ghostblade"); // id unknown
		blocks.observe("ID00-0000-0077", "Ghostblade"); // now we learn the id

		assertTrue(blocks.isBlocked("ID00-0000-0077", "Ghostblade"));
		PlayerFlag only = blocks.entries().get(0);
		assertEquals("ID00-0000-0077", only.getPlayerId());
		assertEquals(1, blocks.entries().size());
	}

	@Test
	public void aBlankIdIsUnknown()
	{
		blocks.toggle("", "protodefend");

		assertTrue(blocks.isBlocked("   ", "protodefend"));
		assertNull("stored as name-only, not under an empty id", blocks.entries().get(0).getPlayerId());
		assertEquals(1, blocks.entries().size());
	}

	@Test
	public void hasAnyBlockedMatchesHostById()
	{
		blocks.toggle("BAD0-HOST-0999", "BadHost");

		Advertisement ad = new Advertisement();
		ad.setHost("BadHost");
		ad.setMembers(Collections.singletonList(new Member("BadHost", "BAD0-HOST-0999")));
		assertTrue(blocks.hasAnyBlocked(ad));

		Advertisement renamedHost = new Advertisement();
		renamedHost.setHost("BadHostRenamed");
		renamedHost.setHostPlayerId("BAD0-HOST-0999");
		renamedHost.setMembers(Arrays.asList(new Member("BadHostRenamed", "BAD0-HOST-0999")));
		assertTrue("still blocked after the host renamed", blocks.hasAnyBlocked(renamedHost));

		Advertisement clean = new Advertisement();
		clean.setHost("GoodHost");
		clean.setMembers(Collections.singletonList(new Member("GoodHost", "GOOD-HOST-0001")));
		assertFalse(blocks.hasAnyBlocked(clean));
	}

	/**
	 * A flags file from before public ids was keyed by the raw account hash of every blocked player.
	 * Opening it must keep the blocks (by name, until each player is next seen and re-keyed by id) and
	 * rewrite the file without the hashes straight away.
	 */
	@Test
	public void openingAHashKeyedFileKeepsTheNamesAndDropsTheHashes() throws Exception
	{
		store.close();
		File flagsFile = new File(dir, "flags.json");
		Files.writeString(flagsFile.toPath(), "{\"version\":1,\"flags\":{\"BLOCK\":["
			+ "{\"accountHash\":424242,\"username\":\"durial321\"},"
			+ "{\"accountHash\":-1,\"username\":\"nameonly\"}]}}");

		store = new JsonPartyStore(dir, new Gson());
		blocks = new BlockListService(store);

		assertTrue("kept by name", blocks.isBlocked("Durial321"));
		assertTrue(blocks.isBlocked("NameOnly"));
		assertNull(blocks.entries().get(0).getPlayerId());
		assertFalse("hash gone from disk on open", Files.readString(flagsFile.toPath()).contains("424242"));

		// The next sighting re-keys the row by id, so a later rename still matches.
		blocks.observe("DUR0-0000-0321", "Durial321");
		assertTrue(blocks.isBlocked("DUR0-0000-0321", "SomebodyElse"));
	}
}
