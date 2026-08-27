package net.osparty.store;

import lombok.Value;

/**
 * One persisted flag row: a {@code playerId} (the account's public, non-reversible id, used to
 * survive name changes) and the last-known {@code username}. When the id is not yet known (a
 * favourite migrated from the old name-only list, or a player we haven't seen an id for),
 * {@code playerId} is {@code null} and matching falls back to the (normalised) username.
 *
 * <p>Rows used to be keyed by the raw account hash. A file from then loads as name-only rows —
 * Gson skips the field — and is rewritten without the hashes the first time it is opened; each
 * row upgrades to an id the next time that player is seen. The hash is what a client asserts
 * to claim an identity, so it has no business on disk against other people's names.
 */
@Value
public class PlayerFlag
{
	String playerId;
	String username;

	public boolean hasKnownId()
	{
		return isKnown(playerId);
	}

	/** Whether {@code playerId} is a real id rather than the "unknown" marker. */
	public static boolean isKnown(String playerId)
	{
		return playerId != null && !playerId.trim().isEmpty();
	}
}
