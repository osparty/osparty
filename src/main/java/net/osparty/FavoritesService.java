package net.osparty;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.osparty.model.Party;
import net.osparty.store.FlagKind;
import net.osparty.store.PartyStore;
import net.osparty.store.PlayerFlag;
import net.runelite.client.config.ConfigManager;

/**
 * Locally-stored list of "favourite" players. A party is flagged as having a
 * favourite when its host (or any listed member) is favourited. Entries are keyed by
 * accountHash when known (so they survive name changes) and persisted in
 * {@link PartyStore}; see {@link PlayerFlagService} for the shared logic.
 *
 * <p>On first run the legacy name-only favourites (stored as a CSV under the OSParty
 * config group) are imported into the store and the old key is cleared.
 */
@Singleton
public class FavoritesService extends PlayerFlagService
{
	/** Legacy config key holding the pre-database, name-only favourites CSV. */
	private static final String LEGACY_KEY = "localFavorites";

	@Inject
	FavoritesService(PartyStore store, ConfigManager configManager)
	{
		super(store, FlagKind.FAVORITE);
		migrateLegacy(configManager);
	}

	private void migrateLegacy(ConfigManager configManager)
	{
		String saved = configManager.getConfiguration(OSPartyConfig.GROUP, LEGACY_KEY);
		if (saved == null || saved.isEmpty())
		{
			return;
		}
		for (String name : saved.split(","))
		{
			String trimmed = name.trim();
			if (!trimmed.isEmpty())
			{
				// Hash unknown for legacy favourites; backfilled when next seen in a party.
				importFlag(PlayerFlag.UNKNOWN_HASH, trimmed);
			}
		}
		// Clear the old key so we don't re-import (the store is now the source of truth).
		configManager.setConfiguration(OSPartyConfig.GROUP, LEGACY_KEY, "");
	}

	// --- Favourite-named aliases over the generic flag API (keeps call sites readable) ---

	public boolean isFavorite(long accountHash, String name)
	{
		return isFlagged(accountHash, name);
	}

	public boolean isFavorite(String name)
	{
		return isFlagged(name);
	}

	public boolean hasAnyFavorite(Party party)
	{
		return hasAnyFlagged(party);
	}

	/** Static normalisation shim retained for callers that used {@code FavoritesService.normalize}. */
	public static String normalize(String name)
	{
		return PlayerFlagService.normalize(name);
	}
}
