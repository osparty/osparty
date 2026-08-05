package net.osparty.tools;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.client.plugins.raids.RaidRoom;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The rotation the scanner advertises, driven from room readings rather than from a scene.
 *
 * <p>What these are really guarding is that a room the scanner filled in never gets counted as a room it
 * saw: the whole raid reads as known the moment the rotation is filled, and a solver that believes the
 * raid is fully known stops solving — leaving whatever it guessed wrong in the layout for good, which is
 * what puts the same boss in it twice.
 */
public class CoxRaidScannerTest
{
	private CoxRaidScanner scanner;
	private List<Object> layouts;
	private List<List<RaidRoom>> rotations;

	@Before
	@SuppressWarnings("unchecked")
	public void setUp() throws Exception
	{
		Constructor<CoxRaidScanner> ctor = CoxRaidScanner.class.getDeclaredConstructor(Client.class);
		ctor.setAccessible(true);
		scanner = ctor.newInstance(mock(Client.class));
		layouts = (List<Object>) field("layouts").get(scanner);
		rotations = (List<List<RaidRoom>>) field("ROTATIONS").get(null);
	}

	private static Field field(String name) throws Exception
	{
		Field f = CoxRaidScanner.class.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}

	/** Pin the scanner to one known layout, as a successful scout would have. */
	private void useLayout(Object layout) throws Exception
	{
		field("solvedLayout").set(scanner, layout);
	}

	@SuppressWarnings("unchecked")
	private static List<int[]> ordered(Object layout) throws Exception
	{
		Field f = layout.getClass().getDeclaredField("ordered");
		f.setAccessible(true);
		return (List<int[]>) f.get(layout);
	}

	private static List<Integer> combatSlots(Object layout) throws Exception
	{
		List<Integer> slots = new ArrayList<>();
		for (int[] entry : ordered(layout))
		{
			if ((char) entry[1] == 'C')
			{
				slots.add(entry[0]);
			}
		}
		return slots;
	}

	/** The first layout with at least {@code atLeast} combat rooms. */
	private Object layoutWithCombats(int atLeast) throws Exception
	{
		for (Object layout : layouts)
		{
			if (combatSlots(layout).size() >= atLeast)
			{
				return layout;
			}
		}
		throw new AssertionError("no layout with " + atLeast + " combat rooms");
	}

