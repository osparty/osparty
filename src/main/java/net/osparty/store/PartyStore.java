package net.osparty.store;

import com.google.inject.ImplementedBy;
import java.util.List;

/**
 * Local persistence for OSParty. Today it backs the favourite / block lists; the
 * same store (and its versioned schema) will hold party history later.
 *
 * <p>Deliberately narrow and backend-agnostic. The concrete implementation is
 * {@link JsonPartyStore} (a plain-JSON flat file); an earlier H2-backed one was
 * dropped because the RuneLite Plugin Hub's dependency verification made bundling
 * H2 fragile. Implementations must be safe to call from the Swing EDT.
 */
@ImplementedBy(JsonPartyStore.class)
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
