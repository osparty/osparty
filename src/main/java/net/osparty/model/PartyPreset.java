package net.osparty.model;

import java.util.List;
import lombok.Data;

/**
 * A saved snapshot of the Create-party form. Used both for the implicit
 * "last used" recall and for named favourites. Persisted as JSON via
 * ConfigManager, so fields stay simple/serializable.
 */
@Data
public class PartyPreset
{
	/** null/empty marks the implicit "last used" preset. */
	private String name;
	private String activityId;
	private int capacity;
	private String lootRule;
	private int minKc;
	private int hardKc;
	private String world;
	private String description;
	private boolean privateParty;
	private boolean ironmanOnly;
	private boolean includeLayout;
	private boolean hardMode;
	private int invocation;
	private List<String> requiredRoles;
	private String hostRole;
	private boolean learner;
	private boolean teacher;
}