	private static List<String> repeatedRooms(String rendered)
	{
		List<String> repeated = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String room : rendered.split(", "))
		{
			if (!room.startsWith("Unknown") && !seen.add(room))
			{
				repeated.add(room);
			}
		}
		return repeated;
	}

	/** Two rooms seen is enough to commit to a rotation and fill the rest in from it. */
	@Test
	public void unseenRoomsAreFilledFromTheRotation() throws Exception
	{
		Object layout = layoutWithCombats(4);
		List<Integer> slots = combatSlots(layout);
		List<RaidRoom> rotation = rotations.get(0);
		useLayout(layout);

		scanner.observe(slots.get(0), rotation.get(0));
		scanner.observe(slots.get(1), rotation.get(1));
		scanner.resolve();

		String rendered = scanner.layout();
		assertNotNull(rendered);
		assertTrue("expected the rotation to name the unseen rooms, got: " + rendered,
			rendered.contains(rotation.get(2).getName()) && rendered.contains(rotation.get(3).getName()));
		assertEquals(Collections.emptyList(), repeatedRooms(rendered));
	}

	/**
	 * The bug this class exists for. Rooms that no single raid could have put where we saw them mean the
	 * scan is holding two raids at once — the scout has to go, not get rendered with a boss in it twice.
	 */
	@Test
	public void aScoutThatFitsNoRotationIsDiscarded() throws Exception
	{
		Object layout = layoutWithCombats(4);
		List<Integer> slots = combatSlots(layout);
		List<RaidRoom> rotation = rotations.get(0);
		useLayout(layout);

		scanner.observe(slots.get(0), rotation.get(0));
		scanner.observe(slots.get(1), rotation.get(1));
		scanner.resolve();
		assertNotNull(scanner.layout());

		// The same boss cannot stand in two rooms of one raid.
		scanner.observe(slots.get(2), rotation.get(0));
		scanner.resolve();

		assertNull("a scan that fits no rotation must be thrown away, not advertised", scanner.layout());
		boolean[] seen = (boolean[]) field("observed").get(scanner);
		for (boolean slot : seen)
		{
			assertFalse("discarding the scout clears what it thought it had seen", slot);
		}
	}

	/** And the rescout that follows lands a clean layout. */
	@Test
	public void rescoutingAfterADiscardGivesACleanLayout() throws Exception
	{
		Object layout = layoutWithCombats(4);
		List<Integer> slots = combatSlots(layout);
		List<RaidRoom> rotation = rotations.get(0);
		useLayout(layout);
		scanner.observe(slots.get(0), rotation.get(0));
		scanner.observe(slots.get(1), rotation.get(1));
		scanner.resolve();
		scanner.observe(slots.get(2), rotation.get(0));
		scanner.resolve();
		assertNull(scanner.layout());

		useLayout(layout);
		for (int i = 0; i < slots.size(); i++)
		{
			scanner.observe(slots.get(i), rotation.get(i));
		}
		scanner.resolve();

		String rendered = scanner.layout();
		assertNotNull(rendered);
		assertEquals(Collections.emptyList(), repeatedRooms(rendered));
	}

	/** A slot the rotation filled must not stop the solver from re-deriving the rotation. */
	@Test
	public void filledRoomsAreNotCountedAsSeen() throws Exception
	{
		Object layout = layoutWithCombats(4);
		List<Integer> slots = combatSlots(layout);
		List<RaidRoom> rotation = rotations.get(1);
		useLayout(layout);

		scanner.observe(slots.get(0), rotation.get(0));
		scanner.observe(slots.get(1), rotation.get(1));
		scanner.resolve();

		// Every combat slot now holds a room, but only two of them were ever read out of the scene.
		Field observed = field("observed");
		boolean[] seen = (boolean[]) observed.get(scanner);
		int seenCount = 0;
		for (int slot : slots)
		{
			if (seen[slot])
			{
				seenCount++;
			}
		}
		assertEquals("the rotation's own answers must not count as observations", 2, seenCount);
		assertFalse("the third combat room was filled in, not seen", seen[slots.get(2)]);
	}

	/**
	 * The property the whole pipeline has to hold: however the raid is walked, a boss never appears twice.
	 * Covers the shift {@code f58732e} fixed as well as the stale guess this change fixes.
	 */
	@Test
	public void noRotationEverRendersABossTwice() throws Exception
	{
		int checked = 0;
		for (int li = 0; li < layouts.size(); li++)
		{
			Object layout = layouts.get(li);
			List<Integer> slots = combatSlots(layout);
			if (slots.isEmpty())
			{
				continue;
			}
			for (int ri = 0; ri < rotations.size(); ri++)
			{
				for (int offset = 0; offset < 8; offset++)
				{
					List<Integer> walk = new ArrayList<>(slots);
					Random rng = new Random(li * 1000L + ri * 100L + offset);
					for (int trial = 0; trial < 10; trial++)
					{
						Collections.shuffle(walk, rng);
						setUp();
						useLayout(layout);

						for (int step = 0; step < walk.size(); step++)
						{
							int slot = walk.get(step);
							scanner.observe(slot, rotations.get(ri).get((offset + slots.indexOf(slot)) % 8));
							scanner.resolve();
							String rendered = scanner.layout();
							if (rendered == null)
							{
								continue;
							}
							checked++;
							assertEquals("layout#" + li + " rotation#" + ri + " offset=" + offset
									+ " walk=" + walk.subList(0, step + 1) + " -> " + rendered,
								Collections.emptyList(), repeatedRooms(rendered));
						}
					}
				}
			}
		}
		assertTrue("expected the sweep to actually render layouts", checked > 1000);
	}

	/** A re-rolled raid is a different party, and none of the old raid's rooms survive it. */
	@Test
	public void aNewRaidPartyDropsTheOldScout() throws Exception
	{
		Object layout = layoutWithCombats(4);
		List<Integer> slots = combatSlots(layout);
		List<RaidRoom> rotation = rotations.get(0);
		useLayout(layout);
		scanner.observe(slots.get(0), rotation.get(0));
		scanner.observe(slots.get(1), rotation.get(1));
		scanner.resolve();
		assertNotNull(scanner.layout());

		scanner.onRaidPartyChanged(7);  // the id we were already in, first time we hear it
		assertNotNull("learning the party id is not a re-roll", scanner.layout());

		scanner.onRaidPartyChanged(9);
		assertNull(scanner.layout());
		boolean[] seen = (boolean[]) field("observed").get(scanner);
		for (boolean slot : seen)
		{
			assertFalse("a re-roll leaves nothing observed", slot);
		}
	}
}
