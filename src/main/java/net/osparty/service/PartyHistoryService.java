package net.osparty.service;

import com.google.gson.Gson;
import java.io.File;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.osparty.OSPartyConfig;
import net.osparty.model.HistoryMember;
import net.osparty.model.Member;
import net.osparty.model.Advertisement;
import net.osparty.model.PartyHistoryEntry;
import net.osparty.store.JsonFile;
import net.runelite.client.RuneLite;

/**
 * Local, capped log of past parties, persisted as {@code <runelite>/osparty/history.json} (newest
 * first, trimmed to {@link OSPartyConfig#partyHistoryLimit()} on write). Guarded by the instance monitor.
 */
@Singleton
public class PartyHistoryService
{
	private static final String FILE_NAME = "history.json";
	private static final int SCHEMA_VERSION = 2;
	/** Absolute ceiling regardless of config, so a bad value can't grow the file unboundedly. */
	private static final int MAX_LIMIT = 500;

	private final JsonFile<Data> store;
	private final IntSupplier limitSupplier;
	/** Newest first. Guarded by the instance monitor. */
	private final List<PartyHistoryEntry> entries = new ArrayList<>();

	@Inject
	PartyHistoryService(OSPartyConfig config, Gson gson)
	{
		this(new File(RuneLite.RUNELITE_DIR, "osparty"), config::partyHistoryLimit, gson);
	}

	/** Test/embeddable entry point: store in {@code dir}, taking the cap from {@code limitSupplier}. */
	public PartyHistoryService(File dir, IntSupplier limitSupplier, Gson gson)
	{
		this.store = new JsonFile<>(dir, FILE_NAME, Data.class, SCHEMA_VERSION, gson);
		this.limitSupplier = limitSupplier;
		load();
	}

	/** On-disk shape: a version tag plus the ordered entries (newest first). */
	private static final class Data implements JsonFile.Versioned
	{
		int version = SCHEMA_VERSION;
		List<PartyHistoryEntry> entries = new ArrayList<>();

		@Override
		public int version()
		{
			return version;
		}
	}

	/** Record that the player just entered the party behind {@code ad}. No-op for null or an already-recorded party. */
	public synchronized void record(Advertisement ad, boolean hosted)
	{
		if (ad == null)
		{
			return;
		}
		String id = ad.getId();
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
		long now = System.currentTimeMillis();
		List<HistoryMember> snapshot = snapshotMembers(ad, now);
		// size = present-member count; fall back to the ad's size only for a member-less (legacy) ad.
		int size = snapshot.isEmpty() ? ad.getSize() : snapshot.size();
		entries.add(0, new PartyHistoryEntry(id, ad.getActivity(), ad.getHost(), hosted,
			size, ad.getCapacity(), ad.isHardMode(), ad.getInvocation(),
			now, snapshot));
		trim();
		save();
	}

	/**
	 * Merge the live roster into the history row for {@code partyId}: leavers flagged, joiners appended,
	 * rejoiners cleared. No write when unmatched, when {@code live} is null/empty (a transient
	 * disconnect must not flag everyone left), or when nothing changed (called on every tick).
	 *
	 * @return {@code true} when an entry was updated and persisted.
	 */
	public synchronized boolean updateRoster(String partyId, List<Member> live)
	{
		if (partyId == null || live == null || live.isEmpty())
		{
			return false;
		}
		for (PartyHistoryEntry e : entries)
		{
			if (!partyId.equals(e.getPartyId()))
			{
				continue;
			}
			if (mergeRoster(e, live, System.currentTimeMillis()))
			{
				save();
				return true;
			}
			return false; // roster already up to date — skip the write
		}
		return false;
	}

	/**
	 * Mark party {@code partyId} ended: stamp {@code leftAt} on every still-present member so the row
	 * shows nobody still here. Called when the player leaves/disbands. Returns whether anything changed.
	 */
	public synchronized boolean closeParty(String partyId, long when)
	{
		if (partyId == null)
		{
			return false;
		}
		for (PartyHistoryEntry e : entries)
		{
			if (!partyId.equals(e.getPartyId()) || e.getMembers() == null)
			{
				continue;
			}
			boolean changed = false;
			for (HistoryMember m : e.getMembers())
			{
				if (m != null && m.isPresent())
				{
					m.setLeftAt(when);
					changed = true;
				}
			}
			if (changed)
			{
				save();
			}
			return changed;
		}
		return false;
	}

	/** Reconcile {@code entry}'s stored roster against {@code live} (matched by hash, else name). Returns whether changed. */
	private static boolean mergeRoster(PartyHistoryEntry entry, List<Member> live, long now)
	{
		List<HistoryMember> stored = entry.getMembers();
		if (stored == null)
		{
			stored = new ArrayList<>();
			entry.setMembers(stored);
		}
		boolean changed = false;
		BitSet matched = new BitSet(stored.size());

		for (Member lm : live)
		{
			if (lm == null || lm.getName() == null)
			{
				continue;
			}
			int idx = indexOfMember(stored, matched, lm);
			if (idx < 0)
			{
				stored.add(new HistoryMember(lm.getName(), 0L, now, 0, lm.getPlayerId()));
				matched.set(stored.size() - 1); // a joiner appended this pass is present, not a leaver
				changed = true;
				continue;
			}
			matched.set(idx);
			HistoryMember hm = stored.get(idx);
			if (hm.getLeftAt() != 0) // rejoined
			{
				hm.setLeftAt(0);
				changed = true;
			}
			if (isBlank(hm.getPlayerId()) && !isBlank(lm.getPlayerId())) // id finally synced
			{
				hm.setPlayerId(lm.getPlayerId());
				changed = true;
			}
			if (!lm.getName().equals(hm.getName())) // e.g. a display-name change
			{
				hm.setName(lm.getName());
				changed = true;
			}
		}

		// Anyone stored, still marked present, but absent from the live roster has just left.
		for (int i = 0; i < stored.size(); i++)
		{
			HistoryMember hm = stored.get(i);
			if (!matched.get(i) && hm.getLeftAt() == 0)
			{
				hm.setLeftAt(now);
				changed = true;
			}
		}

		int present = 0;
		for (HistoryMember hm : stored)
		{
			if (hm.isPresent())
			{
				present++;
			}
		}
		if (entry.getSize() != present)
		{
			entry.setSize(present);
			changed = true;
		}
		return changed;
	}

