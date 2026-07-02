package net.osparty.model;

import java.util.Map;
import lombok.Data;

/**
 * A player applying to join a hosted party, populated from the applicant's live
 * peer-to-peer self-report (see {@code PlayerUpdate}).
 */
@Data
public class Applicant
{
	private String name;
	private int combatLevel;

	/** 0 when unknown. */
	private long memberId;

	/** Applicant's stable accountHash ({@code 0} when unknown); for block-list matching. */
	private long accountHash;

	/** True when this applicant is on the host's block list (host-side flag, not sent over the wire). */
	private boolean blocked;

	/** Ordered: skill name -> level. */
	private Map<String, Integer> stats;

	private int killCount;

	/** -1 when not applicable. */
	private int hardModeKillCount;

	/** Seconds; -1 when unknown. */
	private double pbSeconds = -1;

	private String accountType;
	private String role;

	/** Self-marked as a learner. */
	private boolean learner;

	/**
	 * OSRS item ids indexed by {@link EquipmentSlot#ordinal()} (length
	 * {@link EquipmentSlot#COUNT}); {@code <= 0} = empty slot, {@code null} = unknown.
	 */
	private int[] equipment;

	/** 28 OSRS item ids in slot order; {@code <= 0} = empty slot, {@code null} = unknown. */
	private int[] inventory;

	/** Stack size for each inventory slot, parallel to {@link #inventory}; {@code null} when unknown. */
	private int[] inventoryQuantities;

	/** Resolved rune item ids in the carried rune pouch; {@code null} when none. */
	private int[] runePouch;

	/** Amount of each {@link #runePouch} rune, parallel to it. */
	private int[] runePouchAmounts;

	/** Display name of each {@link #runePouch} rune, parallel to it. */
	private String[] runePouchNames;

	/** Slot order matches {@link #equipment}'s indices. */
	public enum EquipmentSlot
	{
		HEAD, CAPE, AMULET, AMMO, WEAPON, BODY, SHIELD, LEGS, GLOVES, BOOTS, RING;

		public static final int COUNT = values().length;
	}
}
