package net.osparty.api;

/**
 * The one-byte channel tag that lets a single connection carry both the ad board and the live party; every
 * frame in both directions is prefixed by one of these. Must match the server's
 * {@code net.osparty.api.transport.Mux}.
 */
public final class Mux
{
	/** The advertisement board: search, hosting, invites, Discord. */
	public static final byte BOARD = 1;
	/** The live party: roster, member state, pings, ready checks. */
	public static final byte LIVE = 2;

	private Mux()
	{
	}
}
