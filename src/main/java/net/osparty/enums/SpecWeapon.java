package net.osparty.enums;

import java.util.function.IntUnaryOperator;
import net.runelite.api.gameval.ItemID;

/**
 * The defence-draining special-attack weapons OSParty tracks, with the equipped
 * item ids used to recognise them and each weapon's projectile hit delay. A
 * trimmed, self-contained copy of the data OSParty previously read from
 * RuneLite's Special Attack Counter plugin, so the defence tracker no longer
 * needs that plugin installed or enabled. Only defence-draining weapons are
 * listed; other special weapons are simply not recognised (and would be ignored
 * by the tracker anyway).
 */
public enum SpecWeapon
{
	DRAGON_WARHAMMER(ItemID.DRAGON_WARHAMMER, ItemID.BH_DRAGON_WARHAMMER_CORRUPTED),
	ELDER_MAUL(ItemID.ELDER_MAUL, ItemID.ELDER_MAUL_ORNAMENT),
	BANDOS_GODSWORD(ItemID.BGS, ItemID.BGSG),
	ARCLIGHT(ItemID.ARCLIGHT),
	DARKLIGHT(ItemID.DARKLIGHT),
	EMBERLIGHT(ItemID.EMBERLIGHT),
	BARRELCHEST_ANCHOR(ItemID.BRAIN_ANCHOR),
	BONE_DAGGER(ItemID.DTTD_BONE_DAGGER, ItemID.DTTD_BONE_DAGGER_P, ItemID.DTTD_BONE_DAGGER_P_, ItemID.DTTD_BONE_DAGGER_P__),
	DORGESHUUN_CROSSBOW(new int[]{ItemID.DTTD_BONE_CROSSBOW}, distance -> 60 + distance * 3),
	ACCURSED_SCEPTRE(new int[]{ItemID.WILD_CAVE_ACCURSED_CHARGED, ItemID.WILD_CAVE_ACCURSED_CHARGED_RECOL}, distance -> 46 + distance * 10);

	private final int[] itemIds;
	private final IntUnaryOperator clientCycleHitDelay;

	SpecWeapon(int... itemIds)
	{
		this(itemIds, distance -> 0);
	}

	SpecWeapon(int[] itemIds, IntUnaryOperator clientCycleHitDelay)
	{
		this.itemIds = itemIds;
		this.clientCycleHitDelay = clientCycleHitDelay;
	}

	/** @return true if the given equipped weapon item id is this special weapon. */
	public boolean matches(int itemId)
	{
		for (int id : itemIds)
		{
			if (id == itemId)
			{
				return true;
			}
		}
		return false;
	}

	/** Server ticks between the special attack and its hitsplat, given tile distance to the target. */
	public int getHitDelay(int distance)
	{
		return clientCycleHitDelay.applyAsInt(distance) / 30 + 1;
	}
}
