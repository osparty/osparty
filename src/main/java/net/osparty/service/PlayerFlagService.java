package net.osparty.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.osparty.model.Member;
import net.osparty.model.Advertisement;
import net.osparty.store.FlagKind;
import net.osparty.store.PartyStore;
import net.osparty.store.PlayerFlag;

/**
 * Shared logic for a per-player flag list (favourites or blocks), backed by
 * {@link PartyStore}. Entries are keyed by {@code playerId} when known so they
 * survive name changes; entries whose id isn't known yet (e.g. migrated from the
 * old name-only favourites, or from a file keyed by account hash) fall back to
 * matching on the normalised username and are upgraded to an id the first time we
 * {@link #observe} that player in a party.
 *
 * <p>All state is guarded by the instance monitor; callers are the Swing EDT and the
 * socket reader thread.
 */
public abstract class PlayerFlagService
{
	private final PartyStore store;
	private final FlagKind kind;

	/** playerId -&gt; normalised username. */
	private final Map<String, String> byId = new HashMap<>();
	/** Normalised usernames flagged without a known id. */
	private final Set<String> nameOnly = new HashSet<>();
	/** Union of {@link #nameOnly} and {@link #byId} values, for name-based matching. */
	private final Set<String> flaggedNames = new HashSet<>();

	protected PlayerFlagService(PartyStore store, FlagKind kind)
	{
		this.store = store;
		this.kind = kind;
		reload();
	}

	protected final synchronized void reload()
	{
		byId.clear();
		nameOnly.clear();
		for (PlayerFlag flag : store.loadFlags(kind))
		{
			String norm = normalize(flag.getUsername());
			if (flag.hasKnownId())
			{
				byId.put(flag.getPlayerId(), norm);
			}
			else
			{
				nameOnly.add(norm);
			}
		}
		rebuildNames();
	}

	/** True when this player is flagged, by id when known, else by name. */
	public synchronized boolean isFlagged(String playerId, String name)
	{
		if (PlayerFlag.isKnown(playerId) && byId.containsKey(playerId))
		{
			return true;
		}
		return name != null && flaggedNames.contains(normalize(name));
	}

	/** Name-only convenience check (no id available at the call site). */
	public boolean isFlagged(String name)
	{
		return isFlagged(null, name);
	}

	/** True when the host or any listed member of {@code ad} is flagged. */
	public synchronized boolean hasAnyFlagged(Advertisement ad)
	{
		if (ad == null)
		{
			return false;
		}
		if (isFlagged(ad.getHostPlayerId(), ad.getHost()))
		{
			return true;
		}
		List<Member> members = ad.getMembers();
		if (members != null)
		{
			for (Member member : members)
			{
				if (member != null && isFlagged(member.getPlayerId(), member.getName()))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Add the player if not flagged, remove if flagged. Persists the change. */
	public synchronized void toggle(String playerId, String name)
	{
		if (name == null)
		{
			return;
		}
		String norm = normalize(name);
		if (isFlagged(playerId, name))
		{
			// Remove every representation of this player (id row and/or name-only row).
			String byNameId = idForName(norm);
			if (PlayerFlag.isKnown(playerId) && byId.remove(playerId) != null)
			{
				store.removeFlag(kind, new PlayerFlag(playerId, norm));
			}
			if (byNameId != null && byId.remove(byNameId) != null)
			{
				store.removeFlag(kind, new PlayerFlag(byNameId, norm));
			}
			if (nameOnly.remove(norm))
			{
				store.removeFlag(kind, new PlayerFlag(null, norm));
			}
		}
		else if (PlayerFlag.isKnown(playerId))
		{
			byId.put(playerId, norm);
			store.upsertFlag(kind, new PlayerFlag(playerId, norm));
		}
		else
		{
			nameOnly.add(norm);
			store.upsertFlag(kind, new PlayerFlag(null, norm));
		}
		rebuildNames();
	}

	/**
	 * Record that {@code playerId} currently goes by {@code name}: renames a stored
	 * entry when the name changed, and backfills the id onto a name-only entry. No-op
	 * when the id is unknown or the player isn't flagged.
	 */
	public synchronized void observe(String playerId, String name)
	{
		if (!PlayerFlag.isKnown(playerId) || name == null)
		{
			return;
		}
		String norm = normalize(name);
		String known = byId.get(playerId);
		if (known != null)
		{
			if (!known.equals(norm))
			{
				byId.put(playerId, norm);
				store.upsertFlag(kind, new PlayerFlag(playerId, norm));
				rebuildNames();
			}
		}
		else if (nameOnly.contains(norm))
		{
			nameOnly.remove(norm);
			byId.put(playerId, norm);
			// upsert with a known id also clears the stale name-only row.
			store.upsertFlag(kind, new PlayerFlag(playerId, norm));
			rebuildNames();
		}
	}

	/** Apply {@link #observe} to the host and every listed member of an ad. */
	public synchronized void observeAd(Advertisement ad)
	{
		if (ad == null)
		{
			return;
		}
		observe(ad.getHostPlayerId(), ad.getHost());
		if (ad.getMembers() != null)
		{
			for (Member member : ad.getMembers())
			{
				if (member != null)
				{
					observe(member.getPlayerId(), member.getName());
				}
			}
		}
	}

	/** All flagged entries (id + last-known name), for management UIs. */
	public synchronized List<PlayerFlag> entries()
	{
		List<PlayerFlag> out = new ArrayList<>();
		for (Map.Entry<String, String> e : byId.entrySet())
		{
			out.add(new PlayerFlag(e.getKey(), e.getValue()));
		}
		for (String name : nameOnly)
		{
			out.add(new PlayerFlag(null, name));
		}
		return out;
	}

	/** Import a flag without toggling (used for one-time migrations). */
	protected synchronized void importFlag(String playerId, String name)
	{
		if (name == null)
		{
			return;
		}
		String norm = normalize(name);
		if (PlayerFlag.isKnown(playerId))
		{
			byId.put(playerId, norm);
			store.upsertFlag(kind, new PlayerFlag(playerId, norm));
		}
		else if (!flaggedNames.contains(norm) && !nameOnly.contains(norm))
		{
			nameOnly.add(norm);
			store.upsertFlag(kind, new PlayerFlag(null, norm));
		}
		rebuildNames();
	}

	private String idForName(String norm)
	{
		for (Map.Entry<String, String> e : byId.entrySet())
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
		flaggedNames.addAll(byId.values());
	}

	/** Normalise a name for storage/comparison (RuneLite uses nbsp in player names). */
	public static String normalize(String name)
	{
		return name == null ? "" : name.replace(' ', ' ').trim().toLowerCase();
	}
}
