package net.osparty.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.osparty.model.Activity;
import net.osparty.model.Advertisement;
import net.osparty.model.Member;
import net.osparty.model.Role;

/** Ad-derived text and member lookups shared by the party roster and the party cards. */
final class AdText
{
	private AdText()
	{
	}

	/** "Req: ..." for an ad's killcount requirements, or {@code null} when it has none. */
	static String requirementText(Activity activity, Advertisement ad)
	{
		if (ad.getMinKillCount() <= 0 && ad.getMinHardModeKillCount() <= 0)
		{
			return null;
		}
		StringBuilder req = new StringBuilder("Req: ");
		boolean any = false;
		if (ad.getMinKillCount() > 0)
		{
			req.append(ad.getMinKillCount()).append(" KC");
			any = true;
		}
		if (activity != null && activity.hasHardMode() && ad.getMinHardModeKillCount() > 0)
		{
			if (any)
			{
				req.append(", ");
			}
			req.append(ad.getMinHardModeKillCount()).append(' ').append(activity.getHardModeLabel())
				.append(" KC");
		}
		return req.toString();
	}

	/** "Needs: ..." for the roles still open, or {@code null} when none are. */
	static String neededRolesText(Activity activity, List<String> needed)
	{
		if (activity == null || !activity.hasRoles() || needed == null || needed.isEmpty())
		{
			return null;
		}
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String id : needed)
		{
			counts.merge(id, 1, Integer::sum);
		}
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : counts.entrySet())
		{
			String name = Role.displayNameOf(entry.getKey());
			parts.add(entry.getValue() > 1 ? name + " x" + entry.getValue() : name);
		}
		return "Needs: " + String.join(", ", parts);
	}

	/**
	 * Badges for the ad member matching {@code hash}, then {@code name}, or {@code null}. Never match
	 * on list position: a host transfer rewrites the host without touching the member list, so member
	 * zero is the outgoing host from the moment a party changes hands.
	 */
	static List<String> badgesFor(List<Member> members, long hash, String name)
	{
		if (members == null)
		{
			return null;
		}
		if (hash != 0)
		{
			for (Member member : members)
			{
				if (member != null && member.getAccountHash() == hash)
				{
					return member.getBadges();
				}
			}
		}
		if (name != null)
		{
			for (Member member : members)
			{
				if (member != null && sameName(member.getName(), name))
				{
					return member.getBadges();
				}
			}
		}
		return null;
	}

	/** Normalise a player name for comparison (RuneLite renders spaces in names as nbsp). */
	static String normalizeName(String name)
	{
		return name == null ? "" : name.replace('\u00A0', ' ').trim();
	}

	/** True if two RuneScape names refer to the same account (case- and space-insensitive). */
	static boolean sameName(String a, String b)
	{
		return a != null && b != null && normalizeName(a).equalsIgnoreCase(normalizeName(b));
	}
}
