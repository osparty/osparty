package net.osparty.model;

import lombok.Getter;

/**
 * A role a player can fill in a role-based activity (Theatre of Blood and
 * Chambers of Xeric). Each value carries a stable {@link #id} used when
 * (de)serialising and talking to the API, and a human readable
 * {@link #displayName} shown in the UI.
 *
 * <p>Which roles apply where is decided by {@link Activity}:
 * <ul>
 *   <li>{@link Activity#roles()} - the roles a host/applicant can be (the
 *       "my role" dropdown and the apply prompt).</li>
 *   <li>{@link Activity#filterRoles()} - the roles shown in the Search filter
 *       (adds a "Fill / Any" wildcard for ToB).</li>
 *   <li>{@link Activity#fixedComposition(int)} - ToB's size-based team make-up.</li>
 * </ul>
 *
 * <p>The {@code *_FILL} values are wildcards. For Chambers of Xeric a Fill slot
 * is a real, flexible team spot (a host can advertise it, and anyone fits it).
 * For Theatre of Blood, Fill exists only as a Search option meaning "I'll do any
 * role" - it is never part of a party's composition.
 */
@Getter
public enum Role
{
	// Theatre of Blood.
	TOB_MELEE("tobmelee", "Melee"),
	TOB_RANGED("tobranged", "Ranged"),
	TOB_MAGE("tobmage", "Mage"),
	/** Search-only wildcard for ToB ("I'll do any role"); never in a composition. */
	TOB_FILL("tobfill", "Fill / Any"),

	// Chambers of Xeric.
	COX_MELEE_HAND("coxmeleehand", "Melee hand"),
	COX_SKIP("coxskip", "Skip"),
	COX_RUNNER("coxrunner", "Runner"),
	/** A flexible CoX spot: a host can advertise it, and any applicant fits it. */
	COX_FILL("coxfill", "Fill / Any"),
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
