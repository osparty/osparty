package net.osparty.tools;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;

/**
 * Async TCP-connect pinger for OSRS world servers. Connects on port 43594 and
 * measures round-trip time as a proxy for latency. Results are cached for the
 * lifetime of the plugin instance; call {@link #invalidate()} to force a fresh
 * measurement. Callbacks are invoked on a background thread — callers that touch
 * Swing must marshal back onto the EDT themselves.
 */
@Slf4j
public class WorldPinger
{
	private static final int GAME_PORT = 43594;
	private static final int TIMEOUT_MS = 5_000;

	/** Sentinel stored when a world is unreachable or timed out. */
	public static final int UNREACHABLE = -1;

	private final ConcurrentHashMap<Integer, Integer> cache = new ConcurrentHashMap<>();
	private final Set<Integer> inFlight = ConcurrentHashMap.newKeySet();
	private final ExecutorService executor = Executors.newCachedThreadPool(r ->
	{
		Thread t = new Thread(r, "osparty-pinger");
		t.setDaemon(true);
		return t;
	});

	/**
	 * Request a TCP-connect ping for {@code worldNum} at {@code address}.
	 * No-op when the address is null, a result is already cached, or a ping for
	 * this world is already in flight. {@code onDone} is invoked on a background
	 * thread once the result is ready.
	 */
	public void requestPing(int worldNum, String address, Runnable onDone)
	{
		if (address == null || cache.containsKey(worldNum) || !inFlight.add(worldNum))
		{
			return;
		}
		executor.submit(() ->
		{
			int ms = tcpPing(address);
			cache.put(worldNum, ms);
			inFlight.remove(worldNum);
			try
			{
				onDone.run();
			}
			catch (Exception e)
			{
				log.warn("Ping callback error for world {}", worldNum, e);
			}
		});
	}

	/**
	 * @return the cached ping in milliseconds for {@code worldNum}, or {@code null}
	 * if no result is available yet.
	 */
	public Integer getCachedPing(int worldNum)
	{
		return cache.get(worldNum);
	}

	/** Clear all cached results so worlds will be re-pinged on the next request. */
	public void invalidate()
	{
		cache.clear();
	}

	/** Shut down the background executor. Call from the plugin's shutDown(). */
	public void shutdown()
	{
		executor.shutdownNow();
	}

	private static int tcpPing(String address)
	{
		long start = System.currentTimeMillis();
		try (Socket socket = new Socket())
		{
			socket.connect(new InetSocketAddress(address, GAME_PORT), TIMEOUT_MS);
			return (int) (System.currentTimeMillis() - start);
		}
		catch (Exception e)
		{
			return UNREACHABLE;
		}
	}
}
