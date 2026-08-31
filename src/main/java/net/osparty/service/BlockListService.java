package net.osparty.service;

import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.osparty.model.Advertisement;
import net.osparty.store.FlagKind;
import net.osparty.store.PartyStore;
import net.osparty.store.PlayerFlag;

/**
 * Locally-stored avoid/block list — the inverse of {@link FavoritesService}. A party
 * is considered blocked when its host (or any listed member) is on the list; such
 * parties are hidden from search by default. Entries are keyed by playerId when
 * known so a block survives the blocked player changing their name.
 */
@Singleton
public class BlockListService extends PlayerFlagService
{
	private volatile Supplier<String> selfIdSupplier;
	private volatile Supplier<String> selfNameSupplier;

	@Inject
	public BlockListService(PartyStore store)
	{
		super(store, FlagKind.BLOCK);
	}

	/** Register the local player's identity so we never let them block themselves. */
	public void setSelf(Supplier<String> selfIdSupplier, Supplier<String> selfNameSupplier)
	{
		this.selfIdSupplier = selfIdSupplier;
		this.selfNameSupplier = selfNameSupplier;
	}

	public boolean isBlocked(String playerId, String name)
	{
		return isFlagged(playerId, name);
	}

	public boolean isBlocked(String name)
	{
		return isFlagged(name);
	}

	public boolean hasAnyBlocked(Advertisement ad)
	{
		return hasAnyFlagged(ad);
	}

	/**
	 * @return true when {@code playerId}/{@code name} is the local player. Used to refuse
	 * a self-block (you can't block yourself), matching by id when both are known, else by name.
	 */
	public boolean isSelf(String playerId, String name)
	{
		String myId = selfIdSupplier != null ? selfIdSupplier.get() : null;
		if (PlayerFlag.isKnown(playerId) && PlayerFlag.isKnown(myId) && playerId.equals(myId))
		{
			return true;
		}
		String myName = selfNameSupplier != null ? selfNameSupplier.get() : null;
		return myName != null && name != null && normalize(myName).equals(normalize(name));
	}

	/** Refuse to add yourself to the block list; unblocking (if somehow present) still works. */
	@Override
	public synchronized void toggle(String playerId, String name)
	{
		if (!isBlocked(playerId, name) && isSelf(playerId, name))
		{
			return;
		}
		super.toggle(playerId, name);
	}
}
