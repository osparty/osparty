package net.osparty.ui;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import net.osparty.model.Activity;
import net.osparty.model.Advertisement;
import net.osparty.model.LootRule;
import net.runelite.http.api.worlds.WorldRegion;

/**
 * A snapshot of what the Search tab is currently filtering on, taken off its controls so anything
 * else can ask the same question the list asks: <em>would this party show up for me?</em>
 *
 * <p>The list answers it once per push to decide what to draw; the matchmaking watcher answers it to
 * decide what is worth interrupting the player for. Keeping one answer means a party offered in-game
 * is always a party they would have seen in the panel.
 *
 * <p>Everything an advertisement cannot answer about itself (is this host blocked, do I have the
 * killcount, how far is that world) comes in through {@link Context}, which keeps this a value
 * object that can be built and tested without a panel.
 */
public final class SearchFilters
{
	/** How the learner filter is set. */
	public enum Learner
	{
		ANY,
		/** Only parties marked as a learner raid. */
		ONLY,
		/** Hide parties marked as a learner raid. */
		HIDE
	}

	/** The lookups a filter needs that live outside the advertisement. */
	public interface Context
	{
		boolean blocked(Advertisement ad);

		boolean meetsIronmanRule(Advertisement ad);

		/** True only for a settled below-minimum killcount; a check still in flight is not below. */
		boolean killcountBelow(Advertisement ad);

		boolean matchesRoles(Advertisement ad, Activity activity);

		boolean matchesText(Advertisement ad, Activity activity, String text);

		/** The host's world number, or null when the ad does not say or it will not parse. */
		Integer worldOf(Advertisement ad);

		WorldRegion regionOf(int world);

		/** Last measured ping to {@code world}, or null when it has not been measured yet. */
		Integer pingOf(int world);
	}

	private final Set<Activity> activities;
	private final Set<WorldRegion> regions;
	private final boolean regionFilterActive;
	private final LootRule loot;
	private final boolean ironmanOnly;
	private final boolean hideIneligible;
	private final Learner learner;
	private final String text;
	private final int maxPing;
	private final boolean showBlocked;

	private SearchFilters(Builder builder)
	{
		this.activities = builder.activities;
		this.regions = builder.regions;
		this.regionFilterActive = builder.regionFilterActive;
		this.loot = builder.loot;
		this.ironmanOnly = builder.ironmanOnly;
		this.hideIneligible = builder.hideIneligible;
		this.learner = builder.learner;
		this.text = builder.text;
		this.maxPing = builder.maxPing;
		this.showBlocked = builder.showBlocked;
	}

	public static Builder builder()
	{
		return new Builder();
	}

	/**
	 * The first gate, applied before any filter: a party has to have room and not be hidden by the block
	 * list. This is also what counts towards the panel's "X of Y open parties", so it is separate from
	 * {@link #matches}, which is everything the player chose.
	 */
	public boolean joinable(Advertisement ad, Context context)
	{
		if (ad == null || ad.isFull())
		{
			return false;
		}
		return showBlocked || !context.blocked(ad);
	}

	/** Whether {@code ad} survives every filter the player has set. Assumes {@link #joinable} passed. */
	public boolean matches(Advertisement ad, Activity activity, Context context)
	{
		if (activity == null || !activities.contains(activity))
		{
			return false;
		}
		if (loot != null && LootRule.fromName(ad.getLootRule()) != loot)
		{
			return false;
		}
		if (ironmanOnly && !ad.isIronmanOnly())
		{
			return false;
		}
		if (learner == Learner.ONLY && !ad.isLearnerRaid())
		{
			return false;
		}
		if (learner == Learner.HIDE && ad.isLearnerRaid())
		{
			return false;
		}
		if (hideIneligible && (!context.meetsIronmanRule(ad) || context.killcountBelow(ad)))
		{
			return false;
		}
		if (!context.matchesRoles(ad, activity))
		{
			return false;
		}
		if (!text.isEmpty() && !context.matchesText(ad, activity, text))
		{
			return false;
		}

		Integer world = context.worldOf(ad);
		if (world == null)
		{
			return true; // no world to judge by, so the region and ping filters cannot exclude it
		}
		if (regionFilterActive)
		{
			WorldRegion region = context.regionOf(world);
			if (region != null && !regions.contains(region))
			{
				return false;
			}
		}
		if (maxPing > 0)
		{
			Integer ping = context.pingOf(world);
			// An unmeasured world is always shown; excluding it would hide parties until pings land.
			if (ping != null && ping >= 0 && ping > maxPing)
			{
				return false;
			}
		}
		return true;
	}

	public static final class Builder
	{
		private Set<Activity> activities = EnumSet.allOf(Activity.class);
		private Set<WorldRegion> regions = Collections.emptySet();
		private boolean regionFilterActive;
		private LootRule loot;
		private boolean ironmanOnly;
		private boolean hideIneligible;
		private Learner learner = Learner.ANY;
		private String text = "";
		private int maxPing;
		private boolean showBlocked;

		public Builder activities(Set<Activity> activities)
		{
			this.activities = activities == null || activities.isEmpty()
				? EnumSet.noneOf(Activity.class)
				: EnumSet.copyOf(activities);
			return this;
		}

		/**
		 * @param regions the regions still selected; the filter counts as off when every known region is,
		 * which is why {@code known} is passed rather than assumed.
		 */
		public Builder regions(Set<WorldRegion> regions, WorldRegion[] known)
		{
			this.regions = regions == null ? Collections.emptySet() : EnumSet.copyOf(regions);
			this.regionFilterActive = false;
			for (WorldRegion region : known)
			{
				if (!this.regions.contains(region))
				{
					this.regionFilterActive = true;
					break;
				}
			}
			return this;
		}

		public Builder loot(LootRule loot)
		{
			this.loot = loot;
			return this;
		}

		public Builder ironmanOnly(boolean ironmanOnly)
		{
			this.ironmanOnly = ironmanOnly;
			return this;
		}

		public Builder hideIneligible(boolean hideIneligible)
		{
			this.hideIneligible = hideIneligible;
			return this;
		}

		public Builder learner(Learner learner)
		{
			this.learner = learner == null ? Learner.ANY : learner;
			return this;
		}

		public Builder text(String text)
		{
			this.text = text == null ? "" : text.trim().toLowerCase();
			return this;
		}

		/** @param maxPing milliseconds, or {@code <= 0} for no ping limit. */
		public Builder maxPing(int maxPing)
		{
			this.maxPing = maxPing;
			return this;
		}

		public Builder showBlocked(boolean showBlocked)
		{
			this.showBlocked = showBlocked;
			return this;
		}

		public SearchFilters build()
		{
			return new SearchFilters(this);
		}
	}
}
