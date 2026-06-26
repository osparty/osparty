package net.osparty.api;

import net.osparty.OSPartyConfig;
import net.osparty.model.Activity;
import net.osparty.model.Party;
import net.osparty.model.PartyRequest;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Thin asynchronous client over the (separately implemented) party queue API.
 *
 * <p>All calls are non-blocking; results are delivered on an OkHttp dispatcher
 * thread, so callers that touch Swing must marshal back onto the EDT
 * themselves. Every request is keyed on the logged in player name supplied by
 * the caller, which is how the backend associates a queue entry with an
 * account.
 */
@Slf4j
@Singleton
public class PartyApiClient implements PartyService
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final Type PARTY_LIST_TYPE = new TypeToken<List<Party>>()
	{
	}.getType();

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final OSPartyConfig config;

	@Inject
	private PartyApiClient(OkHttpClient httpClient, Gson gson, OSPartyConfig config)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.config = config;
	}

	private HttpUrl.Builder baseUrl()
	{
		HttpUrl parsed = HttpUrl.parse(config.apiBaseUrl());
		if (parsed == null)
		{
			throw new IllegalStateException("Invalid API base URL: " + config.apiBaseUrl());
		}
		// Versioned base path: all endpoints live under /api/v1.
		return parsed.newBuilder().addPathSegment("api").addPathSegment("v1");
	}

	/**
	 * Fetch the parties currently queued for the given activity.
	 */
	@Override
	public void searchParties(Activity activity, String player, Consumer<List<Party>> onSuccess, Consumer<Throwable> onError)
	{
		HttpUrl url = baseUrl()
			.addPathSegment("parties")
			.addQueryParameter("activity", activity.getId())
			.addQueryParameter("player", nullToEmpty(player))
			.build();

		Request request = new Request.Builder().url(url).get().build();
		enqueue(request, PARTY_LIST_TYPE, onSuccess, onError);
	}

	/**
	 * Fetch a single party by its invite code (works for private parties too).
	 */
	@Override
	public void getPartyByCode(String code, Consumer<Party> onSuccess, Consumer<Throwable> onError)
	{
		HttpUrl url = baseUrl()
			.addPathSegment("parties")
			.addPathSegment("by-code")
			.addPathSegment(code)
			.build();

		Request request = new Request.Builder().url(url).get().build();
		enqueue(request, Party.class, onSuccess, onError);
	}

	/**
	 * Create a new party hosted by the logged in player and enter the queue.
	 */
	@Override
	public void createParty(PartyRequest partyRequest, Consumer<Party> onSuccess, Consumer<Throwable> onError)
	{
		HttpUrl url = baseUrl().addPathSegment("parties").build();
		RequestBody body = RequestBody.create(JSON, gson.toJson(partyRequest));
		Request request = new Request.Builder().url(url).post(body).build();
		enqueue(request, Party.class, onSuccess, onError);
	}

	/**
	 * Host keep-alive: bump the ad's liveness so the backend doesn't reap it as
	 * stale. PUT (not POST) so it isn't caught by the create rate limit.
	 */
	@Override
	public void heartbeat(String partyId, Consumer<Party> onSuccess, Consumer<Throwable> onError)
	{
		HttpUrl url = baseUrl()
			.addPathSegment("parties")
			.addPathSegment(partyId)
			.addPathSegment("heartbeat")
			.build();

		RequestBody body = RequestBody.create(JSON, "{}");
		Request request = new Request.Builder().url(url).put(body).build();
		enqueue(request, Party.class, onSuccess, onError);
	}

	/**
	 * Submit an application for the logged in player to an existing party.
	 */
	@Override
	public void applyToParty(String partyId, String player, Consumer<Party> onSuccess, Consumer<Throwable> onError)
	{
		HttpUrl url = baseUrl()
			.addPathSegment("parties")
			.addPathSegment(partyId)
			.addPathSegment("apply")
			.build();

		RequestBody body = RequestBody.create(JSON, gson.toJson(new PlayerRequest(player)));
		Request request = new Request.Builder().url(url).post(body).build();
		enqueue(request, Party.class, onSuccess, onError);
	}

	/**
	 * Withdraw the logged in player's pending application to a party.
	 */
	@Override
	public void cancelApplication(String partyId, String player, Consumer<Party> onSuccess, Consumer<Throwable> onError)
	{
		HttpUrl url = baseUrl()
			.addPathSegment("parties")
			.addPathSegment(partyId)
			.addPathSegment("cancel")
			.build();

		RequestBody body = RequestBody.create(JSON, gson.toJson(new PlayerRequest(player)));
		Request request = new Request.Builder().url(url).post(body).build();
		enqueue(request, Party.class, onSuccess, onError);
	}

	/**
	 * Host action: admit an applicant into the party.
	 */
	@Override
	public void acceptApplicant(String partyId, String host, String applicant, Consumer<Party> onSuccess, Consumer<Throwable> onError)
	{
		HttpUrl url = baseUrl()
			.addPathSegment("parties")
			.addPathSegment(partyId)
			.addPathSegment("accept")
			.build();

		RequestBody body = RequestBody.create(JSON, gson.toJson(new PlayerRequest(applicant)));
		Request request = new Request.Builder().url(url).post(body).build();
		enqueue(request, Party.class, onSuccess, onError);
	}

	/**
	 * Host action: remove a member from the party.
	 */
	@Override
	public void kickPlayer(String partyId, String host, String target, Consumer<Party> onSuccess, Consumer<Throwable> onError)
	{
		HttpUrl url = baseUrl()
			.addPathSegment("parties")
			.addPathSegment(partyId)
			.addPathSegment("kick")
			.build();

		RequestBody body = RequestBody.create(JSON, gson.toJson(new PlayerRequest(target)));
		Request request = new Request.Builder().url(url).post(body).build();
		enqueue(request, Party.class, onSuccess, onError);
	}

	/**
	 * Host action: close the party.
	 */
	@Override
	public void disbandParty(String partyId, String host, Consumer<Party> onSuccess, Consumer<Throwable> onError)
	{
		HttpUrl url = baseUrl()
			.addPathSegment("parties")
			.addPathSegment(partyId)
			.build();

		Request request = new Request.Builder().url(url).delete().build();
		enqueue(request, Party.class, onSuccess, onError);
	}

	private <T> void enqueue(Request request, Type type, Consumer<T> onSuccess, Consumer<Throwable> onError)
	{
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Party API request failed: {}", request.url(), e);
				onError.accept(e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody responseBody = response.body())
				{
					if (!response.isSuccessful())
					{
						onError.accept(new IOException("Unexpected response " + response.code() + " from " + request.url()));
						return;
					}

					String json = responseBody == null ? "" : responseBody.string();
					@SuppressWarnings("unchecked")
					T parsed = (T) gson.fromJson(json, type);
					onSuccess.accept(parsed);
				}
				catch (Exception e)
				{
					log.warn("Failed to parse party API response: {}", request.url(), e);
					onError.accept(e);
				}
			}
		});
	}

	private static String nullToEmpty(String value)
	{
		return value == null ? "" : value;
	}

	private static final class PlayerRequest
	{
		@SuppressWarnings("unused")
		private final String player;

		private PlayerRequest(String player)
		{
			this.player = player;
		}
	}
}
