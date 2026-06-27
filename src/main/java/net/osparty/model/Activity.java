package net.osparty.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.Getter;

/**
 * The set of group activities a player can queue for. Each value carries a
 * stable {@link #id} used when talking to the queue API, a human readable
 * {@link #displayName} shown in the UI, and the party size bounds the activity
 * supports.
 *
 * <p>{@link #hardModeLabel} names the activity's harder kill variant (e.g.
 * Challenge Mode for Chambers of Xeric) where one exists, so parties can set a
 * separate minimum requirement for it; it is {@code null} for activities with
 * no such variant.
 *
 * <p>{@link #regionIds} lists the map region(s) at (or inside) the activity's
 * location, used to surface the activity in the Search tab when the player is
 * standing nearby (see {@link #nearby(int[])}).
 */
@Getter
public enum Activity
{
	CHAMBERS_OF_XERIC("cox", "Chambers of Xeric", 1, 100, "CM", 4919),
	THEATRE_OF_BLOOD("tob", "Theatre of Blood", 3, 5, "HM", 14642),
	TOMBS_OF_AMASCUT("toa", "Tombs of Amascut", 1, 8, "Expert", 13354),
	NEX("nex", "Nex", 1, 40, null, 11601),
	NIGHTMARE("nightmare", "The Nightmare", 1, 80, null, 15515, 15155),
	CORPOREAL_BEAST("corp", "Corporeal Beast", 1, 30, null, 12857),
	BARBARIAN_ASSAULT("ba", "Barbarian Assault", 4, 5, null, 10039),
	ZALCANO("zalcano", "Zalcano", 1, 30, null, 13150),
	HUEYCOATL("hueycoatl", "The Hueycoatl", 1, 10, null, 5939),
	YAMA("yama", "Yama", 1, 2, null, 6045),
	KREEARRA("kreearra", "Kree'arra", 1, 8, null, 11346),
	GENERAL_GRAARDOR("graardor", "General Graardor", 1, 8, null, 11347),
	KRIL_TSUTSAROTH("kril", "K'ril Tsutsaroth", 1, 8, null, 11603),
	COMMANDER_ZILYANA("zilyana", "Commander Zilyana", 1, 8, null, 11602),
	;

	private final String id;
	private final String displayName;
	private final int minPartySize;
	private final int maxPartySize;
	private final String hardModeLabel;
	private final int[] regionIds;

	Activity(String id, String displayName, int minPartySize, int maxPartySize, String hardModeLabel,
		int... regionIds)
	{
		this.id = id;
		this.displayName = displayName;
		this.minPartySize = minPartySize;
		this.maxPartySize = maxPartySize;
		this.hardModeLabel = hardModeLabel;
		this.regionIds = regionIds;
	}

	public boolean hasHardMode()
	{
		return hardModeLabel != null;
	}

	/** The CM/HMT title for the hard-mode toggle (Chambers of Xeric / Theatre of Blood). */
	public String getHardModeName()
	{
		switch (this)
		{
			case CHAMBERS_OF_XERIC:
				return "CoX CM";
			case THEATRE_OF_BLOOD:
				return "HMT";
			default:
				return displayName;
		}
	}

	/** True for ToA, where difficulty is an invocation level rather than a CM/HM toggle. */
	public boolean usesInvocation()
	{
		return this == TOMBS_OF_AMASCUT;
	}

	/**
	 * The roles a player can <em>be</em> in this activity (the "my role" dropdown
	 * and the apply prompt), in display order. Theatre of Blood is Melee / Ranged /
	 * Mage; Chambers of Xeric is Melee hand / Skip / Runner / Fill. Activities
	 * without roles return an empty list.
	 */
	public List<Role> roles()
	{
		switch (this)
		{
			case THEATRE_OF_BLOOD:
				return Arrays.asList(Role.TOB_MELEE, Role.TOB_RANGED, Role.TOB_MAGE);
			case CHAMBERS_OF_XERIC:
				return Arrays.asList(Role.COX_MELEE_HAND, Role.COX_SKIP, Role.COX_RUNNER, Role.COX_FILL);
			default:
				return Collections.emptyList();
		}
	}

