package net.osparty.store;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Flat-file {@link PartyStore}. Persists the favourite / block lists as a single
 * {@code <runelite>/osparty/flags.json} document, rewritten whenever a flag changes
 * (the lists are small and mutated from the EDT at human speed, so a full rewrite is
 * cheap and keeps the on-disk file trivially inspectable).
 *
 * <p>Replaces the former H2-backed store: the RuneLite Plugin Hub's
 * dependency-verification made bundling H2 fragile, and plain JSON needs no runtime
 * dependency at all. Matching semantics (hash-keyed when known, name-only otherwise)
 * are identical, so callers are unaffected.
 */
@Slf4j
@Singleton
public class JsonPartyStore implements PartyStore
{
	private static final String FILE_NAME = "flags.json";
	private static final int SCHEMA_VERSION = 1;

	/** Derived from the client's shared Gson (never a fresh instance — the Plugin Hub forbids that). */
	private final Gson gson;
	private final File file;
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
		if (!dir.exists() && !dir.mkdirs())
		{
			throw new IllegalStateException("Could not create OSParty data dir: " + dir);
		}
		this.gson = gson.newBuilder().setPrettyPrinting().create();
		this.file = new File(dir, FILE_NAME);
		load();
	}

	/** On-disk shape: a version tag plus the per-kind flag lists. */
	private static final class Data
	{
		int version = SCHEMA_VERSION;
		Map<FlagKind, List<PlayerFlag>> flags = new EnumMap<>(FlagKind.class);
	}

	private void load()
	{
		flags.clear();
		if (!file.exists())
		{
			return;
		}
		try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
		{
			Data data = gson.fromJson(reader, Data.class);
			if (data != null && data.flags != null)
			{
				for (Map.Entry<FlagKind, List<PlayerFlag>> e : data.flags.entrySet())
				{
					if (e.getKey() != null && e.getValue() != null)
					{
						flags.put(e.getKey(), new ArrayList<>(e.getValue()));
					}
				}
			}
		}
		catch (Exception e)
		{
			// Corrupt/unreadable file: start empty rather than break the plugin. A subsequent
			// write overwrites the bad file.
			log.warn("OSParty: could not read {}, starting with empty flags", file, e);
			flags.clear();
		}
	}

	private void save()
	{
		Data data = new Data();
		data.flags.putAll(flags);
		try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8))
		{
			gson.toJson(data, writer);
		}
		catch (IOException e)
		{
			log.warn("OSParty: failed to write {}", file, e);
		}
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
			// Replace any row with the same hash, and fold in a stale name-only row for the
			// same username (hash backfill) — mirrors the old H2 upsert.
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
