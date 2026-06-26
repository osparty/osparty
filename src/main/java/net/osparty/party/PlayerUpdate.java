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
 * <p>Unlike RaidParty/Party Panel this is sent whole rather than diffed and
 * bit-packed — simpler to read, and the payload (a couple of int arrays plus a
 * small map) is still small. Diffing can be layered on later if traffic matters.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class PlayerUpdate extends PartyMemberMessage
{
	private String name;
	private int combatLevel;

	/**
	 * Worn gear in {@link net.osparty.model.Applicant.EquipmentSlot} order
	 * (length {@code EquipmentSlot.COUNT}); a value {@code <= 0} means empty.
	 */
	private int[] equipment;

	/** 28 inventory item ids in slot order; a value {@code <= 0} means empty. */
	private int[] inventory;

	/** Combat skill name -> real level (Attack, Strength, ...). */
	private Map<String, Integer> stats;

	/** Kills of the party's activity; {@code -1} when unknown. */
	private int killCount = -1;

	/** Kills of the harder variant (CM/HM/Expert); {@code -1} when unknown/N/A. */
	private int hardModeKillCount = -1;

	/** The member's account type name (NORMAL / IRONMAN / ...); null when unknown. */
	private String accountType;
}
