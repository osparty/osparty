package net.osparty.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.osparty.model.Member;
import net.osparty.model.Party;
import net.osparty.store.FlagKind;
import net.osparty.store.PartyStore;
import net.osparty.store.PlayerFlag;

/**
 * Shared logic for a per-player flag list (favourites or blocks), backed by
 * {@link PartyStore}. Entries are keyed by {@code accountHash} when known so they
 * survive name changes; entries whose hash isn't known yet (e.g. migrated from the
 * old name-only favourites) fall back to matching on the normalised username and are
 * upgraded to a hash the first time we {@link #observe} that player in a party.
 *
 * <p>All state is guarded by the instance monitor; callers are the Swing EDT and the
 * socket reader thread.
 */
public abstract class PlayerFlagService
{
	private final PartyStore store;
	private final FlagKind kind;

	/** accountHash (&gt; 0) -&gt; normalised username. */
	private final Map<Long, String> byHash = new HashMap<>();
	/** Normalised usernames flagged without a known hash. */
	private final Set<String> nameOnly = new HashSet<>();
	/** Union of {@link #nameOnly} and {@link #byHash} values, for name-based matching. */
	private final Set<String> flaggedNames = new HashSet<>();

	protected PlayerFlagService(PartyStore store, FlagKind kind)
	{
		this.store = store;
		this.kind = kind;
		reload();
	}

	protected final synchronized void reload()
	{
		byHash.clear();
		nameOnly.clear();
		for (PlayerFlag flag : store.loadFlags(kind))
		{
			String norm = normalize(flag.getUsername());
			if (flag.hasKnownHash())
			{
				byHash.put(flag.getAccountHash(), norm);
			}
			else
			{
				nameOnly.add(norm);
			}
		}
		rebuildNames();
	}

	/** True when this player is flagged, by hash when known, else by name. */
	public synchronized boolean isFlagged(long accountHash, String name)
	{
		if (PlayerFlag.isKnown(accountHash) && byHash.containsKey(accountHash))
		{
			return true;
		}
		return name != null && flaggedNames.contains(normalize(name));
	}

	/** Name-only convenience check (no hash available at the call site). */
	public boolean isFlagged(String name)
	{
		return isFlagged(0L, name);
	}

	/** True when the host or any listed member of {@code party} is flagged. */
	public synchronized boolean hasAnyFlagged(Party party)
	{
		if (party == null)
		{
			return false;
		}
		if (isFlagged(party.getHostAccountHash(), party.getHost()))
		{
			return true;
		}
		List<Member> members = party.getMembers();
		if (members != null)
		{
			for (Member member : members)
			{
				if (member != null && isFlagged(member.getAccountHash(), member.getName()))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Add the player if not flagged, remove if flagged. Persists the change. */
	public synchronized void toggle(long accountHash, String name)
	{
		if (name == null)
		{
			return;
		}
		String norm = normalize(name);
		if (isFlagged(accountHash, name))
		{
			// Remove every representation of this player (hash row and/or name-only row).
			Long byNameHash = hashForName(norm);
			if (PlayerFlag.isKnown(accountHash) && byHash.remove(accountHash) != null)
			{
				store.removeFlag(kind, new PlayerFlag(accountHash, norm));
			}
			if (byNameHash != null && byHash.remove(byNameHash) != null)
			{
				store.removeFlag(kind, new PlayerFlag(byNameHash, norm));
			}
			if (nameOnly.remove(norm))
			{
				store.removeFlag(kind, new PlayerFlag(PlayerFlag.UNKNOWN_HASH, norm));
			}
		}
		else if (PlayerFlag.isKnown(accountHash))
		{
			byHash.put(accountHash, norm);
			store.upsertFlag(kind, new PlayerFlag(accountHash, norm));
		}
		else
		{
			nameOnly.add(norm);
			store.upsertFlag(kind, new PlayerFlag(PlayerFlag.UNKNOWN_HASH, norm));
		}
		rebuildNames();
	}

	/** Name-only toggle (no hash available at the call site). */
	public void toggle(String name)
	{
		toggle(0L, name);
	}

	/**
	 * Record that {@code accountHash} currently goes by {@code name}: renames a stored
	 * entry when the name changed, and backfills the hash onto a name-only entry. No-op
	 * when the hash is unknown or the player isn't flagged.
	 */
	public synchronized void observe(long accountHash, String name)
	{
		if (!PlayerFlag.isKnown(accountHash) || name == null)
		{
			return;
		}
		String norm = normalize(name);
		String known = byHash.get(accountHash);
		if (known != null)
		{
			if (!known.equals(norm))
			{
				byHash.put(accountHash, norm);
				store.upsertFlag(kind, new PlayerFlag(accountHash, norm));
				rebuildNames();
			}
		}
		else if (nameOnly.contains(norm))
		{
			nameOnly.remove(norm);
			byHash.put(accountHash, norm);
			// upsert with a known hash also clears the stale name-only row.
			store.upsertFlag(kind, new PlayerFlag(accountHash, norm));
			rebuildNames();
		}
	}

	/** Apply {@link #observe} to the host and every listed member of a party. */
	public synchronized void observeParty(Party party)
	{
		if (party == null)
		{
			return;
		}
		observe(party.getHostAccountHash(), party.getHost());
		if (party.getMembers() != null)
		{
			for (Member member : party.getMembers())
			{
				if (member != null)
				{
					observe(member.getAccountHash(), member.getName());
				}
			}
		}
	}

	/** Read-only view of all flagged (normalised) usernames. */
	public synchronized Set<String> getAll()
	{
		return Collections.unmodifiableSet(new HashSet<>(flaggedNames));
	}

	/** All flagged entries (hash + last-known name), for management UIs. */
	public synchronized List<PlayerFlag> entries()
	{
		List<PlayerFlag> out = new ArrayList<>();
		for (Map.Entry<Long, String> e : byHash.entrySet())
		{
			out.add(new PlayerFlag(e.getKey(), e.getValue()));
		}
		for (String name : nameOnly)
		{
			out.add(new PlayerFlag(PlayerFlag.UNKNOWN_HASH, name));
		}
		return out;
	}

	/** Import a flag without toggling (used for one-time migrations). */
	protected synchronized void importFlag(long accountHash, String name)
	{
		if (name == null)
		{
			return;
		}
		String norm = normalize(name);
		if (PlayerFlag.isKnown(accountHash))
		{
			byHash.put(accountHash, norm);
			store.upsertFlag(kind, new PlayerFlag(accountHash, norm));
		}
		else if (!flaggedNames.contains(norm) && !nameOnly.contains(norm))
		{
			nameOnly.add(norm);
			store.upsertFlag(kind, new PlayerFlag(PlayerFlag.UNKNOWN_HASH, norm));
		}
		rebuildNames();
	}

	private Long hashForName(String norm)
	{
		for (Map.Entry<Long, String> e : byHash.entrySet())
		{
			if (e.getValue().equals(norm))
			{
				return e.getKey();
			}
		}
		return null;
	}

	private void rebuildNames()
	{
		flaggedNames.clear();
		flaggedNames.addAll(nameOnly);
		flaggedNames.addAll(byHash.values());
	}

	/** Normalise a name for storage/comparison (RuneLite uses nbsp in player names). */
	public static String normalize(String name)
	{
		return name == null ? "" : name.replace(' ', ' ').trim().toLowerCase();
	}
}
