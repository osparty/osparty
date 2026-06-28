package net.osparty;

import net.runelite.client.config.ConfigManager;

/**
 * Reads the local player's personal-best time for an activity from the data
 * RuneLite's chat-commands plugin records (config group {@code personalbest},
 * key {@code "<boss> <team size>"}, value in seconds) — the same data behind the
 * {@code !pb} command. Only the local account's own PBs are readable, so each
 * client reads and broadcasts its own.
 */
public final class PersonalBests
{
	private PersonalBests()
	{
	}

	public static double read(ConfigManager configManager, String activityId, int teamSize)
	{
		String boss = bossName(activityId);
		if (boss == null)
		{
			return -1;
		}

		Double pb = null;
		String size = teamSizeLabel(teamSize);
		if (size != null)
		{
			pb = configManager.getRSProfileConfiguration("personalbest", (boss + " " + size), double.class);
		}
		if (pb == null)
		{
			// Fall back to the player's overall PB for the activity.
			pb = configManager.getRSProfileConfiguration("personalbest", boss, double.class);
		}
		return pb != null ? pb : -1;
	}

	public static boolean isPbActivity(String activityId)
	{
		return bossName(activityId) != null;
	}

	/** Format seconds as {@code m:ss.t}. */
	public static String format(double seconds)
	{
		int tenths = (int) Math.round(seconds * 10);
		int mins = tenths / 600;
		int secs = (tenths / 10) % 60;
		int frac = tenths % 10;
		return String.format("%d:%02d.%d", mins, secs, frac);
	}

	private static String bossName(String activityId)
	{
		if (activityId == null)
		{
			return null;
		}
		switch (activityId)
		{
			case "cox":
				return "chambers of xeric";
			case "tob":
				return "theatre of blood";
			case "toa":
				return "tombs of amascut";
			case "nex":
				return "nex";
			case "nightmare":
				return "nightmare";
			case "inferno":
				return "tzkal-zuk";
			default:
				return null; // no timed PB (Wintertodt, Corp, minigames, …)
		}
	}

	private static String teamSizeLabel(int teamSize)
	{
		if (teamSize <= 0)
		{
			return null;
		}
		return teamSize == 1 ? "solo" : teamSize + " players";
	}
}
