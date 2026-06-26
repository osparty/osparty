package net.osparty.model;

import java.util.List;
import lombok.Data;

/**
 * A party as returned by the queue API. The {@code activity} field is the
 * activity id (see {@link Activity#getId()}) so the model stays decoupled from
 * the client's enum when (de)serialising.
 */
@Data
public class Party
{
	private String id;
	private String activity;
	private String host;
	private String description;
	private int size;
	private int capacity;
	private String world;
	private long createdAt;

	/**
	 * RuneLite party passphrase for the live peer-to-peer room backing this ad.
	 * The API only advertises it; the actual roster/live data is exchanged P2P.
	 * May be {@code null} for legacy/seed ads with no live room.
	 */
	private String passphrase;

	/** Current members by player name; the host is the first entry. */
	private List<String> members;

	/** Minimum kills required to apply. 0 means no requirement. */
	private int minKillCount;

	/**
	 * Minimum kills of the activity's harder variant (CM/HM/Expert) required to
	 * apply. 0 means no requirement; only meaningful for raids.
	 */
	private int minHardModeKillCount;

	/** Private parties aren't listed in search — joined via {@link #inviteCode}. */
	private boolean privateParty;

	/** Short code used to find this party (shown to the host to share). */
	private String inviteCode;

	/** Loot rule: FFA / SPLIT / UNSPECIFIED (see {@link LootRule}). */
	private String lootRule;

	/** When true, only ironman accounts should join. */
	private boolean ironmanOnly;

	/** The host's account type name (NORMAL / IRONMAN / ...). */
	private String hostAccountType;

	public boolean isFull()
	{
		return capacity > 0 && size >= capacity;
	}
}
