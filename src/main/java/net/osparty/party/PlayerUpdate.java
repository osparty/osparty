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
	private int combatLevel;

	/** In {@link net.osparty.model.Applicant.EquipmentSlot} order; {@code <= 0} = empty. */
	private int[] equipment;

	/** 28 ids in slot order; {@code <= 0} = empty. */
	private int[] inventory;

	/** Skill name -> real level. */
	private Map<String, Integer> stats;

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

	/** Seconds; {@code -1} when unknown. */
	private double pbSeconds = -1;

	/** {@code 0} when logged out/unknown. */
	private int world;

	/** Null when in no friends chat. */
	private String friendsChatOwner;
}
