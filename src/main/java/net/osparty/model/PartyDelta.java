package net.osparty.model;

import java.util.List;

/**
 * A partial update for an existing {@link Party}, mirroring the server's {@code PartyDelta}.
 * Only the fields actually changed (plus {@code id}/{@code activity}) are populated in an
 * {@code updated} entry of a {@code batch} frame; the rest are null and left untouched on merge.
 *
 * <p>Boxed types matter: a primitive {@code 0}/{@code false} is indistinguishable from "absent",
 * so the nullable wrappers let {@link #applyTo} merge only the fields the server actually sent.
 */
public class PartyDelta
{
	private String id;
	private String activity;
	private Integer size;
	private List<Member> members;
	private String world;
	private String layout;
	private List<String> neededRoles;
	private String description;
	private Integer capacity;
	private String lootRule;
	private Boolean ironmanOnly;
	private Boolean privateParty;
	private Integer minKillCount;
	private Integer minHardModeKillCount;
	private Integer invocation;
	private Boolean hardMode;

	public String getId()
	{
		return id;
	}

	/** Merge the present (non-null) fields of this delta onto an existing party in place. */
	public void applyTo(Party p)
	{
		if (size != null)
		{
			p.setSize(size);
		}
		if (members != null)
		{
			p.setMembers(members);
		}
		if (world != null)
		{
			p.setWorld(world);
		}
		if (layout != null)
		{
			p.setLayout(layout);
		}
		if (neededRoles != null)
		{
			p.setNeededRoles(neededRoles);
		}
		if (description != null)
		{
			p.setDescription(description);
		}
		if (capacity != null)
		{
			p.setCapacity(capacity);
		}
		if (lootRule != null)
		{
			p.setLootRule(lootRule);
		}
		if (ironmanOnly != null)
		{
			p.setIronmanOnly(ironmanOnly);
		}
		if (privateParty != null)
		{
			p.setPrivateParty(privateParty);
		}
		if (minKillCount != null)
		{
			p.setMinKillCount(minKillCount);
		}
		if (minHardModeKillCount != null)
		{
			p.setMinHardModeKillCount(minHardModeKillCount);
		}
		if (invocation != null)
		{
			p.setInvocation(invocation);
		}
		if (hardMode != null)
		{
			p.setHardMode(hardMode);
		}
	}
}