	/** Index of the stored member matching {@code lm} (by player id, else name); {@code -1} if newly seen. */
	private static int indexOfMember(List<HistoryMember> stored, BitSet matched, Member lm)
	{
		if (!isBlank(lm.getPlayerId()))
		{
			for (int i = 0; i < stored.size(); i++)
			{
				if (!matched.get(i) && lm.getPlayerId().equals(stored.get(i).getPlayerId()))
				{
					return i;
				}
			}
		}
		for (int i = 0; i < stored.size(); i++)
		{
			HistoryMember hm = stored.get(i);
			// Only match by name where the id can't contradict it (one side unknown) -- otherwise two
			// different accounts that happen to share a name would be folded into one row.
			if (!matched.get(i) && (isBlank(hm.getPlayerId()) || isBlank(lm.getPlayerId()))
				&& sameName(hm.getName(), lm.getName()))
			{
				return i;
			}
		}
		return -1;
	}

	private static boolean sameName(String a, String b)
	{
		return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
	}

	private static boolean isBlank(String s)
	{
		return s == null || s.trim().isEmpty();
	}

	/** The party's initial roster as present members joined at {@code now}; empty when the ad had none. */
	private static List<HistoryMember> snapshotMembers(Advertisement ad, long now)
	{
		List<HistoryMember> out = new ArrayList<>();
		List<Member> live = ad.getMembers();
		if (live == null)
		{
			return out;
		}
		for (Member m : live)
		{
			if (m != null)
			{
				// The board ad's member list carries a playerId directly now, so this is usually already
				// populated; mergeRoster still backfills it from the live roster for a party snapshotted
				// from an older server, or one where it hasn't synced yet. accountHash is deliberately not
				// read here even though the wire form still carries it -- see HistoryMember's class doc.
				out.add(new HistoryMember(m.getName(), 0L, now, 0, m.getPlayerId()));
			}
		}
		return out;
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

	/** Remove a single recorded party (matched by id, else host + joinedAt). Returns whether removed. */
	public synchronized boolean delete(PartyHistoryEntry entry)
	{
		if (entry == null)
		{
			return false;
		}
		for (Iterator<PartyHistoryEntry> it = entries.iterator(); it.hasNext(); )
		{
			if (sameEntry(it.next(), entry))
			{
				it.remove();
				save();
				return true;
			}
		}
		return false;
	}

	private static boolean sameEntry(PartyHistoryEntry a, PartyHistoryEntry b)
	{
		if (a == b)
		{
			return true;
		}
		if (a.getPartyId() != null && b.getPartyId() != null)
		{
			return a.getPartyId().equals(b.getPartyId());
		}
		return a.getJoinedAt() == b.getJoinedAt() && Objects.equals(a.getHost(), b.getHost());
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
		Data data = store.read();
		boolean scrubbed = false;
		if (data != null && data.entries != null)
		{
			for (PartyHistoryEntry e : data.entries)
			{
				if (e != null)
				{
					migrate(e);
					scrubbed |= scrubAccountHash(e);
					entries.add(e);
				}
			}
		}
		// Honour a limit that may have been lowered since the file was written.
		trim();
		if (scrubbed)
		{
			// Rewrite immediately rather than waiting for the next party: the whole point is that the raw
			// hash stops sitting on disk, and a user who never parties again would otherwise keep it there
			// forever despite this running.
			save();
		}
	}

	/** Bring a loaded entry up to the current shape: v1 rows get {@code joinedAt} stamped from the party start. */
	private static void migrate(PartyHistoryEntry entry)
	{
		List<HistoryMember> members = entry.getMembers();
		if (members == null)
		{
			return;
		}
		for (HistoryMember m : members)
		{
			if (m != null && m.getJoinedAt() == 0)
			{
				m.setJoinedAt(entry.getJoinedAt());
			}
		}
	}

	/**
	 * Clear {@link HistoryMember#getAccountHash()} on every row that still has one, for a file written
	 * before {@link HistoryMember#getPlayerId()} existed. The raw account hash of everyone in the party
	 * never needed to sit on disk indefinitely, and this is what removes it from files that predate the
	 * field that replaced it -- new rows never carry one in the first place.
	 *
	 * @return whether anything was cleared, so the caller knows to resave.
	 */
	private static boolean scrubAccountHash(PartyHistoryEntry entry)
	{
		List<HistoryMember> members = entry.getMembers();
		if (members == null)
		{
			return false;
		}
		boolean changed = false;
		for (HistoryMember m : members)
		{
			if (m != null && m.getAccountHash() != 0)
			{
				m.setAccountHash(0);
				changed = true;
			}
		}
		return changed;
	}

	private void save()
	{
		Data data = new Data();
		data.entries.addAll(entries);
		store.write(data);
	}
}
