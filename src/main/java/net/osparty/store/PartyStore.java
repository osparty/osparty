package net.osparty.store;

import com.google.inject.ImplementedBy;
import java.util.List;

/**
 * Local persistence for OSParty. Today it backs the favourite / block lists; the
 * same store (and its versioned schema) will hold party history later.
 *
 * <p>Deliberately narrow and backend-agnostic so the concrete implementation
 * ({@link H2PartyStore}) can be swapped for a flat-file one without touching
 * callers — important because the RuneLite Plugin Hub may not approve the H2
 * dependency. Implementations must be safe to call from the Swing EDT.
 */
@ImplementedBy(H2PartyStore.class)
public interface PartyStore
{
	/** All persisted flags of the given kind. */
	List<PlayerFlag> loadFlags(FlagKind kind);

	/**
	 * Insert or update a flag. When {@code flag.hasKnownHash()} the row is keyed by
	 * (kind, accountHash) and any pre-existing name-only row for the same username is
	 * removed (hash backfill). Otherwise it is keyed by (kind, normalised username).
	 */
	void upsertFlag(FlagKind kind, PlayerFlag flag);

	/** Remove a flag by hash when known, otherwise by normalised username. */
	void removeFlag(FlagKind kind, PlayerFlag flag);

	/** Release any resources (connections/pools). Safe to call more than once. */
	void close();
}
