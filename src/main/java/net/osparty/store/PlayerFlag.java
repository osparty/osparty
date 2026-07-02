package net.osparty.store;

import lombok.Value;

/**
 * One persisted flag row: an {@code accountHash} (the stable RuneScape account id,
 * used to survive name changes) and the last-known {@code username}. When the hash
 * is not yet known (e.g. a favourite migrated from the old name-only list, or a
 * player we haven't seen a hash for), {@code accountHash} is {@link #UNKNOWN_HASH}
 * and matching falls back to the (normalised) username.
 */
@Value
public class PlayerFlag
{
	/** Sentinel for "hash not known yet"; RuneLite also returns {@code -1} when logged out. */
	public static final long UNKNOWN_HASH = -1L;

	long accountHash;
	String username;

	public boolean hasKnownHash()
	{
		return isKnown(accountHash);
	}

	/**
	 * Whether {@code accountHash} is a real account id rather than an "unknown" marker.
	 * RuneLite account hashes span the full signed-long range (negatives are common), so
	 * only the two sentinels are treated as unknown: {@code 0} (never sent) and
	 * {@link #UNKNOWN_HASH} ({@code -1}, RuneLite's logged-out value).
	 */
	public static boolean isKnown(long accountHash)
	{
		return accountHash != 0 && accountHash != UNKNOWN_HASH;
	}
}
