package net.osparty.store;

/**
 * The two kinds of per-player flag persisted in {@link PartyStore}: FAVORITE
 * (highlighted, surfaced on the Favorites tab) and BLOCK (hidden from search).
 * Stored as the enum name in the {@code player_flag.kind} column.
 */
public enum FlagKind
{
	FAVORITE,
	BLOCK
}
