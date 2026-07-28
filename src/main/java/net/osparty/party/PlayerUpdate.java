package net.osparty.party;

import com.google.gson.annotations.SerializedName;
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
 *
 * <p>The wire names are short because the vitals frame — four small integers, sent on almost every tick a
 * member is in combat — was mostly key names: {@code currentHp}/{@code currentPrayer}/{@code specialPercent}/
 * {@code runEnergy} cost more than the numbers they label. Each field keeps its long name as a Gson
 * {@code alternate}, so this client still reads anything an older one sends; only the other direction
 * needs the update. The server never reads this payload — it relays the blob opaquely — so shortening it
 * is a change between clients alone.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class PlayerUpdate extends PartyMemberMessage
{
	@SerializedName(value = "n", alternate = "name")
	private String name;
	/** The member's stable accountHash; {@code 0} when unknown (older client). Used for block/favourite matching. */
	@SerializedName(value = "ah", alternate = "accountHash")
	private long accountHash;
	@SerializedName(value = "cl", alternate = "combatLevel")
	private int combatLevel;

	/** In {@link net.osparty.model.Applicant.EquipmentSlot} order; {@code <= 0} = empty. */
	@SerializedName(value = "eq", alternate = "equipment")
	private int[] equipment;

	/** 28 ids in slot order; {@code <= 0} = empty. */
	@SerializedName(value = "iv", alternate = "inventory")
	private int[] inventory;

	/** Stack size for each inventory slot, parallel to {@link #inventory}; {@code 0} when empty/unknown. */
	@SerializedName(value = "iq", alternate = "inventoryQuantities")
	private int[] inventoryQuantities;

	/** Resolved rune item ids inside the carried rune pouch; {@code null} when none is carried. */
	@SerializedName(value = "rp", alternate = "runePouch")
	private int[] runePouch;

	/** Amount of each {@link #runePouch} rune, parallel to it. */
	@SerializedName(value = "ra", alternate = "runePouchAmounts")
	private int[] runePouchAmounts;

	/** Display name of each {@link #runePouch} rune, parallel to it (resolved on the owner's client thread). */
	@SerializedName(value = "rn", alternate = "runePouchNames")
	private String[] runePouchNames;

	/** Skill name -> real level. */
	@SerializedName(value = "sk", alternate = "stats")
	private Map<String, Integer> stats;

	// ---- live vitals (always shown in the roster); {@code -1} when unknown ----
	/** Current (boosted) hitpoints. */
	@SerializedName(value = "hp", alternate = "currentHp")
	private int currentHp = -1;
	/** Max hitpoints (real Hitpoints level). */
	@SerializedName(value = "mh", alternate = "maxHp")
	private int maxHp = -1;
	/** Current (boosted) prayer points. */
	@SerializedName(value = "pr", alternate = "currentPrayer")
	private int currentPrayer = -1;
	/** Max prayer (real Prayer level). */
	@SerializedName(value = "mp", alternate = "maxPrayer")
	private int maxPrayer = -1;
	/** Special-attack energy, 0-100. */
	@SerializedName(value = "sp", alternate = "specialPercent")
	private int specialPercent = -1;
	/** Run energy, 0-100. */
	@SerializedName(value = "re", alternate = "runEnergy")
	private int runEnergy = -1;

	/** Active spellbook: 0 standard, 1 ancient, 2 lunar, 3 arceuus; {@code -1} when unknown. */
	@SerializedName(value = "sb", alternate = "spellbook")
	private int spellbook = -1;

	/**
	 * {@code -1} when unknown — which is always: nothing has ever populated this. Kept, and kept read, only
	 * because every reader treats {@code -1} as "go and look it up", and that lookup ({@code KillcountService},
	 * by name, on the viewer's client) is where the killcount actually comes from. Transient so the two dead
	 * fields stop costing ~45 bytes on every live update.
	 */
	private transient int killCount = -1;

	/** Harder variant (CM/HM/Expert); {@code -1} when unknown/N/A. As dead as {@link #killCount}. */
	private transient int hardModeKillCount = -1;

	/** Enum name (NORMAL / IRONMAN / ...); null when unknown. */
	@SerializedName(value = "at", alternate = "accountType")
	private String accountType;

	/** Raids only; null when none. */
	@SerializedName(value = "ro", alternate = "role")
	private String role;

	/** Self-marked as a learner for this raid. */
	@SerializedName(value = "ln", alternate = "learner")
	private boolean learner;

	/** Marked as the teacher for this raid (the host of a teaching raid). */
	@SerializedName(value = "te", alternate = "teacher")
	private boolean teacher;

	/** True when we joined via a party invite; the host auto-admits us instead of prompting for approval. */
	@SerializedName(value = "in", alternate = "invited")
	private boolean invited;

	/** Seconds; {@code -1} when unknown. */
	@SerializedName(value = "pb", alternate = "pbSeconds")
	private double pbSeconds = -1;

	/** {@code 0} when logged out/unknown. */
	@SerializedName(value = "wd", alternate = "world")
	private int world;

	/** Null when in no friends chat. */
	@SerializedName(value = "fc", alternate = "friendsChatOwner")
	private String friendsChatOwner;

	/**
	 * V2: the sender is deliberately withholding its inventory (and rune pouch), rather than not having sent
	 * it yet. Receivers merge each update into the last one they held, so an omitted field means "unchanged"
	 * — without this, turning privacy on would leave peers looking at the last inventory it saw. Boxed and
	 * left null on the RuneLite-relay path, so V1's wire format is unchanged. Delete with that path at P6.
	 */
	@SerializedName(value = "hi", alternate = "hideInventory")
	private Boolean hideInventory;

	/** V2: as {@link #hideInventory}, for worn equipment. */
	@SerializedName(value = "hg", alternate = "hideGear")
	private Boolean hideGear;
}
