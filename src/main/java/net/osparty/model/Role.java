package net.osparty.model;

import java.util.EnumSet;
import java.util.Set;
import lombok.Getter;

/**
 * A role a player can fill in a role-based activity (Theatre of Blood and Chambers
 * of Xeric). Each value carries a stable {@link #id} used when (de)serialising and
 * talking to the API, and a {@link #displayName}. Which roles apply where is decided
 * by {@link Activity}.
 *
 * <p>Each of the four difficulty modes has its own, fully separate role set, so a
 * pick in one can never be confused with (or matched against) a party of another.
 * The {@code *_FILL} values are per-mode wildcards: for Chambers of Xeric a Fill slot
 * is a real, flexible team spot, whereas for Theatre of Blood Fill exists only as a
 * Search option meaning "I'll do any role" and is never part of a composition.
 */
@Getter
public enum Role
{
	// Theatre of Blood (normal).
	TOB_MELEE("tobmelee", "Melee"),
	TOB_RANGED("tobranged", "Ranged"),
	TOB_FRZ("tobfrz", "Freeze"),
	TOB_NFRZ("tobnfrz", "North freeze"),
	TOB_SFRZ("tobsfrz", "South freeze"),
	TOB_FILL("tobfill", "Fill / Any"),

	// Theatre of Blood Hard Mode (HMT).
	TOB_HM_MELEE("tobhmmelee", "Melee"),
	TOB_HM_RANGED("tobhmranged", "Ranged"),
	TOB_HM_FRZ("tobhmfrz", "Freeze"),
	TOB_HM_NFRZ("tobhmnfrz", "North freeze"),
	TOB_HM_SFRZ("tobhmsfrz", "South freeze"),
	TOB_HM_FILL("tobhmfill", "Fill / Any"),

	// Chambers of Xeric (normal).
	COX_MELEE("coxmelee", "Melee"),
	COX_MAGE("coxmage", "Mage"),
	COX_RUNNER("coxrunner", "Runner"),
	COX_FILL("coxfill", "Fill / Any"),

	// Chambers of Xeric Challenge Mode (CM).
	COX_CM_VENG("coxcmveng", "Veng"),
	COX_CM_ANCIENT("coxcmancient", "Ancient"),
	COX_CM_NORMAL("coxcmnormal", "Normal spells"),
	COX_CM_FILL("coxcmfill", "Fill / Any"),

	// Barbarian Assault.
	BA_ATTACKER("baattacker", "Attacker"),
	BA_DEFENDER("badefender", "Defender"),
	BA_COLLECTOR("bacollector", "Collector"),
	BA_HEALER("bahealer", "Healer"),
	BA_FILL("bafill", "Fill / Any"),
	;

	/**
	 * The freeze roles of one mode. A three-man team has a single combined Freeze slot
	 * instead of a north/south pair, so the three are interchangeable when matching a
	 * player's pick against a slot a party still needs.
	 */
	private static final Set<Role> TOB_FREEZE = EnumSet.of(TOB_FRZ, TOB_NFRZ, TOB_SFRZ);
	private static final Set<Role> TOB_HM_FREEZE = EnumSet.of(TOB_HM_FRZ, TOB_HM_NFRZ, TOB_HM_SFRZ);

	private final String id;
	private final String displayName;

	Role(String id, String displayName)
	{
		this.id = id;
		this.displayName = displayName;
	}

	@Override
	public String toString()
	{
		return displayName;
	}

	public static Role fromId(String id)
	{
		if (id == null)
		{
			return null;
		}
		for (Role role : values())
		{
			if (role.id.equals(id))
			{
				return role;
			}
		}
		return null;
	}

	/**
	 * True when a player who picked this role can take a slot advertised as
	 * {@code neededId}: the same role, or the other freeze slots of the same mode (a
	 * north freezer can take a three-man's combined Freeze slot, and vice versa).
	 */
	public boolean canFill(String neededId)
	{
		if (id.equals(neededId))
		{
			return true;
		}
		Role needed = fromId(neededId);
		if (needed == null)
		{
			return false;
		}
		return (TOB_FREEZE.contains(this) && TOB_FREEZE.contains(needed))
			|| (TOB_HM_FREEZE.contains(this) && TOB_HM_FREEZE.contains(needed));
	}

	/** The display name for a role id, falling back to the raw id when unknown. */
	public static String displayNameOf(String id)
	{
		Role role = fromId(id);
		return role != null ? role.displayName : id;
	}
}
