package net.osparty.model;

import java.util.List;
import lombok.Data;

/**
 * A saved snapshot of the Create form. Used both for the implicit "last used" recall and for named
 * favourites. Persisted as JSON via ConfigManager, so fields stay simple/serializable.
 *
 * <p>{@link #privateParty} keeps its old name deliberately: this is the on-disk shape, and renaming it
 * would silently clear that checkbox on every favourite a user has already saved. The mapping to
 * {@code AdvertisementRequest.privateAd} is explicit in {@code CreatePanel}.
 */
@Data
public class AdvertisementPreset
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
	/** Chambers of Xeric team-size scaling (e.g. "3+4"). */
	private String coxScale;
	private List<String> requiredRoles;
	private String hostRole;
	private boolean learner;
	private boolean teacher;
}
