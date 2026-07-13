package net.osparty.service;

import net.osparty.OSPartyConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.osparty.model.RuneWatchCase;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Checks players against the public RuneWatch / We Do Raids scammer watchlist.
 *
 * <p>Mirrors the official RuneWatch plugin's approach: download the combined
 * {@code mixedlist.json} feed once (and refresh periodically), then check names
 * locally against the cached map. No per-name lookups and no name ever leaves
 * the client.
 */
@Slf4j
@Singleton
public class RuneWatchService
{
	// The combined RuneWatch + We Do Raids feed maintained for the RuneWatch plugin.
	private static final HttpUrl LIST_URL = HttpUrl.parse(
		"https://raw.githubusercontent.com/while-loop/runelite-plugins/runewatch/data/mixedlist.json");
	private static final Type LIST_TYPE = new TypeToken<List<RuneWatchCase>>()
	{
	}.getType();

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final OSPartyConfig config;

	private final Map<String, RuneWatchCase> cases = new ConcurrentHashMap<>();
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private volatile boolean loaded;

	@Inject
	private RuneWatchService(OkHttpClient httpClient, Gson gson, OSPartyConfig config)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.config = config;
	}

	/** Listeners fire (off the EDT) when the watchlist finishes (re)loading. */
	public void addListener(Runnable listener)
	{
		listeners.add(listener);
	}

	public boolean isLoaded()
	{
		return loaded;
	}

	public RuneWatchCase get(String rsn)
	{
		if (!config.runeWatch() || rsn == null)
		{
			return null;
		}
		return cases.get(normalize(rsn));
	}

	/** Background thread; no-op when disabled. */
	public void refresh()
	{
		if (!config.runeWatch())
		{
			return;
		}

		Request request = new Request.Builder().url(LIST_URL).build();
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("RuneWatch list fetch failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					if (!response.isSuccessful() || body == null)
					{
						log.warn("RuneWatch list returned status {}", response.code());
						return;
					}

					List<RuneWatchCase> list = gson.fromJson(
						new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8), LIST_TYPE);

					Map<String, RuneWatchCase> fresh = new ConcurrentHashMap<>();
					if (list != null)
					{
						for (RuneWatchCase c : list)
						{
							if (c.getRsn() != null)
							{
								fresh.put(normalize(c.getRsn()), c);
							}
						}
					}

					cases.clear();
					cases.putAll(fresh);
					loaded = true;
					log.debug("RuneWatch watchlist loaded: {} cases", cases.size());
					fire();
				}
				catch (Exception e)
				{
					log.warn("Failed to parse RuneWatch list", e);
				}
			}
		});
	}

	private void fire()
	{
		for (Runnable listener : listeners)
		{
			listener.run();
		}
	}

	/** Normalise a RSN the same way the RuneWatch plugin does, for matching. */
	private static String normalize(String rsn)
	{
		return Text.removeTags(Text.toJagexName(rsn)).toLowerCase();
	}
}
