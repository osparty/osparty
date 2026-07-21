package net.osparty.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.Getter;

/**
 * The set of group activities a player can queue for, each carrying a stable
 * {@link #id} used by the queue API, a {@link #displayName}, and party-size bounds.
 * {@link #hardModeLabel} names the activity's harder kill variant, or {@code null}
 * when none; {@link #regionIds} lists the map region(s) at the activity's location.
 */
@Getter
public enum Activity
{
	CHAMBERS_OF_XERIC("cox", "CoX", 1, 100, "CM", 4919, 13136, 13137, 13138, 13139, 13140, 13141, 13393, 13394, 13395, 13396, 13397, 12889, 13145, 13401),
	THEATRE_OF_BLOOD("tob", "Theatre of Blood", 3, 5, "HM", 14642, 14386, 12611, 12612, 12613, 12867, 12869, 13122, 13123, 13125, 13379),
	TOMBS_OF_AMASCUT("toa", "Tombs of Amascut", 1, 8, "Expert", 13354, 13454, 14160, 14162, 14164, 14674, 14676, 15184, 15186, 15188, 15696, 15698, 15700),
	NEX("nex", "Nex", 1, 40, null, 11601, 11345),
	NIGHTMARE("nightmare", "The Nightmare", 1, 80, null, 15515, 15256),
	CORPOREAL_BEAST("corp", "Corporeal Beast", 1, 30, null, 11844, 11842),
	BARBARIAN_ASSAULT("ba", "Barbarian Assault", 5, 5, null, 10039),
	ZALCANO("zalcano", "Zalcano", 1, 30, null, 13150),
	HUEYCOATL("hueycoatl", "The Hueycoatl", 1, 10, null, 5939),
	YAMA("yama", "Yama", 1, 2, null, 6045),
	ROYAL_TITANS("royaltitans", "Royal Titans", 1, 2, null, 11925),
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

	/**
	 * True when this activity has a hiscore killcount a host can set a minimum for.
	 * Barbarian Assault is a minigame with no boss killcount, so its ads carry no KC bar.
	 */
	public boolean hasKillcount()
	{
		return this != BARBARIAN_ASSAULT;
	}

	public boolean isRaid()
	{
		return this == CHAMBERS_OF_XERIC || this == THEATRE_OF_BLOOD || this == TOMBS_OF_AMASCUT;
	}

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
	 * The roles a player can <em>be</em> in this activity at a given difficulty (the
	 * "my role" dropdown and the apply prompt), in display order. Empty for activities
	 * without roles.
	 */
	public List<Role> roles(boolean hardMode)
	{
		switch (this)
		{
			case THEATRE_OF_BLOOD:
				return hardMode
					? Arrays.asList(Role.TOB_HM_MELEE, Role.TOB_HM_RANGED, Role.TOB_HM_NFRZ, Role.TOB_HM_SFRZ)
					: Arrays.asList(Role.TOB_MELEE, Role.TOB_RANGED, Role.TOB_NFRZ, Role.TOB_SFRZ);
			case CHAMBERS_OF_XERIC:
				return hardMode
					? Arrays.asList(Role.COX_CM_VENG, Role.COX_CM_ANCIENT, Role.COX_CM_NORMAL, Role.COX_CM_FILL)
					: Arrays.asList(Role.COX_MELEE, Role.COX_MAGE, Role.COX_RUNNER, Role.COX_FILL);
			case BARBARIAN_ASSAULT:
				return Arrays.asList(Role.BA_ATTACKER, Role.BA_DEFENDER, Role.BA_COLLECTOR, Role.BA_HEALER);
			default:
				return Collections.emptyList();
		}
	}

	/**
	 * The roles shown in the Search filter, adding for ToB/HMT a "Fill / Any" wildcard
	 * (ToB has no Fill slot in a composition, but you can still search as "any role").
	 * CoX's Fill already doubles as the "any" option.
	 */
	public List<Role> filterRoles(boolean hardMode)
	{
		switch (this)
		{
			case THEATRE_OF_BLOOD:
				return hardMode
					? Arrays.asList(Role.TOB_HM_MELEE, Role.TOB_HM_RANGED, Role.TOB_HM_NFRZ,
						Role.TOB_HM_SFRZ, Role.TOB_HM_FILL)
					: Arrays.asList(Role.TOB_MELEE, Role.TOB_RANGED, Role.TOB_NFRZ, Role.TOB_SFRZ, Role.TOB_FILL);
			case CHAMBERS_OF_XERIC:
				return roles(hardMode); // the mode's Fill already doubles as the "any" option
			case BARBARIAN_ASSAULT:
				// BA has no Fill slot in a composition, but you can still search as "any role".
				return Arrays.asList(Role.BA_ATTACKER, Role.BA_DEFENDER, Role.BA_COLLECTOR,
					Role.BA_HEALER, Role.BA_FILL);
			default:
				return Collections.emptyList();
		}
	}

