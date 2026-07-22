package net.osparty.service;

import net.osparty.model.Activity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.hiscore.HiscoreClient;
import net.runelite.client.hiscore.HiscoreEndpoint;
import net.runelite.client.hiscore.HiscoreResult;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.Skill;

/**
 * Looks up a player's killcount for an activity from the OSRS hiscores, since we
 * can't read another account's boss KC locally. Results are cached per
 * (player, activity); the normal hiscores endpoint covers every account type.
 */
@Slf4j
@Singleton
public class KillcountService
{
	private static final long FAILURE_RETRY_MS = 60_000L;

	/** {@code -1} = unknown / unranked. */
	public static final class Killcount
	{
		public final int killCount;
		public final int hardModeKillCount;
		public final boolean unavailable;
		private final long fetchedAt;

		Killcount(int killCount, int hardModeKillCount, boolean unavailable)
		{
			this.killCount = killCount;
			this.hardModeKillCount = hardModeKillCount;
			this.unavailable = unavailable;
			this.fetchedAt = System.currentTimeMillis();
		}

		public boolean isKnown(boolean hardMode)
		{
			return !unavailable && (hardMode ? hardModeKillCount : killCount) >= 0;
		}

		private boolean isStale()
		{
			return unavailable && System.currentTimeMillis() - fetchedAt > FAILURE_RETRY_MS;
		}
	}

	private final HiscoreClient hiscoreClient;
	private final Map<String, Killcount> cache = new ConcurrentHashMap<>();
	/** Guards {@link #inFlight} and {@link #waiting} together, so a callback is never dropped on a race. */
	private final Object lock = new Object();
	private final Set<String> inFlight = new HashSet<>();
	private final Map<String, List<Runnable>> waiting = new HashMap<>();

	@Inject
	private KillcountService(HiscoreClient hiscoreClient)
	{
		this.hiscoreClient = hiscoreClient;
	}

	public Killcount cached(String rsn, Activity activity)
	{
		if (rsn == null || activity == null)
		{
			return null;
		}
		return cache.get(key(rsn, activity));
	}

	/**
	 * Look up the activity killcount for {@code rsn}, hitting the hiscores at most once per
	 * (player, activity) — a failed lookup is retried after {@link #FAILURE_RETRY_MS}.
	 * {@code onComplete} always fires on the EDT, including when the result is already cached
	 * or another caller's lookup is already in flight; callers may rely on it to resume work.
	 */
	public void lookup(String rsn, Activity activity, Runnable onComplete)
	{
		if (rsn == null || activity == null)
		{
			return;
		}
		String key = key(rsn, activity);

		synchronized (lock)
		{
			Killcount cached = cache.get(key);
			if (cached == null || cached.isStale())
			{
				if (onComplete != null)
				{
					waiting.computeIfAbsent(key, k -> new ArrayList<>()).add(onComplete);
				}
				if (!inFlight.add(key))
				{
					// Someone else is already fetching this; our callback rides along with theirs.
					return;
				}
			}
			else
			{
				// Already known: answer immediately rather than dropping the callback.
				if (onComplete != null)
				{
					SwingUtilities.invokeLater(onComplete);
				}
				return;
			}
		}

		HiscoreSkill skill = skillFor(activity);
		if (skill == null)
		{
			// Activity has no hiscore killcount (minigames etc.).
			complete(key, new Killcount(-1, -1, false));
			return;
		}

		try
		{
			hiscoreClient.lookupAsync(rsn, HiscoreEndpoint.NORMAL).whenComplete((result, ex) -> {
				int kc = -1;
				int hard = -1;
				boolean unavailable = ex != null || result == null;
				try
				{
					if (!unavailable)
					{
						kc = count(result, skill);
						HiscoreSkill hardSkill = hardSkillFor(activity);
						if (hardSkill != null)
						{
							hard = count(result, hardSkill);
						}
					}
					else
					{
						log.debug("Hiscore lookup failed for {}", rsn, ex);
					}
				}
				catch (RuntimeException e)
				{
					log.debug("Hiscore result unreadable for {}", rsn, e);
					unavailable = true;
				}
				complete(key, new Killcount(kc, hard, unavailable));
			});
		}
		catch (RuntimeException e)
		{
			log.debug("Hiscore lookup could not be started for {}", rsn, e);
			complete(key, new Killcount(-1, -1, true));
		}
	}

	private void complete(String key, Killcount result)
	{
		List<Runnable> callbacks;
		synchronized (lock)
		{
			cache.put(key, result);
			inFlight.remove(key);
			callbacks = waiting.remove(key);
		}
		if (callbacks != null)
		{
			for (Runnable callback : callbacks)
			{
				SwingUtilities.invokeLater(callback);
			}
		}
	}

	private static int count(HiscoreResult result, HiscoreSkill skill)
	{
		Skill s = result.getSkill(skill);
		return s == null ? -1 : s.getLevel();
	}

	private static String key(String rsn, Activity activity)
	{
		return rsn.replace('\u00A0', ' ').trim().toLowerCase() + "|" + activity.getId();
	}

	private static HiscoreSkill skillFor(Activity activity)
	{
		switch (activity)
		{
			case CHAMBERS_OF_XERIC:
				return HiscoreSkill.CHAMBERS_OF_XERIC;
			case THEATRE_OF_BLOOD:
				return HiscoreSkill.THEATRE_OF_BLOOD;
			case TOMBS_OF_AMASCUT:
				return HiscoreSkill.TOMBS_OF_AMASCUT;
			case NEX:
				return HiscoreSkill.NEX;
			case NIGHTMARE:
				return HiscoreSkill.NIGHTMARE;
			case CORPOREAL_BEAST:
				return HiscoreSkill.CORPOREAL_BEAST;
			case ZALCANO:
				return HiscoreSkill.ZALCANO;
			case HUEYCOATL:
				return HiscoreSkill.THE_HUEYCOATL;
			case YAMA:
				return HiscoreSkill.YAMA;
			case ROYAL_TITANS:
				return HiscoreSkill.THE_ROYAL_TITANS;
			case KREEARRA:
				return HiscoreSkill.KREEARRA;
			case GENERAL_GRAARDOR:
				return HiscoreSkill.GENERAL_GRAARDOR;
			case KRIL_TSUTSAROTH:
				return HiscoreSkill.KRIL_TSUTSAROTH;
			case COMMANDER_ZILYANA:
				return HiscoreSkill.COMMANDER_ZILYANA;
			default:
				return null; // BA and Volcanic Mine have no boss killcount
		}
	}

	private static HiscoreSkill hardSkillFor(Activity activity)
	{
		switch (activity)
		{
			case CHAMBERS_OF_XERIC:
				return HiscoreSkill.CHAMBERS_OF_XERIC_CHALLENGE_MODE;
			case THEATRE_OF_BLOOD:
				return HiscoreSkill.THEATRE_OF_BLOOD_HARD_MODE;
			case TOMBS_OF_AMASCUT:
				return HiscoreSkill.TOMBS_OF_AMASCUT_EXPERT;
			default:
				return null;
		}
	}
}
