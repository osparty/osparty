package net.osparty;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.osparty.model.Party;
import net.osparty.store.FlagKind;
import net.osparty.store.PartyStore;

/**
 * Locally-stored avoid/block list — the inverse of {@link FavoritesService}. A party
 * is considered blocked when its host (or any listed member) is on the list; such
 * parties are hidden from search by default. Entries are keyed by accountHash when
 * known so a block survives the blocked player changing their name.
 */
@Singleton
public class BlockListService extends PlayerFlagService
{
	@Inject
	BlockListService(PartyStore store)
	{
		super(store, FlagKind.BLOCK);
	}

	public boolean isBlocked(long accountHash, String name)
	{
		return isFlagged(accountHash, name);
	}

	public boolean isBlocked(String name)
	{
		return isFlagged(name);
	}

	public boolean hasAnyBlocked(Party party)
	{
		return hasAnyFlagged(party);
	}
}
