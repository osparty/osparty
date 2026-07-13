package net.osparty.party;

import net.osparty.model.Applicant.EquipmentSlot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.FriendsChatManager;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

/**
 * Builds a {@link PlayerUpdate} from the local client's live state. Reads item
 * containers and skills, so it must be called on the client thread.
 */
final class LocalPlayerSync
{
	/** Varp id for special-attack energy (0-1000). {@code VarPlayer.SPECIAL_ATTACK_PERCENT} is deprecated. */
	private static final int VARP_SPECIAL_ATTACK_PERCENT = 300;

	/** Rune-pouch slot varbits (rune type + amount), up to 6 slots on the divine pouch. */
	private static final int[] RUNE_POUCH_TYPE_VARBITS = {
		VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
		VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6,
	};
	private static final int[] RUNE_POUCH_AMOUNT_VARBITS = {
		VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2, VarbitID.RUNE_POUCH_QUANTITY_3,
		VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5, VarbitID.RUNE_POUCH_QUANTITY_6,
	};
	/** All rune-pouch item ids (standard/locked and divine/locked). */
	private static final int[] RUNE_POUCH_ITEMS = {
		ItemID.BH_RUNE_POUCH, ItemID.BH_RUNE_POUCH_TROUVER,
		ItemID.DIVINE_RUNE_POUCH, ItemID.DIVINE_RUNE_POUCH_TROUVER,
	};

	private LocalPlayerSync()
	{
	}

	/** @return a snapshot of the local player, or {@code null} when not logged in. */
	static PlayerUpdate snapshot(Client client)
	{
		Player local = client.getLocalPlayer();
		if (local == null || local.getName() == null)
		{
			return null;
		}

		PlayerUpdate update = new PlayerUpdate();
		update.setName(local.getName());
		update.setAccountHash(client.getAccountHash());
		update.setCombatLevel(local.getCombatLevel());
		update.setEquipment(equipment(client));
		update.setInventory(inventory(client));
		update.setInventoryQuantities(inventoryQuantities(client));
		captureRunePouch(client, update);

		// Live vitals — current values (boosted), always shown in the roster.
		update.setMaxHp(client.getRealSkillLevel(Skill.HITPOINTS));
		update.setCurrentHp(client.getBoostedSkillLevel(Skill.HITPOINTS));
		update.setMaxPrayer(client.getRealSkillLevel(Skill.PRAYER));
		update.setCurrentPrayer(client.getBoostedSkillLevel(Skill.PRAYER));
		update.setSpecialPercent(client.getVarpValue(VARP_SPECIAL_ATTACK_PERCENT) / 10);
		update.setRunEnergy(client.getEnergy() / 100); // getEnergy() is in 1/100th of a percent
		update.setSpellbook(client.getVarbitValue(VarbitID.SPELLBOOK));

		Map<String, Integer> stats = new LinkedHashMap<>();
		for (Skill skill : Skill.values())
		{
			try
			{
				stats.put(skill.getName(), client.getRealSkillLevel(skill));
			}
			catch (Exception ignored)
			{
				// Placeholder/unreleased skills (e.g. Sailing) - skip.
			}
		}
		update.setStats(stats);
		if (client.getAccountType() != null)
		{
			update.setAccountType(client.getAccountType().name());
		}
		update.setWorld(client.getWorld());

		FriendsChatManager fcm = client.getFriendsChatManager();
		update.setFriendsChatOwner(fcm != null ? fcm.getOwner() : null);
		return update;
	}

	private static int[] inventory(Client client)
	{
		int[] out = new int[28];
		Arrays.fill(out, -1);
		ItemContainer container = client.getItemContainer(InventoryID.INVENTORY);
		if (container != null)
		{
			Item[] items = container.getItems();
			for (int i = 0; i < items.length && i < out.length; i++)
			{
				out[i] = items[i] == null ? -1 : items[i].getId();
			}
		}
		return out;
	}

