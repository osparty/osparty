package net.osparty.api;

/**
 * Handle to a live party-list subscription (see {@link PartyService#subscribeParties}).
 * Close it to unsubscribe and release the socket. {@link #isConnected()} lets the
 * caller fall back to REST polling while the socket is down.
 */
public interface PartySubscription extends AutoCloseable
{
	/** Whether the socket is currently connected and receiving updates. */
	boolean isConnected();

	/** Unsubscribe and close the underlying socket. Idempotent. */
	@Override
	void close();
}
