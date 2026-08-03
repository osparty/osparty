package net.osparty.party;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The receiving half of partial live updates: an update carries only what changed, so folding it into what
 * we already held must leave everything else alone — and privacy has to clear fields explicitly, because
 * leaving them out no longer means anything.
 */
public class LivePartyStateMergeTest
{
	private final Gson gson = new Gson();

	@Test
	public void patchLeavesFieldsItDoesNotMention()
	{
		JsonObject full = LiveStateCodec.toWire(gson.toJsonTree(snapshot()).getAsJsonObject());
		JsonObject vitals = new JsonObject();
		vitals.addProperty("hp", 31);

		PlayerUpdate merged =
			gson.fromJson(LiveStateCodec.fromWire(LiveStateCodec.merge(full, vitals)), PlayerUpdate.class);

		assertEquals(31, merged.getCurrentHp());
		// Everything the patch was silent about survives it.
		assertEquals("Zezima", merged.getName());
		assertArrayEquals(new int[]{4151, 11802}, merged.getEquipment());
		assertArrayEquals(new int[]{385, 2434}, merged.getInventory());
		assertEquals(301, merged.getWorld());
	}

	@Test
	public void patchWithNoPriorStateIsTheWholePicture()
	{
		JsonObject vitals = new JsonObject();
		vitals.addProperty("hp", 12);

		PlayerUpdate merged =
			gson.fromJson(LiveStateCodec.fromWire(LiveStateCodec.merge(null, vitals)), PlayerUpdate.class);

		assertEquals(12, merged.getCurrentHp());
		assertNull(merged.getName());
	}

	@Test
	public void mergingDoesNotMutateEitherSide()
	{
		JsonObject base = LiveStateCodec.toWire(gson.toJsonTree(snapshot()).getAsJsonObject());
		JsonObject patch = new JsonObject();
		patch.addProperty("wd", 420);

		LiveStateCodec.merge(base, patch);

		assertEquals(301, base.get("wd").getAsInt());
		assertFalse(patch.has("n"));
	}

	/**
	 * The trap this design creates: privacy used to hide gear by omitting it, which now reads as "unchanged"
	 * and would leave peers looking at the last inventory they saw. The flag is what clears it instead.
	 */
	@Test
	public void privacyFlagsClearWhatTheSenderWithheld()
	{
		PlayerUpdate held = snapshot();
		held.setHideInventory(true);
		held.setHideGear(true);

		LiveParty.applyPrivacy(held);

		assertNull(held.getInventory());
		assertNull(held.getInventoryQuantities());
		assertNull(held.getRunePouch());
		assertNull(held.getEquipment());
		// Not privacy's business.
		assertEquals(99, held.getCurrentHp());
	}

	@Test
	public void privacyLeavesAnUnhiddenSnapshotAlone()
	{
		PlayerUpdate held = snapshot();
		held.setHideInventory(false);
		held.setHideGear(false);

		LiveParty.applyPrivacy(held);

		assertNotNull(held.getInventory());
		assertNotNull(held.getEquipment());
	}

	/** Turning privacy back off has to reach peers, which it only does because the flag is always sent. */
	@Test
	public void unhidingRestoresGearOnTheNextUpdateCarryingIt()
	{
		PlayerUpdate hiding = snapshot();
		hiding.setHideGear(true);
		hiding.setEquipment(null);
		JsonObject held = LiveStateCodec.toWire(gson.toJsonTree(hiding).getAsJsonObject());

		PlayerUpdate showing = snapshot();
		showing.setHideGear(false);
		JsonObject patch = LiveStateCodec.toWire(gson.toJsonTree(showing).getAsJsonObject());

		PlayerUpdate merged =
			gson.fromJson(LiveStateCodec.fromWire(LiveStateCodec.merge(held, patch)), PlayerUpdate.class);
		LiveParty.applyPrivacy(merged);

		assertArrayEquals(new int[]{4151, 11802}, merged.getEquipment());
	}

