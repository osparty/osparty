package net.osparty.party;

import net.osparty.model.Applicant.EquipmentSlot;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;

/**
 * Builds a {@link PlayerUpdate} from the local client's live state. Reads item
 * containers and skills, so it must be called on the client thread.
 */
final class LocalPlayerSync
{
	private static final Skill[] COMBAT_SKILLS = {
		Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.HITPOINTS,
		Skill.RANGED, Skill.MAGIC, Skill.PRAYER,
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
		update.setCombatLevel(local.getCombatLevel());
		update.setEquipment(equipment(client));
		update.setInventory(inventory(client));

		Map<String, Integer> stats = new LinkedHashMap<>();
		for (Skill skill : COMBAT_SKILLS)
		{
			stats.put(skill.getName(), client.getRealSkillLevel(skill));
		}
		update.setStats(stats);
		if (client.getAccountType() != null)
		{
			update.setAccountType(client.getAccountType().name());
		}
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
