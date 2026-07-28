package net.osparty.api;

/**
 * The one-byte channel tag that lets a single connection carry both the ad board and the live party.
 *
 * <p>This plugin used to hold two sockets whenever the user was in a party. The gateway in front of the API
 * costs about as much CPU per connection as the API itself does, so the second socket was the most expensive
 * thing about being in a party — and it carried nothing the first one could not have. One connection now
 * carries both, with every frame in both directions prefixed by one of these bytes.
 *
 * <p>Must match the server's {@code net.osparty.api.transport.Mux}.
 */
public final class Mux
{
	/** The V1 advertisement board. */
	public static final byte BOARD = 1;
	/** The V2 live party. */
	public static final byte LIVE = 2;

	private Mux()
	{
	}
}
