package net.osparty.model;

import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The host-authoritative snapshot of an advertised party's settings, published over the live room so
 * members track edits to the ad. A member's {@link Advertisement} is the copy it took from the search card when it
 * applied and is never re-fetched, so without this the Party tab keeps showing the description, world,
 * requirements and host it saw at join time — for the rest of the party's life.
 *
 * <p>Carries {@code host} because the ad's owner moves on a host transfer, and every member that is neither
 * the old nor the new host would otherwise keep the old name (and look its badges up under it).
 *
 * <p>Only fields the host can actually change are here; the activity and invite code are fixed at creation.
 * Room state the live layer already owns (roster, locked, Discord URL) deliberately isn't duplicated.
 */
@Data
@NoArgsConstructor
public class PartyMeta
{
	private String host;
	private String description;
	private int capacity;
	private String world;
	private int minKillCount;
	private int minHardModeKillCount;
	private String lootRule;
	private boolean privateAd;
	private boolean ironmanOnly;
	private int invocation;
	private boolean hardMode;
	private String coxScale;
	private List<String> requiredRoles;
	private String hostRole;
	private boolean learner;
	private boolean teacher;

	public static PartyMeta from(Advertisement party)
	{
		PartyMeta meta = new PartyMeta();
		meta.host = party.getHost();
		meta.description = party.getDescription();
		meta.capacity = party.getCapacity();
		meta.world = party.getWorld();
		meta.minKillCount = party.getMinKillCount();
		meta.minHardModeKillCount = party.getMinHardModeKillCount();
		meta.lootRule = party.getLootRule();
		meta.privateAd = party.isPrivateAd();
		meta.ironmanOnly = party.isIronmanOnly();
		meta.invocation = party.getInvocation();
		meta.hardMode = party.isHardMode();
		meta.coxScale = party.getCoxScale();
		meta.requiredRoles = party.getRequiredRoles();
		meta.hostRole = party.getHostRole();
		meta.learner = party.isLearner();
		meta.teacher = party.isTeacher();
		return meta;
	}

	/** Overwrite {@code party}'s advertised settings with ours. */
	public void applyTo(Advertisement party)
	{
		party.setHost(host);
		party.setDescription(description);
		party.setCapacity(capacity);
		party.setWorld(world);
		party.setMinKillCount(minKillCount);
		party.setMinHardModeKillCount(minHardModeKillCount);
		party.setLootRule(lootRule);
		party.setPrivateAd(privateAd);
		party.setIronmanOnly(ironmanOnly);
		party.setInvocation(invocation);
		party.setHardMode(hardMode);
		party.setCoxScale(coxScale);
		party.setRequiredRoles(requiredRoles);
		party.setHostRole(hostRole);
		party.setLearner(learner);
		party.setTeacher(teacher);
	}
}
