package net.osparty.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * What the server this client is pointed at can actually do, asked once at startup.
 *
 * <p>A released plugin outlives any single server configuration. The live party may be on or off, and the
 * merged socket may or may not exist, and neither is something a user updates their plugin to change — so
 * the client works out which world it is in rather than assuming. Everything unavailable falls back to what
 * this plugin has always done: the ad-board socket on its own endpoint, and RuneLite's relay for live party.
 *
 * <p>Asked rather than inferred from a failed connection. A 404 on a newer path is ambiguous — a proxy, a
 * typo, a deployment mid-roll — and inferring capability from a failure means one wasted handshake on every
 * start against an older server, plus a rule about which failures count.
 *
 * <p>Failing to reach the server at all answers "no": a client that cannot ask is a client that should be
 * doing the oldest, most compatible thing.
 */
@Slf4j
@Singleton
public class ServerCapabilities {
	/** Short: this blocks startup, and the answer is only ever an optimisation over the fallback. */
	private static final long TIMEOUT_MS = 4_000;

	private final OkHttpClient client;
	private final Gson gson;

	private volatile boolean probed;
	private volatile boolean partyV2;
	private volatile boolean mergedSocket;
	/**
	 * Set when the merged socket has failed us repeatedly since the probe. A server can turn the live party
	 * off underneath a running client, and without this that client would keep dialling a path that no
	 * longer exists until the user restarted — so a rollback would strand exactly the people who updated.
	 */
	private volatile boolean forcedLegacy;

	@Inject
	public ServerCapabilities(OkHttpClient httpClient, Gson gson) {
		this.client = httpClient.newBuilder()
			.callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
			.build();
		this.gson = gson;
	}

	/** Ask, once. Safe to call repeatedly; only the first call goes to the network. */
	public synchronized void probe() {
		if (probed) {
			return;
		}
		probed = true;
		// A forced answer, for pointing a development client at something the probe cannot see. It has to
		// set both: the live party is not usable without the connection that carries it, and forcing only
		// the backend would select V2 over a socket with no live channel — which fails silently, the worst
		// way for a test override to be wrong.
		if (Boolean.getBoolean("osparty.partyV2")) {
			partyV2 = true;
			mergedSocket = true;
			log.info("Server capabilities forced by -Dosparty.partyV2");
			return;
		}
		String url = PartyApiClient.apiBaseUrl() + "/api/v1/capabilities";
		try (Response response = client.newCall(new Request.Builder().url(url).get().build()).execute()) {
			if (!response.isSuccessful() || response.body() == null) {
				log.debug("Capabilities probe: {} answered {}", url, response.code());
				return;
			}
			JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);
			if (json == null) {
				return;
			}
			partyV2 = json.has("partyV2") && json.get("partyV2").getAsBoolean();
			mergedSocket = json.has("mergedSocket") && json.get("mergedSocket").getAsBoolean();
		}
		catch (Exception e) {
			// An older server has no such endpoint, and an unreachable one tells us nothing. Both mean
			// "use what has always worked", which is what the fields already say.
			log.debug("Capabilities probe failed ({}); using the legacy endpoints", e.toString());
		}
		log.info("Server capabilities: partyV2={} mergedSocket={}", partyV2, mergedSocket);
	}

	/** Whether both protocols may share one connection at {@code /api/ws}. */
	public boolean mergedSocket() {
		return mergedSocket && !forcedLegacy;
	}

	/** Whether OSParty's own live party is served, rather than RuneLite's relay. */
	public boolean partyV2() {
		return partyV2 && !forcedLegacy;
	}

	/**
	 * Give up on the merged socket for the rest of this session.
	 *
	 * <p>Called when it has failed enough times to mean the server stopped serving it rather than that the
	 * network hiccuped. The live-party backend is chosen once at startup and does not change under a running
	 * party, so this only moves the connection back to the endpoint every server serves — discovery keeps
	 * working, which is the part the user is looking at.
	 */
	public void fallBackToLegacy() {
		if (!forcedLegacy) {
			log.info("Merged socket unavailable; falling back to the ad-board endpoint");
			forcedLegacy = true;
		}
	}
}