	/**
	 * The roles shown in the Search filter for this activity: the normal
	 * {@link #roles()} plus a "Fill / Any" wildcard (ToB has no Fill slot in a
	 * composition, but you can still search as "I'll do any role").
	 */
	public List<Role> filterRoles()
	{
		switch (this)
		{
			case THEATRE_OF_BLOOD:
				return Arrays.asList(Role.TOB_MELEE, Role.TOB_RANGED, Role.TOB_MAGE, Role.TOB_FILL);
			case CHAMBERS_OF_XERIC:
				return roles(); // COX_FILL already doubles as the "any" option
			default:
				return Collections.emptyList();
		}
	}

	/** The "I'll do any role" wildcard for this activity's Search box, or null. */
	public Role anyRole()
	{
		switch (this)
		{
			case THEATRE_OF_BLOOD:
				return Role.TOB_FILL;
			case CHAMBERS_OF_XERIC:
				return Role.COX_FILL;
			default:
				return null;
		}
	}

	/**
	 * Theatre of Blood's fixed team composition (a role multiset) for a given party
	 * size: 3 = 1 melee / 1 ranged / 1 mage, 4 = 1 / 1 / 2, 5 = 2 / 1 / 2 (smaller
	 * sizes degrade sensibly). Returns null for activities whose composition the
	 * host configures by hand (e.g. Chambers of Xeric).
	 */
	public List<Role> fixedComposition(int partySize)
	{
		if (this != THEATRE_OF_BLOOD)
		{
			return null;
		}
		List<Role> comp = new ArrayList<>();
		int melee = partySize >= 5 ? 2 : (partySize >= 2 ? 1 : 0);
		int ranged = partySize >= 3 ? 1 : 0;
		int mage = partySize - melee - ranged; // the remainder (the freezers)
		for (int i = 0; i < melee; i++)
		{
			comp.add(Role.TOB_MELEE);
		}
		for (int i = 0; i < ranged; i++)
		{
			comp.add(Role.TOB_RANGED);
		}
		for (int i = 0; i < Math.max(0, mage); i++)
		{
			comp.add(Role.TOB_MAGE);
		}
		return comp;
	}

	/** True when this activity's composition is fixed by party size (i.e. ToB). */
	public boolean hasFixedComposition()
	{
		return this == THEATRE_OF_BLOOD;
	}

	/** True when this activity uses roles (i.e. Theatre of Blood / Chambers of Xeric). */
	public boolean hasRoles()
	{
		return !roles().isEmpty();
	}

	/**
	 * @return the title to show for an ad: the CM/HMT name when {@code hardMode} is
	 * set, or "ToA (level)" when an {@code invocation} is given, else the plain name.
	 */
	public String displayName(boolean hardMode, int invocation)
	{
		if (usesInvocation())
		{
			return invocation > 0 ? "ToA (" + invocation + ")" : displayName;
		}
		return hardMode && hasHardMode() ? getHardModeName() : displayName;
	}

	@Override
	public String toString()
	{
		return displayName;
	}

	public static Activity fromId(String id)
	{
		for (Activity activity : values())
		{
			if (activity.id.equals(id))
			{
				return activity;
			}
		}
		return null;
	}

	/**
	 * @return the first activity whose area is among the currently-loaded map
	 * regions (i.e. the player is standing at/inside it), or {@code null} if none.
	 */
	public static Activity nearby(int[] mapRegions)
	{
		if (mapRegions == null || mapRegions.length == 0)
		{
			return null;
		}
		for (Activity activity : values())
		{
			for (int region : activity.regionIds)
			{
				for (int loaded : mapRegions)
				{
					if (region == loaded)
					{
						return activity;
					}
				}
			}
		}
		return null;
	}
}
