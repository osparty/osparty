package net.osparty.party.v2;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.osparty.party.PlayerUpdate;
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
		JsonObject full = gson.toJsonTree(snapshot()).getAsJsonObject();
		JsonObject vitals = new JsonObject();
		vitals.addProperty("currentHp", 31);

		PlayerUpdate merged = gson.fromJson(LivePartyV2.merge(full, vitals), PlayerUpdate.class);

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
		vitals.addProperty("currentHp", 12);

		PlayerUpdate merged = gson.fromJson(LivePartyV2.merge(null, vitals), PlayerUpdate.class);

		assertEquals(12, merged.getCurrentHp());
		assertNull(merged.getName());
	}

	@Test
	public void mergingDoesNotMutateEitherSide()
	{
		JsonObject base = gson.toJsonTree(snapshot()).getAsJsonObject();
		JsonObject patch = new JsonObject();
		patch.addProperty("world", 420);

		LivePartyV2.merge(base, patch);

		assertEquals(301, base.get("world").getAsInt());
		assertFalse(patch.has("name"));
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

		LivePartyV2.applyPrivacy(held);

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

		LivePartyV2.applyPrivacy(held);

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
		JsonObject held = gson.toJsonTree(hiding).getAsJsonObject();

		PlayerUpdate showing = snapshot();
		showing.setHideGear(false);
		JsonObject patch = gson.toJsonTree(showing).getAsJsonObject();

		PlayerUpdate merged = gson.fromJson(LivePartyV2.merge(held, patch), PlayerUpdate.class);
		LivePartyV2.applyPrivacy(merged);

		assertArrayEquals(new int[]{4151, 11802}, merged.getEquipment());
	}

	/**
	 * The three frame kinds must not overlap. If a field rode in two of them, which value a receiver ends up
	 * with would depend on the order the frames happened to arrive.
	 */
	@Test
	public void frameKindsDoNotShareFields()
	{
		for (String item : LivePartyV2.ITEM_FIELDS)
		{
			for (String profile : LivePartyV2.PROFILE_FIELDS)
			{
				assertFalse("field in both items and profile: " + item, item.equals(profile));
			}
		}
		// The vitals frame is built by hand rather than projected, so check it against both lists.
		for (String vital : new String[]{"currentHp", "currentPrayer", "specialPercent", "runEnergy"})
		{
			for (String other : LivePartyV2.ITEM_FIELDS)
			{
				assertFalse("field in both vitals and items: " + vital, vital.equals(other));
			}
			for (String other : LivePartyV2.PROFILE_FIELDS)
			{
				assertFalse("field in both vitals and profile: " + vital, vital.equals(other));
			}
		}
	}

	@Test
	public void projectionTakesOnlyItsOwnFields()
	{
		JsonObject full = gson.toJsonTree(snapshot()).getAsJsonObject();

		JsonObject items = LivePartyV2.project(full, LivePartyV2.ITEM_FIELDS);
		assertTrue(items.has("inventory"));
		assertTrue(items.has("equipment"));
		assertFalse(items.has("stats"));
		assertFalse(items.has("currentHp"));

		JsonObject profile = LivePartyV2.project(full, LivePartyV2.PROFILE_FIELDS);
		assertTrue(profile.has("name"));
		assertTrue(profile.has("world"));
		assertFalse(profile.has("inventory"));
	}

	/** A projection of nothing is empty rather than a crash — a snapshot can be null before login. */
	@Test
	public void projectionOfNothingIsEmpty()
	{
		assertEquals(0, LivePartyV2.project(null, LivePartyV2.ITEM_FIELDS).size());
	}

	/** Killcount is dead weight on the wire and must not be serialised at all. */
	@Test
	public void killcountNeverReachesTheWire()
	{
		JsonObject full = gson.toJsonTree(snapshot()).getAsJsonObject();

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