	/** Stack sizes parallel to {@link #inventory(Client)}; {@code 0} for empty slots. */
	private static int[] inventoryQuantities(Client client)
	{
		int[] out = new int[28];
		ItemContainer container = client.getItemContainer(InventoryID.INVENTORY);
		if (container != null)
		{
			Item[] items = container.getItems();
			for (int i = 0; i < items.length && i < out.length; i++)
			{
				out[i] = items[i] == null ? 0 : items[i].getQuantity();
			}
		}
		return out;
	}

	/** Populate rune-pouch contents (only the owner can read the varbits), resolved to item ids + amounts. */
	private static void captureRunePouch(Client client, PlayerUpdate update)
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		if (inventory == null || !containsRunePouch(inventory))
		{
			return;
		}
		EnumComposition runeEnum = client.getEnum(EnumID.RUNEPOUCH_RUNE);
		List<Integer> ids = new ArrayList<>();
		List<Integer> amounts = new ArrayList<>();
		List<String> names = new ArrayList<>();
		for (int i = 0; i < RUNE_POUCH_TYPE_VARBITS.length; i++)
		{
			int runeType = client.getVarbitValue(RUNE_POUCH_TYPE_VARBITS[i]);
			int amount = client.getVarbitValue(RUNE_POUCH_AMOUNT_VARBITS[i]);
			if (runeType == 0 || amount <= 0)
			{
				continue;
			}
			int itemId = runeEnum != null ? runeEnum.getIntValue(runeType) : -1;
			if (itemId <= 0)
			{
				continue;
			}
			ids.add(itemId);
			amounts.add(amount);
			// Resolve the name here (client thread); spectators can't call getItemComposition off it.
			names.add(client.getItemDefinition(itemId).getName());
		}
		if (ids.isEmpty())
		{
			return;
		}
		update.setRunePouch(ids.stream().mapToInt(Integer::intValue).toArray());
		update.setRunePouchAmounts(amounts.stream().mapToInt(Integer::intValue).toArray());
		update.setRunePouchNames(names.toArray(new String[0]));
	}

	private static boolean containsRunePouch(ItemContainer inventory)
	{
		for (Item item : inventory.getItems())
		{
			if (item == null)
			{
				continue;
			}
			for (int pouchId : RUNE_POUCH_ITEMS)
			{
				if (item.getId() == pouchId)
				{
					return true;
				}
			}
		}
		return false;
	}

	private static int[] equipment(Client client)
	{
		int[] out = new int[EquipmentSlot.COUNT];
		Arrays.fill(out, -1);
		ItemContainer container = client.getItemContainer(InventoryID.EQUIPMENT);
		if (container == null)
		{
			return out;
		}
		out[EquipmentSlot.HEAD.ordinal()] = id(container, EquipmentInventorySlot.HEAD);
		out[EquipmentSlot.CAPE.ordinal()] = id(container, EquipmentInventorySlot.CAPE);
		out[EquipmentSlot.AMULET.ordinal()] = id(container, EquipmentInventorySlot.AMULET);
		out[EquipmentSlot.AMMO.ordinal()] = id(container, EquipmentInventorySlot.AMMO);
		out[EquipmentSlot.WEAPON.ordinal()] = id(container, EquipmentInventorySlot.WEAPON);
		out[EquipmentSlot.BODY.ordinal()] = id(container, EquipmentInventorySlot.BODY);
		out[EquipmentSlot.SHIELD.ordinal()] = id(container, EquipmentInventorySlot.SHIELD);
		out[EquipmentSlot.LEGS.ordinal()] = id(container, EquipmentInventorySlot.LEGS);
		out[EquipmentSlot.GLOVES.ordinal()] = id(container, EquipmentInventorySlot.GLOVES);
		out[EquipmentSlot.BOOTS.ordinal()] = id(container, EquipmentInventorySlot.BOOTS);
		out[EquipmentSlot.RING.ordinal()] = id(container, EquipmentInventorySlot.RING);
		return out;
	}

	private static int id(ItemContainer container, EquipmentInventorySlot slot)
	{
		Item item = container.getItem(slot.getSlotIdx());
		return item == null ? -1 : item.getId();
	}
}
