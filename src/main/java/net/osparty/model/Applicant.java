package net.osparty.model;

import java.util.Map;
import lombok.Data;

/**
 * A player applying to join a hosted party. Carries the combat stats and boss
 * killcount the host uses to decide whether to accept. Populated from the
 * applicant's live peer-to-peer self-report (see {@code PlayerUpdate}).
 */
@Data
public class Applicant
{
	private String name;
	private int combatLevel;

	/** The applicant's live-party member id (for admit/decline); 0 when unknown. */
	private long memberId;

	/** Ordered skill name -> level (Attack, Strength, ...). */
	private Map<String, Integer> stats;

	/** Kills of the party's activity. */
	private int killCount;

	/** Kills of the harder variant (CM/HM/Expert); -1 when not applicable. */
	private int hardModeKillCount;

	/** Personal best (seconds) for the activity+team size; -1 when unknown. */
	private double pbSeconds = -1;

	/**
	 * Worn equipment as OSRS item ids, indexed by {@link EquipmentSlot#ordinal()}
	 * (length {@link EquipmentSlot#COUNT}). A value {@code <= 0} means the slot is
	 * empty. {@code null} when no gear is known for this player.
	 */
	private int[] equipment;

	/**
	 * Inventory as 28 OSRS item ids (slot order). A value {@code <= 0} means an
	 * empty slot. {@code null} when no inventory is known for this player.
	 */
	private int[] inventory;

	/**
	 * The eleven worn-equipment slots, in the order {@link #equipment} stores
	 * them. Mirrors the in-game equipment layout used by {@code CurrentPanel}.
	 */
	public enum EquipmentSlot
	{
		HEAD, CAPE, AMULET, AMMO, WEAPON, BODY, SHIELD, LEGS, GLOVES, BOOTS, RING;

		public static final int COUNT = values().length;
	}
}
