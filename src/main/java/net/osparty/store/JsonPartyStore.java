package net.osparty.store;

import com.google.gson.Gson;
import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
	private static final int SCHEMA_VERSION = 1;

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
		if (flag.hasKnownHash())
		{
			// Replace any same-hash row and fold in a stale name-only row (hash backfill).
			list.removeIf(f -> f.getAccountHash() == flag.getAccountHash()
				|| (!f.hasKnownHash() && lower(f.getUsername()).equals(norm)));
		}
		else
		{
			list.removeIf(f -> !f.hasKnownHash() && lower(f.getUsername()).equals(norm));
		}
		list.add(new PlayerFlag(flag.getAccountHash(), flag.getUsername()));
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
		if (flag.hasKnownHash())
		{
			list.removeIf(f -> f.getAccountHash() == flag.getAccountHash());
		}
		else
		{
			String norm = lower(flag.getUsername());
			list.removeIf(f -> !f.hasKnownHash() && lower(f.getUsername()).equals(norm));
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
