package net.osparty.api;

import net.osparty.model.Party;
import net.osparty.model.PartyRequest;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * {@link PartyService} backed entirely by the live {@link PartySocket}. Discovery,
 * hosting, keep-alive and one-shot lookups all travel over the single session-long
 * WebSocket; there is no REST path. Callbacks fire on the socket's reader thread, so
 * UI callers must marshal back onto the EDT themselves.
 */
@Singleton
public class PartyApiClient implements PartyService
{
	/** Production party advertising API, used unless overridden for local development. */
	private static final String DEFAULT_API_BASE_URL = "https://api.osparty.net";

	/**
	 * The API base URL. Defaults to {@link #DEFAULT_API_BASE_URL}, but can be pointed
	 * at a local API during development via the {@code osparty.apiUrl} system property.
	 * Read by {@link PartySocket} to derive the WebSocket URL.
	 */
	private static final String API_BASE_URL = resolveBaseUrl();

	private static String resolveBaseUrl()
	{
		String property = System.getProperty("osparty.apiUrl");
		if (property != null && !property.trim().isEmpty())
		{
			return property.trim();
		}
		return DEFAULT_API_BASE_URL;
	}

	public static String apiBaseUrl()
	{
		return API_BASE_URL;
	}

	private final PartySocket partySocket;

	@Inject
	private PartyApiClient(PartySocket partySocket)
	{
		this.partySocket = partySocket;
	}

	@Override
	public PartySubscription subscribeParties(Consumer<List<Party>> onParties, Consumer<Throwable> onError)
	{
		return subscribeParties(onParties, onError, null);
	}

	@Override
	public PartySubscription subscribeParties(Consumer<List<Party>> onParties, Consumer<Throwable> onError, String activityId)
	{
		// The connection is owned by PartySocket (plugin-lifetime); registering a listener
		// subscribes it to the live list. Closing the handle just unregisters — it does not
		// drop the socket (a host still needs it open).
		partySocket.setSearchListener(onParties, activityId);
		return new PartySubscription()
		{
			@Override
			public boolean isConnected()
			{
				return partySocket.isConnected();
			}

			@Override
			public void setActivity(String activityId)
			{
				partySocket.setSearchActivity(activityId);
			}

			@Override
			public void close()
			{
				partySocket.clearSearchListener(onParties);
			}
		};
	}

	@Override
	public void getPartyByCode(String code, Consumer<Party> onSuccess, Consumer<Throwable> onError)
	{
		partySocket.getByCode(code, party -> deliver(party, onSuccess, onError, "No party with code " + code));
	}

	@Override
	public void getPartyByHost(String host, Consumer<Party> onSuccess, Consumer<Throwable> onError)
	{
		partySocket.getByHost(host, party -> deliver(party, onSuccess, onError, "No party for host " + host));
	}

	@Override
	public void createParty(PartyRequest partyRequest, String hostKey, Consumer<Party> onSuccess, Consumer<Throwable> onError)
	{
		// The socket advertises the ad and is its own keep-alive; the hosted ack carries
		// the server-assigned id. If the socket is down, host() reports the error.
		partySocket.host(partyRequest, hostKey, onSuccess, onError);
	}

	@Override
	public void heartbeat(String partyId, int size, int world, String layout, String roles, String hostKey,
		Consumer<Party> onSuccess, Consumer<Throwable> onError)
	{
		// The open socket is the keep-alive; just push the changed fields (deduped inside
		// PartySocket). There is no acknowledgement — list changes come back as deltas.
		partySocket.update(partyId, hostKey, patchOf(size, world, layout, roles));
	}

	@Override
	public void disbandParty(String partyId, String host, String hostKey, Consumer<Party> onSuccess, Consumer<Throwable> onError)
	{
		partySocket.unhost(partyId, hostKey);
		onSuccess.accept(null); // optimistic — the server removes the ad and broadcasts it
	}

	/** Route a lookup result: the party on success, or a not-found error when null. */
	private static void deliver(Party party, Consumer<Party> onSuccess, Consumer<Throwable> onError, String notFound)
	{
		if (party != null)
		{
			onSuccess.accept(party);
		}
		else
		{
			onError.accept(new IOException(notFound));
		}
	}

	/** A partial update mirroring the server's PartyUpdate (Gson omits the null fields). */
	private static PartyPatch patchOf(int size, int world, String layout, String roles)
	{
		PartyPatch patch = new PartyPatch();
		if (size > 0)
		{
			patch.size = size;
		}
		if (world > 0)
		{
			patch.world = Integer.toString(world);
		}
		if (layout != null && !layout.isEmpty())
		{
			patch.layout = layout;
		}
		if (roles != null && !roles.isEmpty())
		{
			patch.neededRoles = Arrays.asList(roles.split(","));
		}
		return patch;
	}

	private static final class PartyPatch
	{
		Integer size;
		String world;
		String layout;
		List<String> neededRoles;
	}
}
