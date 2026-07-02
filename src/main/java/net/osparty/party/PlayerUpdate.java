package net.osparty.party;

import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Live self-report a party member broadcasts about their own character: worn
 * gear, inventory and combat stats. Every member sends their own; the framework
 * stamps the sender's {@code memberId} on receipt.
 *
 * <p>Sent whole rather than diffed/bit-packed — simpler, and the payload (a couple
 * of int arrays plus a small map) is small enough not to matter.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class PlayerUpdate extends PartyMemberMessage
{
	private String name;
	/** The member's stable accountHash; {@code 0} when unknown (older client). Used for block/favourite matching. */
	private long accountHash;
	private int combatLevel;

	/** In {@link net.osparty.model.Applicant.EquipmentSlot} order; {@code <= 0} = empty. */
	private int[] equipment;

	/** 28 ids in slot order; {@code <= 0} = empty. */
	private int[] inventory;

	/** Stack size for each inventory slot, parallel to {@link #inventory}; {@code 0} when empty/unknown. */
	private int[] inventoryQuantities;

	/** Skill name -> real level. */
	private Map<String, Integer> stats;

	// ---- live vitals (always shown in the roster); {@code -1} when unknown ----
	/** Current (boosted) hitpoints. */
	private int currentHp = -1;
	/** Max hitpoints (real Hitpoints level). */
	private int maxHp = -1;
	/** Current (boosted) prayer points. */
	private int currentPrayer = -1;
	/** Max prayer (real Prayer level). */
	private int maxPrayer = -1;
	/** Special-attack energy, 0-100. */
	private int specialPercent = -1;
	/** Run energy, 0-100. */
	private int runEnergy = -1;

	/** {@code -1} when unknown. */
	private int killCount = -1;

	/** Harder variant (CM/HM/Expert); {@code -1} when unknown/N/A. */
	private int hardModeKillCount = -1;

	/** Enum name (NORMAL / IRONMAN / ...); null when unknown. */
	private String accountType;

	/** Raids only; null when none. */
	private String role;

	/** Self-marked as a learner for this raid. */
	private boolean learner;

	/** Marked as the teacher for this raid (the host of a teaching raid). */
	private boolean teacher;

	/** Seconds; {@code -1} when unknown. */
	private double pbSeconds = -1;

	/** {@code 0} when logged out/unknown. */
	private int world;

	/** Null when in no friends chat. */
	private String friendsChatOwner;
}
