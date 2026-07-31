package net.osparty.api;

/**
 * Handle to a live board subscription (see {@link BoardService#subscribeAds}).
 * Close it to unsubscribe and release the socket. {@link #isConnected()} lets the
 * caller fall back to REST polling while the socket is down.
 */
public interface BoardSubscription extends AutoCloseable
{
	/** Whether the socket is currently connected and receiving updates. */
	boolean isConnected();

	/**
	 * Re-scope the live feed to a single activity id ({@code null} = all). The server replies with a
	 * fresh snapshot for the new scope; cheap to call when the user narrows/widens their filter.
	 */
	void setActivity(String activityId);

	/** Force an immediate reconnect attempt when the socket is down. */
	void reconnect();

	/** Unsubscribe and close the underlying socket. Idempotent. */
	@Override
	void close();
}
