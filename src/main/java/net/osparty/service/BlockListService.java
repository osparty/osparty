package net.osparty.service;

import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.osparty.model.Party;
import net.osparty.store.FlagKind;
import net.osparty.store.PartyStore;
import net.osparty.store.PlayerFlag;

/**
 * Locally-stored avoid/block list — the inverse of {@link FavoritesService}. A party
 * is considered blocked when its host (or any listed member) is on the list; such
 * parties are hidden from search by default. Entries are keyed by accountHash when
 * known so a block survives the blocked player changing their name.
 */
@Singleton
public class BlockListService extends PlayerFlagService
{
	private volatile LongSupplier selfHash;
	private volatile Supplier<String> selfName;

	@Inject
    public BlockListService(PartyStore store)
	{
		super(store, FlagKind.BLOCK);
	}

	/** Register the local player's identity so we never let them block themselves. */
	public void setSelf(LongSupplier selfHash, Supplier<String> selfName)
	{
		this.selfHash = selfHash;
		this.selfName = selfName;
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

	/**
	 * @return true when {@code accountHash}/{@code name} is the local player. Used to refuse
	 * a self-block (you can't block yourself), matching by hash when known, else by name.
	 */
	public boolean isSelf(long accountHash, String name)
	{
		long myHash = selfHash != null ? selfHash.getAsLong() : PlayerFlag.UNKNOWN_HASH;
		if (PlayerFlag.isKnown(accountHash) && PlayerFlag.isKnown(myHash) && accountHash == myHash)
		{
			return true;
		}
		String myName = selfName != null ? selfName.get() : null;
		return myName != null && name != null && normalize(myName).equals(normalize(name));
	}

	/** Refuse to add yourself to the block list; unblocking (if somehow present) still works. */
	@Override
	public synchronized void toggle(long accountHash, String name)
	{
		if (!isBlocked(accountHash, name) && isSelf(accountHash, name))
		{
			return;
		}
		super.toggle(accountHash, name);
	}
}
