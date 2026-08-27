package net.osparty.store;

/**
 * The local player's own account hash, as {@code Client.getAccountHash()} reports it. It is the one hash
 * this plugin ever handles: it goes to the server to say who we are, and it keys the credential file.
 * Nobody else's hash is ever seen -- other players are known by their public {@code playerId}.
 */
public final class AccountHash
{
	/** RuneLite's logged-out value. */
	public static final long UNKNOWN = -1L;

	private AccountHash()
	{
	}

	/**
	 * Whether {@code accountHash} is a real account id rather than an "unknown" marker.
	 * Account hashes span the full signed-long range (negatives are common), so only the two
	 * sentinels are treated as unknown: {@code 0} (never set) and {@link #UNKNOWN}.
	 */
	public static boolean isKnown(long accountHash)
	{
		return accountHash != 0 && accountHash != UNKNOWN;
	}
}
