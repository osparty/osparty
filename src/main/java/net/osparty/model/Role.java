package net.osparty.model;

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
	TOB_NFRZ("tobnfrz", "North freeze"),
	TOB_SFRZ("tobsfrz", "South freeze"),
	TOB_FILL("tobfill", "Fill / Any"),

	// Theatre of Blood Hard Mode (HMT).
	TOB_HM_MELEE("tobhmmelee", "Melee"),
	TOB_HM_RANGED("tobhmranged", "Ranged"),
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
	;

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

	/** The display name for a role id, falling back to the raw id when unknown. */
	public static String displayNameOf(String id)
	{
		Role role = fromId(id);
		return role != null ? role.displayName : id;
	}
}