	/**
	 * Every role a player might tick across this activity's Search tabs, unioning the
	 * normal and hard-mode sets. Used to decide which ticks belong to this activity's
	 * box; matching against a party's still-needed roles then narrows it back down.
	 */
	public List<Role> allFilterRoles()
	{
		if (!hasRoles())
		{
			return Collections.emptyList();
		}
		List<Role> all = new ArrayList<>(filterRoles(false));
		all.addAll(filterRoles(true));
		return all;
	}

	public Role anyRole(boolean hardMode)
	{
		switch (this)
		{
			case THEATRE_OF_BLOOD:
				return hardMode ? Role.TOB_HM_FILL : Role.TOB_FILL;
			case CHAMBERS_OF_XERIC:
				return hardMode ? Role.COX_CM_FILL : Role.COX_FILL;
			case BARBARIAN_ASSAULT:
				return Role.BA_FILL;
			default:
				return null;
		}
	}

	/**
	 * The flexible Fill slot a host can put in a composition (Chambers of Xeric only),
	 * or null. Theatre of Blood has no Fill slot in a composition.
	 */
	public Role fillRole(boolean hardMode)
	{
		if (this == CHAMBERS_OF_XERIC)
		{
			return hardMode ? Role.COX_CM_FILL : Role.COX_FILL;
		}
		return null;
	}

	/**
	 * Theatre of Blood's fixed team composition (a role multiset) for a given party
	 * size. Freezer slots fill north-first, then south. Returns null for activities
	 * whose composition the host configures by hand (e.g. Chambers of Xeric).
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
		int freezers = Math.max(0, partySize - melee - ranged);
		// Distribute freezers north-first, then south (so a lone freezer is North).
		int north = (freezers + 1) / 2;
		int south = freezers / 2;
		for (int i = 0; i < melee; i++)
		{
			comp.add(Role.TOB_MELEE);
		}
		for (int i = 0; i < ranged; i++)
		{
			comp.add(Role.TOB_RANGED);
		}
		for (int i = 0; i < north; i++)
		{
			comp.add(Role.TOB_NFRZ);
		}
		for (int i = 0; i < south; i++)
		{
			comp.add(Role.TOB_SFRZ);
		}
		return comp;
	}

	/**
	 * True when this activity's composition is fixed by party size: normal Theatre of
	 * Blood only. HMT comps vary by team, so the host configures it by hand.
	 */
	public boolean hasFixedComposition(boolean hardMode)
	{
		return this == THEATRE_OF_BLOOD && !hardMode;
	}

	public boolean hasRoles()
	{
		return this == THEATRE_OF_BLOOD || this == CHAMBERS_OF_XERIC || this == BARBARIAN_ASSAULT;
	}

	/**
	 * True when the team composition isn't a fixed multiset the host lays out slot by
	 * slot, but a set of roles applicants pick freely: every base role is wanted at
	 * least once, then any spare slot may double a role up to {@link #roleCap()}, with
	 * the whole team capped at the party size (Barbarian Assault). "For now" the host
	 * doesn't choose which role gets doubled — whoever applies decides.
	 */
	public boolean hasFlexibleRoles()
	{
		return this == BARBARIAN_ASSAULT;
	}

	/** How many times a single role may appear in a team: two for Barbarian Assault, else one. */
	public int roleCap()
	{
		return this == BARBARIAN_ASSAULT ? 2 : 1;
	}

	/**
	 * The roles still open for a {@link #hasFlexibleRoles() flexible} activity given the
	 * roles already taken and the team {@code capacity}. Every base role is mandatory
	 * once; while a spare slot remains, a role that's under its {@link #roleCap()} may
	 * still be picked (the "extra"), so once a role hits its cap it drops out. Returns an
	 * empty list when the team is full or this activity isn't flexible.
	 */
	public List<String> flexibleNeededRoles(List<String> takenRoles, int capacity)
	{
		if (!hasFlexibleRoles())
		{
			return Collections.emptyList();
		}
		java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
		for (Role role : roles(false))
		{
			counts.put(role.getId(), 0);
		}
		int taken = 0;
		if (takenRoles != null)
		{
			for (String id : takenRoles)
			{
				taken++;
				if (counts.containsKey(id))
				{
					counts.merge(id, 1, Integer::sum);
				}
			}
		}
		int openSlots = Math.max(0, capacity - taken);
		if (openSlots == 0)
		{
			return new ArrayList<>();
		}
		int emptyRoles = 0;
		for (int count : counts.values())
		{
			if (count == 0)
			{
				emptyRoles++;
			}
		}
		// A spare slot beyond covering every still-empty base role is what lets a role double up.
		boolean allowExtra = openSlots > emptyRoles;
		int cap = roleCap();
		List<String> needed = new ArrayList<>();
		for (java.util.Map.Entry<String, Integer> entry : counts.entrySet())
		{
			int count = entry.getValue();
			if (count == 0 || (allowExtra && count < cap))
			{
				needed.add(entry.getKey());
			}
		}
		return needed;
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
