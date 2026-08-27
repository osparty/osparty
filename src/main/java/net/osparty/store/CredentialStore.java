package net.osparty.store;

import com.google.gson.Gson;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * The OSParty credential for each character on this install, in {@code <runelite>/osparty/credentials.json}.
 *
 * <p><b>Why here and not in the plugin config.</b> RuneLite's config is synchronised: for a signed-in user
 * the whole config group is PATCHed to api.runelite.net, so anything written there leaves this machine in
 * plaintext and lands on servers we do not run. A credential that authenticates a player to us has no
 * business being copied anywhere, least of all somewhere it is stored as a preference. The plugin hub's own
 * guidance points the same way: files belong under the RuneLite directory.
 *
 * <p><b>Why keyed by account hash.</b> The credential is per (account, machine), so two characters on one
 * install hold two different credentials and neither may be used for the other. Keying the file by account
 * hash is what keeps them apart -- a single-valued store would hand a main's credential to an ironman the
 * first time someone switched.
 *
 * <p>Losing this file is not a disaster: the next connection enrols again and the account ends up with an
 * extra credential it does not use. It is not worth a backup, and it is worth never syncing.
 */
@Slf4j
@Singleton
public class CredentialStore
{
	private static final String FILE_NAME = "credentials.json";
	private static final int SCHEMA_VERSION = 1;

	private final JsonFile<Data> store;
	/** accountHash -&gt; the token issued for it on this machine. Guarded by the instance monitor. */
	private final Map<Long, String> tokens = new HashMap<>();

	@Inject
	public CredentialStore(Gson gson)
	{
		this(new File(RuneLite.RUNELITE_DIR, "osparty"), gson);
	}

	/** Test/embeddable entry point: read (or create) the store in {@code dir}. */
	public CredentialStore(File dir, Gson gson)
	{
		this.store = new JsonFile<>(dir, FILE_NAME, Data.class, SCHEMA_VERSION, gson);
		load();
	}

	/** On-disk shape: a version tag plus one token per character. */
	private static final class Data implements JsonFile.Versioned
	{
		int version = SCHEMA_VERSION;
		Map<Long, String> tokens = new HashMap<>();

		@Override
		public int version()
		{
			return version;
		}
	}

	private synchronized void load()
	{
		tokens.clear();
		Data data = store.read();
		if (data != null && data.tokens != null)
		{
			tokens.putAll(data.tokens);
		}
	}

	/** The credential for {@code accountHash}, or null when this machine has never enrolled that character. */
	public synchronized String get(long accountHash)
	{
		return AccountHash.isKnown(accountHash) ? tokens.get(accountHash) : null;
	}

	/**
	 * Remember the credential the server issued for {@code accountHash}.
	 *
	 * <p>Ignores an unknown account rather than storing under the logged-out sentinel: that key would be
	 * shared by every character, so the first one to enrol would lend its credential to the rest.
	 */
	public synchronized void put(long accountHash, String token)
	{
		if (!AccountHash.isKnown(accountHash) || token == null || token.isEmpty())
		{
			return;
		}
		tokens.put(accountHash, token);
		save();
	}

	/** Forget a credential the server no longer accepts, so the next connection enrols cleanly. */
	public synchronized void clear(long accountHash)
	{
		if (tokens.remove(accountHash) != null)
		{
			save();
		}
	}

	private void save()
	{
		Data data = new Data();
		data.tokens = new HashMap<>(tokens);
		store.write(data);
	}
}
