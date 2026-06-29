package net.osparty;

import net.osparty.model.Party;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/**
 * Locally-stored list of "favourite" player names. A party is flagged as having
 * a favourite when any member (host or joined) is in this set. Stored under the
 * OSParty config group so it survives restarts alongside the regular filter prefs.
 */
@Singleton
public class FavoritesService
{
	private static final String KEY = "localFavorites";

	private final ConfigManager configManager;
	private Set<String> favorites;

	@Inject
	FavoritesService(ConfigManager configManager)
	{
		this.configManager = configManager;
		load();
	}

	/** True when the supplied name is in the favourites list (case / nbsp-insensitive). */
	public boolean isFavorite(String playerName)
	{
		return playerName != null && favorites.contains(normalize(playerName));
	}

	/**
	 * True when the host <em>or</em> any listed member of {@code party} is a favourite.
	 * Always false when the party has no host.
	 */
	public boolean hasAnyFavorite(Party party)
	{
		if (party == null)
		{
			return false;
		}
		if (isFavorite(party.getHost()))
		{
			return true;
		}
		List<String> members = party.getMembers();
		if (members != null)
		{
			for (String member : members)
			{
				if (isFavorite(member))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Add {@code playerName} if absent, remove if present. */
	public void toggle(String playerName)
	{
		if (playerName == null)
		{
			return;
		}
		String key = normalize(playerName);
		if (favorites.contains(key))
		{
			favorites.remove(key);
		}
		else
		{
			favorites.add(key);
		}
		save();
	}

	/** Read-only view of all saved favourite names (normalised). */
	public Set<String> getAll()
	{
		return Collections.unmodifiableSet(favorites);
	}

	private void load()
	{
		favorites = new HashSet<>();
		String saved = configManager.getConfiguration(OSPartyConfig.GROUP, KEY);
		if (saved != null && !saved.isEmpty())
		{
			for (String name : saved.split(","))
			{
				String trimmed = name.trim();
				if (!trimmed.isEmpty())
				{
					favorites.add(trimmed);
				}
			}
		}
	}

	private void save()
	{
		configManager.setConfiguration(OSPartyConfig.GROUP, KEY,
			favorites.isEmpty() ? "" : String.join(",", favorites));
	}

	/** Normalise a name for storage and comparison (RuneLite uses nbsp in player names). */
	public static String normalize(String name)
	{
		return name == null ? "" : name.replace('\u00A0', ' ').trim().toLowerCase();
	}
}
