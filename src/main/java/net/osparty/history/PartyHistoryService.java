package net.osparty.history;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.osparty.OSPartyConfig;
import net.osparty.model.Member;
import net.osparty.model.Party;
import net.runelite.client.RuneLite;

/**
 * Local, capped log of the parties the player has been in. Recorded on entry (host or
 * join) and persisted as {@code <runelite>/osparty/history.json}. Newest entry first;
 * the list is trimmed to the configured limit ({@link OSPartyConfig#partyHistoryLimit()})
 * on every write, so lowering the limit takes effect on the next recorded party.
 *
 * <p>All access is guarded by the instance monitor; callers are the Swing EDT.
 */
@Slf4j
@Singleton
public class PartyHistoryService
{
	private static final String FILE_NAME = "history.json";
	private static final int SCHEMA_VERSION = 1;
	/** Absolute ceiling regardless of config, so a bad value can't grow the file unboundedly. */
	private static final int MAX_LIMIT = 500;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final File file;
	private final IntSupplier limitSupplier;
	/** Newest first. Guarded by the instance monitor. */
	private final List<PartyHistoryEntry> entries = new ArrayList<>();

	@Inject
	PartyHistoryService(OSPartyConfig config)
	{
		this(new File(RuneLite.RUNELITE_DIR, "osparty"), config::partyHistoryLimit);
	}

	/** Test/embeddable entry point: store in {@code dir}, taking the cap from {@code limitSupplier}. */
	public PartyHistoryService(File dir, IntSupplier limitSupplier)
	{
		if (!dir.exists() && !dir.mkdirs())
		{
			throw new IllegalStateException("Could not create OSParty data dir: " + dir);
		}
		this.file = new File(dir, FILE_NAME);
		this.limitSupplier = limitSupplier;
		load();
	}

	/** On-disk shape: a version tag plus the ordered entries (newest first). */
	private static final class Data
	{
		int version = SCHEMA_VERSION;
		List<PartyHistoryEntry> entries = new ArrayList<>();
	}

	/**
	 * Record that the player just entered {@code party}. No-op for a null party or one
	 * already at the head of the history (so a resume-after-restart or a repeat render
	 * doesn't duplicate the same party session).
	 */
	public synchronized void record(Party party, boolean hosted)
	{
		if (party == null)
		{
			return;
		}
		String id = party.getId();
		if (id != null)
		{
			for (PartyHistoryEntry e : entries)
			{
				if (id.equals(e.getPartyId()))
				{
					return;
				}
			}
		}
		entries.add(0, new PartyHistoryEntry(id, party.getActivity(), party.getHost(), hosted,
			party.getSize(), party.getCapacity(), party.isHardMode(), party.getInvocation(),
			System.currentTimeMillis(), snapshotMembers(party)));
		trim();
		save();
	}

	/**
	 * Deep-copy the party's roster (name + accountHash, host first) so the stored entry keeps a
	 * stable snapshot even if the live ad's member objects are later reused or mutated. Returns an
	 * empty list when the ad carried no members (e.g. a legacy/seed ad).
	 */
	private static List<Member> snapshotMembers(Party party)
	{
		List<Member> live = party.getMembers();
		if (live == null || live.isEmpty())
		{
			return new ArrayList<>();
		}
		List<Member> copy = new ArrayList<>(live.size());
		for (Member m : live)
		{
			if (m != null)
			{
				copy.add(new Member(m.getName(), m.getAccountHash()));
			}
		}
		return copy;
	}

	/** A snapshot of the history, newest first. */
	public synchronized List<PartyHistoryEntry> list()
	{
		return new ArrayList<>(entries);
	}

	/** Drop all recorded history. */
	public synchronized void clear()
	{
		entries.clear();
		save();
	}

	private void trim()
	{
		int limit = clampLimit(limitSupplier.getAsInt());
		while (entries.size() > limit)
		{
			entries.remove(entries.size() - 1);
		}
	}

	private static int clampLimit(int limit)
	{
		return Math.max(1, Math.min(MAX_LIMIT, limit));
	}

	private void load()
	{
		entries.clear();
		if (!file.exists())
		{
			return;
		}
		try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
		{
			Data data = GSON.fromJson(reader, Data.class);
			if (data != null && data.entries != null)
			{
				for (PartyHistoryEntry e : data.entries)
				{
					if (e != null)
					{
						entries.add(e);
					}
				}
			}
		}
		catch (Exception e)
		{
			log.warn("OSParty: could not read {}, starting with empty history", file, e);
			entries.clear();
		}
		// Honour a limit that may have been lowered since the file was written.
		trim();
	}

	private void save()
	{
		Data data = new Data();
		data.entries.addAll(entries);
		try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8))
		{
			GSON.toJson(data, writer);
		}
		catch (IOException e)
		{
			log.warn("OSParty: failed to write {}", file, e);
		}
	}
}