	/**
	 * An items frame carries the slots that moved, not the inventory. The merge has to accumulate them, or a
	 * peer would end up holding only the last thing that changed and nothing else.
	 */
	@Test
	public void slotPatchesAccumulateInsteadOfReplacing()
	{
		JsonObject held = new JsonObject();
		JsonObject baseline = new JsonObject();
		baseline.addProperty("0", 385);
		baseline.addProperty("1", 2434);
		held.add("iv", baseline);

		JsonObject patch = new JsonObject();
		JsonObject moved = new JsonObject();
		// One slot emptied, one filled. Everything else must survive untouched.
		moved.addProperty("1", -1);
		moved.addProperty("5", 12695);
		patch.add("iv", moved);

		PlayerUpdate merged =
			gson.fromJson(LiveStateCodec.fromWire(LiveStateCodec.merge(held, patch)), PlayerUpdate.class);

		assertEquals(385, merged.getInventory()[0]);
		assertEquals(-1, merged.getInventory()[1]);
		assertEquals(12695, merged.getInventory()[5]);
		// Padded to the full inventory, so a caller can index by slot without checking.
		assertEquals(28, merged.getInventory().length);
	}

	/** A slot nobody has mentioned is empty, and an unmentioned quantity is a single item. */
	@Test
	public void unmentionedSlotsTakeTheirDefault()
	{
		JsonObject state = new JsonObject();
		JsonObject inventory = new JsonObject();
		inventory.addProperty("2", 385);
		state.add("iv", inventory);
		JsonObject quantities = new JsonObject();
		quantities.addProperty("2", 40);
		state.add("iq", quantities);

		PlayerUpdate read = gson.fromJson(LiveStateCodec.fromWire(state), PlayerUpdate.class);

		assertEquals(385, read.getInventory()[2]);
		assertEquals(-1, read.getInventory()[0]);
		assertEquals(40, read.getInventoryQuantities()[2]);
		assertEquals(1, read.getInventoryQuantities()[0]);
	}

	/**
	 * The three frame kinds must not overlap. If a field rode in two of them, which value a receiver ends up
	 * with would depend on the order the frames happened to arrive.
	 */
	@Test
	public void frameKindsDoNotShareFields()
	{
		for (String item : LiveStateCodec.ITEM_FIELDS)
		{
			for (String profile : LiveStateCodec.PROFILE_FIELDS)
			{
				assertFalse("field in both items and profile: " + item, item.equals(profile));
			}
		}
		// The vitals frame is built by hand rather than projected, so check it against both lists.
		for (String vital : new String[]{"hp", "pr", "sp", "re"})
		{
			for (String other : LiveStateCodec.ITEM_FIELDS)
			{
				assertFalse("field in both vitals and items: " + vital, vital.equals(other));
			}
			for (String other : LiveStateCodec.PROFILE_FIELDS)
			{
				assertFalse("field in both vitals and profile: " + vital, vital.equals(other));
			}
		}
	}

	@Test
	public void projectionTakesOnlyItsOwnFields()
	{
		JsonObject full = LiveStateCodec.toWire(gson.toJsonTree(snapshot()).getAsJsonObject());

		JsonObject items = LiveStateCodec.project(full, LiveStateCodec.ITEM_FIELDS);
		assertTrue(items.has("iv"));
		assertTrue(items.has("eq"));
		assertFalse(items.has("sk"));
		assertFalse(items.has("hp"));

		JsonObject profile = LiveStateCodec.project(full, LiveStateCodec.PROFILE_FIELDS);
		assertTrue(profile.has("n"));
		assertTrue(profile.has("wd"));
		assertFalse(profile.has("iv"));
	}

	/** A projection of nothing is empty rather than a crash — a snapshot can be null before login. */
	@Test
	public void projectionOfNothingIsEmpty()
	{
		assertEquals(0, LiveStateCodec.project(null, LiveStateCodec.ITEM_FIELDS).size());
	}

	/** Killcount is dead weight on the wire and must not be serialised at all. */
	@Test
	public void killcountNeverReachesTheWire()
	{
		JsonObject full = LiveStateCodec.toWire(gson.toJsonTree(snapshot()).getAsJsonObject());

		assertFalse(full.has("killCount"));
		assertFalse(full.has("hardModeKillCount"));
	}

	private static PlayerUpdate snapshot()
	{
		PlayerUpdate update = new PlayerUpdate();
		update.setName("Zezima");
		update.setAccountHash(4242L);
		update.setWorld(301);
		update.setCurrentHp(99);
		update.setEquipment(new int[]{4151, 11802});
		update.setInventory(new int[]{385, 2434});
		update.setInventoryQuantities(new int[]{1, 4});
		update.setRunePouch(new int[]{554, 555});
		return update;
	}
}
