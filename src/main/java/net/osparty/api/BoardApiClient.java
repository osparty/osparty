package net.osparty.api;

import net.osparty.model.Advertisement;
import net.osparty.model.AdvertisementEditRequest;
import net.osparty.model.AdvertisementRequest;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * {@link BoardService} backed entirely by the live {@link OSPartySocket}; no REST path. Callbacks fire
 * on the socket's reader thread, so UI callers must marshal back onto the EDT themselves.
 */
@Singleton
public class BoardApiClient implements BoardService
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

	private final OSPartySocket socket;

	@Inject
	private BoardApiClient(OSPartySocket socket)
	{
		this.socket = socket;
	}

	@Override
	public BoardSubscription subscribeAds(Consumer<List<Advertisement>> onAds, Consumer<Throwable> onError)
	{
		return subscribeAds(onAds, onError, null);
	}

	@Override
	public BoardSubscription subscribeAds(Consumer<List<Advertisement>> onAds, Consumer<Throwable> onError, String activityId)
	{
		// Registering a listener subscribes the socket to the live list; closing just unregisters.
		socket.setSearchListener(onAds, activityId);
		return new BoardSubscription()
		{
			@Override
			public boolean isConnected()
			{
				return socket.isConnected();
			}

			@Override
			public void setActivity(String activityId)
			{
				socket.setSearchActivity(activityId);
			}

			@Override
			public void reconnect()
			{
				socket.reconnectNow();
			}

			@Override
			public void close()
			{
				socket.clearSearchListener(onAds);
			}
		};
	}

	@Override
	public void fetchAdByCode(String code, Consumer<Advertisement> onSuccess, Consumer<Throwable> onError)
	{
		socket.fetchByCode(code, ad -> deliver(ad, onSuccess, onError, "No party with code " + code));
	}

	@Override
	public void fetchAdByHost(String host, Consumer<Advertisement> onSuccess, Consumer<Throwable> onError)
	{
		socket.fetchByHost(host, ad -> deliver(ad, onSuccess, onError, "No party for host " + host));
	}

	@Override
	public void setOnHostedAdGone(Consumer<String> callback)
	{
		socket.setOnHostedGone(callback);
	}

	@Override
	public boolean isApiConnected()
	{
		return socket.isConnected();
	}

	@Override
	public void createAd(AdvertisementRequest request, String hostKey, Consumer<Advertisement> onSuccess, Consumer<Throwable> onError)
	{
		// The socket advertises the ad; the hosted ack carries the server-assigned id.
		socket.host(request, hostKey, onSuccess, onError);
	}

	@Override
	public void heartbeat(String adId, int size, int world, String layout, String roles,
		java.util.List<net.osparty.model.Member> members, String hostKey,
		Consumer<Advertisement> onSuccess, Consumer<Throwable> onError)
	{
		// The open socket is the keep-alive; push only changed fields (deduped in OSPartySocket).
		socket.update(adId, hostKey, patchOf(size, world, layout, roles, members));
	}

	@Override
	public void editAd(String adId, String hostKey, AdvertisementEditRequest edit, Consumer<Advertisement> onSuccess,
		Consumer<Throwable> onError)
	{
		// No ack (like disband): report success optimistically; the refreshed ad returns as an 'updated' broadcast.
		socket.edit(adId, hostKey, edit);
		onSuccess.accept(null);
	}

	@Override
	public void removeAd(String adId, String host, String hostKey, Consumer<Advertisement> onSuccess, Consumer<Throwable> onError)
	{
		socket.unhost(adId, hostKey);
		onSuccess.accept(null); // optimistic — the server removes the ad and broadcasts it
	}

	@Override
	public void transferHost(String adId, String currentHostKey, String newHost, String newHostAccountType,
		String newHostKey, Consumer<Advertisement> onSuccess, Consumer<Throwable> onError)
	{
		// Unlike disband there IS a 'transferred' ack; the old host mustn't relinquish until the re-key succeeds.
		socket.transferHost(adId, currentHostKey, newHost, newHostAccountType, newHostKey, onSuccess, onError);
	}

	@Override
	public void adoptHostedAd(String adId, String hostKey)
	{
		socket.setHosting(adId, hostKey);
	}

	@Override
	public void releaseHostedAd(String adId)
	{
		socket.clearHosting(adId);
	}

	@Override
	public void createVoiceChannel(String adId, String hostKey, Consumer<String> onUrl, Consumer<Throwable> onError)
	{
		// One-shot request/reply over the socket; the reply or a matching error resolves one callback.
		socket.createVoiceChannel(adId, hostKey, onUrl, onError);
	}

	@Override
	public void startDiscordLink(long accountHash, Consumer<String> onUrl, Consumer<Throwable> onError)
	{
		socket.startDiscordLink(accountHash, onUrl, onError);
	}

	@Override
	public void fetchDiscordLink(long accountHash, Consumer<DiscordLinkStatus> onResult)
	{
		socket.fetchDiscordLink(accountHash, onResult);
	}

	@Override
	public void unlinkDiscord(long accountHash)
	{
		socket.unlinkDiscord(accountHash);
	}

	@Override
	public void setBadgeVisibility(long accountHash, boolean visible, Consumer<DiscordLinkStatus> onResult)
	{
		socket.setBadgeVisibility(accountHash, visible, onResult);
	}

	@Override
	public void kickVoiceMember(String adId, String hostKey, long accountHash)
	{
		socket.kickVoiceMember(adId, hostKey, accountHash);
	}

	@Override
	public void reportAd(String adId)
	{
		socket.reportAd(adId);
	}

	@Override
	public void requestVoiceAccess(String adId, long accountHash, Runnable onGranted, Consumer<Throwable> onError)
	{
		socket.requestVoiceAccess(adId, accountHash, onGranted, onError);
	}

	@Override
	public void identify(long accountHash, String name)
	{
		socket.identify(accountHash, name);
	}

	@Override
	public void inviteFriend(String adId, String fromName, long fromAccountHash, String targetName,
		Consumer<Boolean> onDelivered)
	{
		socket.invite(adId, fromName, fromAccountHash, targetName, onDelivered);
	}

	@Override
	public void setInviteListener(Consumer<PartyInvite> listener)
	{
		socket.setInviteListener(listener);
	}

	@Override
	public int onlineUserCount()
	{
		return socket.onlineUserCount();
	}

	/** Route a lookup result: the ad on success, or a not-found error when null. */
	private static void deliver(Advertisement ad, Consumer<Advertisement> onSuccess, Consumer<Throwable> onError, String notFound)
	{
		if (ad != null)
		{
			onSuccess.accept(ad);
		}
		else
		{
			onError.accept(new IOException(notFound));
		}
	}

	/** A partial update mirroring the server's AdvertisementUpdate (Gson omits the null fields). */
	private static AdPatch patchOf(int size, int world, String layout, String roles,
		List<net.osparty.model.Member> members)
	{
		AdPatch patch = new AdPatch();
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

	private static final class AdPatch
	{
		Integer size;
		List<net.osparty.model.Member> members;
		String world;
		String layout;
		List<String> neededRoles;
	}
}
