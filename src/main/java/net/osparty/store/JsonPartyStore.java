package net.osparty.store;

import com.google.gson.Gson;
import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.RuneLite;

/**
 * Flat-file {@link PartyStore}: favourite/block lists as one {@code <runelite>/osparty/flags.json},
 * rewritten on every change.
 */
@Singleton
public class JsonPartyStore implements PartyStore
{
	private static final String FILE_NAME = "flags.json";
	/** v2 keys rows by {@code playerId}; a v1 file (keyed by account hash) is rewritten without the hashes on load. */
	private static final int SCHEMA_VERSION = 2;

	private final JsonFile<Data> store;
	/** kind -&gt; its persisted flags. Guarded by the instance monitor. */
	private final Map<FlagKind, List<PlayerFlag>> flags = new EnumMap<>(FlagKind.class);

	@Inject
	public JsonPartyStore(Gson gson)
	{
		this(new File(RuneLite.RUNELITE_DIR, "osparty"), gson);
	}

	/** Test/embeddable entry point: read (or create) the store in {@code dir}. */
	public JsonPartyStore(File dir, Gson gson)
	{
		this.store = new JsonFile<>(dir, FILE_NAME, Data.class, SCHEMA_VERSION, gson);
		load();
	}

	/** On-disk shape: a version tag plus the per-kind flag lists. */
	private static final class Data implements JsonFile.Versioned
	{
		int version = SCHEMA_VERSION;
		Map<FlagKind, List<PlayerFlag>> flags = new EnumMap<>(FlagKind.class);

		@Override
		public int version()
		{
			return version;
		}
	}

	private void load()
	{
		flags.clear();
		Data data = store.read();
		if (data == null || data.flags == null)
		{
			return;
		}
		for (Map.Entry<FlagKind, List<PlayerFlag>> e : data.flags.entrySet())
		{
			if (e.getKey() != null && e.getValue() != null)
			{
				flags.put(e.getKey(), new ArrayList<>(e.getValue()));
			}
		}
		if (data.version < SCHEMA_VERSION)
		{
			// A v1 row's account hash was skipped on read, so it is already a name-only row here; rewrite now
			// so the hash stops sitting on disk too, rather than only once the next toggle happens to.
			save();
		}
	}

	private void save()
	{
		Data data = new Data();
		data.flags.putAll(flags);
		store.write(data);
	}

	@Override
	public synchronized List<PlayerFlag> loadFlags(FlagKind kind)
	{
		List<PlayerFlag> list = flags.get(kind);
		return list == null ? new ArrayList<>() : new ArrayList<>(list);
	}

	@Override
	public synchronized void upsertFlag(FlagKind kind, PlayerFlag flag)
	{
		List<PlayerFlag> list = flags.computeIfAbsent(kind, k -> new ArrayList<>());
		String norm = lower(flag.getUsername());
		if (flag.hasKnownId())
		{
			// Replace any same-id row and fold in a stale name-only row (id backfill).
			list.removeIf(f -> Objects.equals(f.getPlayerId(), flag.getPlayerId())
				|| (!f.hasKnownId() && lower(f.getUsername()).equals(norm)));
		}
		else
		{
			list.removeIf(f -> !f.hasKnownId() && lower(f.getUsername()).equals(norm));
		}
		list.add(new PlayerFlag(flag.getPlayerId(), flag.getUsername()));
		save();
	}

	@Override
	public synchronized void removeFlag(FlagKind kind, PlayerFlag flag)
	{
		List<PlayerFlag> list = flags.get(kind);
		if (list == null)
		{
			return;
		}
		if (flag.hasKnownId())
		{
			list.removeIf(f -> Objects.equals(f.getPlayerId(), flag.getPlayerId()));
		}
		else
		{
			String norm = lower(flag.getUsername());
			list.removeIf(f -> !f.hasKnownId() && lower(f.getUsername()).equals(norm));
		}
		save();
	}

	@Override
	public synchronized void close()
	{
		// Nothing to release: every mutation is flushed to disk immediately.
	}

	private static String lower(String s)
	{
		return s == null ? "" : s.toLowerCase();
	}
}
