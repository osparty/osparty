package net.osparty.api;

import net.osparty.model.Party;
import net.osparty.model.PartyEditRequest;
import net.osparty.model.PartyRequest;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * {@link PartyService} backed entirely by the live {@link PartySocket}; no REST path. Callbacks fire
 * on the socket's reader thread, so UI callers must marshal back onto the EDT themselves.
 */
@Singleton
public class PartyApiClient implements PartyService
{
	private static final String DEFAULT_API_BASE_URL = "https://api.osparty.net";

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
		// Registering a listener subscribes the socket to the live list; closing just unregisters.
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
			public void reconnect()
			{
				partySocket.reconnectNow();
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
		// The socket advertises the ad; the hosted ack carries the server-assigned id.
		partySocket.host(partyRequest, hostKey, onSuccess, onError);
	}

	@Override
	public void heartbeat(String partyId, int size, int world, String layout, String roles,
		java.util.List<net.osparty.model.Member> members, String hostKey,
		Consumer<Party> onSuccess, Consumer<Throwable> onError)
	{
		// The open socket is the keep-alive; push only changed fields (deduped in PartySocket).
		partySocket.update(partyId, hostKey, patchOf(size, world, layout, roles, members));
	}

	@Override
	public void editParty(String partyId, String hostKey, PartyEditRequest edit, Consumer<Party> onSuccess,
		Consumer<Throwable> onError)
	{
		// No ack (like disband): report success optimistically; the refreshed ad returns as an 'updated' broadcast.
		partySocket.edit(partyId, hostKey, edit);
		onSuccess.accept(null);
	}

	@Override
	public void disbandParty(String partyId, String host, String hostKey, Consumer<Party> onSuccess, Consumer<Throwable> onError)
	{
		partySocket.unhost(partyId, hostKey);
		onSuccess.accept(null); // optimistic — the server removes the ad and broadcasts it
	}

	@Override
	public void transferHost(String partyId, String currentHostKey, String newHost, String newHostKey,
		Consumer<Party> onSuccess, Consumer<Throwable> onError)
	{
		// Unlike disband there IS a 'transferred' ack; the old host mustn't relinquish until the re-key succeeds.
		partySocket.transferHost(partyId, currentHostKey, newHost, newHostKey, onSuccess, onError);
	}

	@Override
	public void adoptHostedParty(String partyId, String hostKey)
	{
		partySocket.setHosting(partyId, hostKey);
	}

	@Override
	public void releaseHostedParty(String partyId)
	{
		partySocket.clearHosting(partyId);
	}

	@Override
	public void createVoiceChannel(String partyId, String hostKey, Consumer<String> onUrl, Consumer<Throwable> onError)
	{
		// One-shot request/reply over the socket; the reply or a matching error resolves one callback.
		partySocket.createVoiceChannel(partyId, hostKey, onUrl, onError);
	}

	@Override
	public void startDiscordLink(long accountHash, Consumer<String> onUrl, Consumer<Throwable> onError)
	{
		partySocket.startDiscordLink(accountHash, onUrl, onError);
	}

	@Override
	public void getDiscordLink(long accountHash, Consumer<DiscordLinkStatus> onResult)
	{
		partySocket.getDiscordLink(accountHash, onResult);
	}

	@Override
	public void unlinkDiscord(long accountHash)
	{
		partySocket.unlinkDiscord(accountHash);
	}

	@Override
	public void setBadgeVisibility(long accountHash, boolean visible, Consumer<DiscordLinkStatus> onResult)
	{
		partySocket.setBadgeVisibility(accountHash, visible, onResult);
	}

	@Override
	public void kickVoiceMember(String partyId, String hostKey, long accountHash)
	{
		partySocket.kickVoiceMember(partyId, hostKey, accountHash);
	}

	@Override
	public void requestVoiceAccess(String partyId, long accountHash, Runnable onGranted, Consumer<Throwable> onError)
	{
		partySocket.requestVoiceAccess(partyId, accountHash, onGranted, onError);
	}

	@Override
	public int onlineUsers()
	{
		return partySocket.onlineUsers();
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
	private static PartyPatch patchOf(int size, int world, String layout, String roles,
		List<net.osparty.model.Member> members)
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
		if (members != null && !members.isEmpty())
		{
			patch.members = members;
		}
		return patch;
	}

	private static final class PartyPatch
	{
		Integer size;
		List<net.osparty.model.Member> members;
		String world;
		String layout;
		List<String> neededRoles;
	}
}
