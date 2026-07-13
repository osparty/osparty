package net.osparty.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Host-only patch applied to an already-advertised party. Field names mirror the
 * server's {@code PartyUpdate}, so this is serialised straight into the {@code update}
 * frame's {@code patch}. Unlike the keep-alive heartbeat (which only carries live
 * occupancy), every field here is sent so the host can both set and clear values
 * (e.g. an empty description, a zeroed minimum KC). {@code requiredRoles}/
 * {@code hostRole} are null for activities without roles and are then omitted by Gson.
 */
@Data
@AllArgsConstructor
public class PartyEditRequest
{
	private String description;
	private int capacity;
	private String world;
	private int minKillCount;
	private int minHardModeKillCount;
	private String lootRule;
	private boolean privateParty;
	private boolean ironmanOnly;
	private int invocation;
	private boolean hardMode;
	/** Chambers of Xeric team-size scaling (e.g. "3+4"); empty to clear it or when not a CoX ad. */
	private String coxScale;

	/** A multiset of role ids; null when the activity has no roles. */
	private List<String> requiredRoles;

	private String hostRole;
	private boolean learner;
	private boolean teacher;
}
