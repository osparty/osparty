package net.osparty.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Payload sent to the queue API when a player creates a new party. The host is
 * the currently logged in player, resolved by the plugin at request time.
 */
@Data
@AllArgsConstructor
public class PartyRequest
{
	private String activity;
	private String host;
	/** The host's stable accountHash ({@code client.getAccountHash()}); {@code 0} when unknown. */
	private long hostAccountHash;
	private String description;
	private int capacity;
	private String world;
	private int minKillCount;
	private int minHardModeKillCount;

	private String passphrase;

	private boolean privateParty;
	private String lootRule;
	private boolean ironmanOnly;
	private String hostAccountType;
	private boolean hardMode;
	private int invocation;
	/** Chambers of Xeric team-size scaling (e.g. "3+4"); empty when unset or not a CoX ad. */
	private String coxScale;

	/** A multiset of role ids. */
	private List<String> requiredRoles;

	private String hostRole;
	private boolean learner;
	private boolean teacher;
}
