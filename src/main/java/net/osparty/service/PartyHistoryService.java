package net.osparty.service;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.osparty.OSPartyConfig;
import net.osparty.model.HistoryMember;
import net.osparty.model.Member;
import net.osparty.model.Party;
import net.osparty.model.PartyHistoryEntry;
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
	private static final int SCHEMA_VERSION = 2;
	/** Absolute ceiling regardless of config, so a bad value can't grow the file unboundedly. */
	private static final int MAX_LIMIT = 500;

	/** Derived from the client's shared Gson (never a fresh instance — the Plugin Hub forbids that). */
	private final Gson gson;
	private final File file;
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
		if (!dir.exists() && !dir.mkdirs())
		{
			throw new IllegalStateException("Could not create OSParty data dir: " + dir);
		}
		this.gson = gson.newBuilder().setPrettyPrinting().create();
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
		long now = System.currentTimeMillis();
		List<HistoryMember> snapshot = snapshotMembers(party, now);
		// size tracks the present-member count; fall back to the ad's own size only for a member-less
		// (legacy/seed) ad so it stays consistent with what updateRoster maintains from here on.
		int size = snapshot.isEmpty() ? party.getSize() : snapshot.size();
		entries.add(0, new PartyHistoryEntry(id, party.getActivity(), party.getHost(), hosted,
			size, party.getCapacity(), party.isHardMode(), party.getInvocation(),
			now, snapshot));
		trim();
		save();
	}

	/**
	 * Merge the live roster of an already-recorded party (matched by {@code partyId}) into its history
	 * row so joins and leaves after the player entered are reflected. Members who left are flagged (a
	 * {@link HistoryMember#getLeftAt() leftAt} timestamp) rather than dropped, new members are appended
	 * with a {@code joinedAt} of now, and a member who reappears has their {@code leftAt} cleared. The
	 * entry's {@link PartyHistoryEntry#getSize() size} tracks the currently-present count.
	 *
	 * <p>No-op — and, crucially, no disk write — when there is no matching entry, when {@code live} is
	 * null/empty (a transient disconnected roster must not flag everyone as left), or when nothing
	 * actually changed. That last guard matters because this is called on every live-party tick
	 * (presence pings included), so we only touch {@code history.json} when membership truly changed.
	 *
	 * @return {@code true} when an entry was updated and persisted; {@code false} otherwise.
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
	 * Mark the party {@code partyId} as ended: stamp {@link HistoryMember#getLeftAt() leftAt} on every
	 * member still flagged present so the concluded row shows nobody as "still here" (the local player /
	 * host included). Called when the player leaves, disbands, or the party otherwise ends — the point
	 * past which we can no longer observe the roster, so "present at the end" is recorded as left then.
	 *
	 * <p>The frozen {@link PartyHistoryEntry#getSize() size} is left as the last active count (a handy
	 * "how big it got" summary); nothing mutates the row after this. No-op — no write — when there's no
	 * matching entry or everyone had already left. Returns whether anything changed.
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

	/**
	 * Reconcile {@code entry}'s stored roster against the {@code live} members observed at {@code now}.
	 * Returns whether anything changed. Members are identified by accountHash when known, falling back
	 * to name; a stored member whose hash was unknown is upgraded once the live roster carries one.
	 */
	private static boolean mergeRoster(PartyHistoryEntry entry, List<Member> live, long now)
	{
		List<HistoryMember> stored = entry.getMembers();
		if (stored == null)
		{
			stored = new ArrayList<>();
			entry.setMembers(stored);
		}
		boolean changed = false;
		boolean[] matched = new boolean[stored.size()];

		for (Member lm : live)
		{
			if (lm == null || lm.getName() == null)
			{
				continue;
			}
			int idx = indexOfMember(stored, matched, lm);
			if (idx < 0)
			{
				stored.add(new HistoryMember(lm.getName(), lm.getAccountHash(), now, 0));
				changed = true;
				continue;
			}
			matched[idx] = true;
			HistoryMember hm = stored.get(idx);
			if (hm.getLeftAt() != 0) // rejoined
			{
				hm.setLeftAt(0);
				changed = true;
			}
			if (hm.getAccountHash() == 0 && lm.getAccountHash() != 0) // hash finally synced
			{
				hm.setAccountHash(lm.getAccountHash());
				changed = true;
			}
			if (!lm.getName().equals(hm.getName())) // e.g. a display-name change
			{
				hm.setName(lm.getName());
				changed = true;
			}
		}

		// Anyone stored, still marked present, but absent from the live roster has just left.
		for (int i = 0; i < matched.length; i++)
		{
			HistoryMember hm = stored.get(i);
			if (!matched[i] && hm.getLeftAt() == 0)
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

	/**
	 * Index of the stored (not yet matched) member corresponding to {@code lm}: by accountHash when
	 * both carry one, else by case-insensitive name. {@code -1} when this is a newly-seen member.
	 */
	private static int indexOfMember(List<HistoryMember> stored, boolean[] matched, Member lm)
	{
		if (lm.getAccountHash() != 0)
		{
			for (int i = 0; i < stored.size(); i++)
			{
				if (!matched[i] && stored.get(i).getAccountHash() == lm.getAccountHash())
				{
					return i;
				}
			}
		}
		for (int i = 0; i < stored.size(); i++)
		{
			HistoryMember hm = stored.get(i);
			// Only match by name where the hash can't contradict it (one side unknown).
			if (!matched[i] && (hm.getAccountHash() == 0 || lm.getAccountHash() == 0)
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

	/**
	 * The party's initial roster (name + accountHash, host first) as present members joined at
	 * {@code now}. Returns an empty list when the ad carried no members (e.g. a legacy/seed ad).
	 */
	private static List<HistoryMember> snapshotMembers(Party party, long now)
	{
		List<HistoryMember> out = new ArrayList<>();
		List<Member> live = party.getMembers();
		if (live == null)
		{
			return out;
		}
		for (Member m : live)
		{
			if (m != null)
			{
				out.add(new HistoryMember(m.getName(), m.getAccountHash(), now, 0));
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

	/**
	 * Remove a single recorded party, matched by identity: the party id when both sides carry one,
	 * else host + joinedAt (the same identity {@code keyOf} uses for UI state). Returns whether an
	 * entry was removed (and the file rewritten).
	 */
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
		if (!file.exists())
		{
			return;
		}
		try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
		{
			Data data = gson.fromJson(reader, Data.class);
			if (data != null && data.entries != null)
			{
				for (PartyHistoryEntry e : data.entries)
				{
					if (e != null)
					{
						migrate(e);
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

	/**
	 * Bring a loaded entry up to the current shape. Rows written by the pre-timestamp format (v1)
	 * deserialise into {@link HistoryMember}s with {@code joinedAt}/{@code leftAt} defaulting to 0;
	 * treat everyone as still-present (they were, when recorded) and stamp {@code joinedAt} with the
	 * party's own start time so the UI has a sensible value rather than the epoch.
	 */
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

	private void save()
	{
		Data data = new Data();
		data.entries.addAll(entries);
		try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8))
		{
			gson.toJson(data, writer);
		}
		catch (IOException e)
		{
			log.warn("OSParty: failed to write {}", file, e);
		}
	}
}
