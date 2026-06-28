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
	private String layout;
	private boolean hardMode;
	private int invocation;
	private long createdAt;

	/**
	 * Live RuneLite P2P room backing this ad; roster/live data is exchanged P2P,
	 * not via the API. {@code null} for legacy/seed ads with no live room.
	 */
	private String passphrase;

	/** Host is the first entry. */
	private List<String> members;

	private int minKillCount;
	private int minHardModeKillCount;
	private boolean privateParty;
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
