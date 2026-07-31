package net.osparty.model;

import java.util.List;
import lombok.Data;

/**
 * One advertisement from the board: what a host is running, who is in it, and how to reach it. Not the
 * party — the party is the live room, reached over the same socket on the other channel.
 *
 * <p>The {@code activity} field is the activity id (see {@link Activity#getId()}) so the model stays
 * decoupled from the client's enum when (de)serialising.
 *
 * <p>Field names here are the wire, and they must match the server's {@code net.osparty.api.model
 * .Advertisement} exactly — neither side annotates, so both serialise by field name.
 */
@Data
public class Advertisement
{
	private String id;
	private String activity;
	private String host;

	/**
	 * The current host's account hash as the server reports it, or 0 from a server that predates the
	 * field. Read it through {@link #getHostAccountHash()}, never directly: a host transfer rewrites
	 * {@code host} without touching the member list, so the old fallback of member zero goes stale the
	 * moment a party changes hands.
	 */
	private long hostAccountHash;

	private String description;
	private int size;
	private int capacity;
	private String world;
	private String layout;
	private boolean hardMode;
	private int invocation;
	/** Chambers of Xeric team-size scaling as the host advertises it (e.g. "3+4"); null/empty when unset. */
	private String coxScale;
	private long createdAt;

	/**
	 * Cluster-wide revision, bumped by the server on every meaningful write and never on a TTL touch.
	 * What lets a client that already holds the board resume from where it got to instead of being sent
	 * all of it again.
	 */
	private long seq;

	/**
	 * Live room backing this ad. Roster and live member state travel over the live socket, not the
	 * advertisement. {@code null} for seed ads with no live room.
	 */
	private String passphrase;

	/** Host is the first entry. Each carries the member's name plus stable accountHash. */
	private List<Member> members;

	/**
	 * @return the host's accountHash, or {@code 0} when unknown (older host client, or legacy/seed ad).
	 * Used for block/favourite matching. Falls back to member zero only for a server that predates
	 * {@link #hostAccountHash}, where it is the best guess available; member zero is wrong after a host
	 * transfer, which is why the server sends the hash itself.
	 */
	public long getHostAccountHash()
	{
		if (hostAccountHash != 0L)
		{
			return hostAccountHash;
		}
		return members == null || members.isEmpty() ? 0L : members.get(0).getAccountHash();
	}

	private int minKillCount;
	private int minHardModeKillCount;
	private boolean privateAd;
	/**
	 * The pod the host's live room is on, reported by the host. Null on an ad from a plugin that predates
	 * it, in which case joining costs a redirect exactly as it always did.
	 */
	private String node;
	private String inviteCode;
	private String lootRule;
	private boolean ironmanOnly;
	private String hostAccountType;

	/** A multiset of {@link Role#getId()} values, so a doubled-up slot appears twice. */
	private List<String> requiredRoles;

	private String hostRole;

	/** Kept live by the host via heartbeat as members join/leave. */
	private List<String> neededRoles;

	private boolean learner;
	private boolean teacher;

	/** Discord voice channel the server made for this ad, or null when it made none. */
	private String discordChannelId;

	/** Invite to {@link #discordChannelId}, or null when there is no channel. */
	private String discordInviteUrl;

	public boolean isFull()
	{
		return capacity > 0 && size >= capacity;
	}

	public boolean isLearnerRaid()
	{
		return learner || teacher;
	}

	public String learnerLabel()
	{
		if (!isLearnerRaid())
		{
			return null;
		}
		if (teacher && learner)
		{
			return "Learner raid (teacher + learner)";
		}
		return teacher ? "Learner raid (teacher)" : "Learner raid (learner)";
	}
}
