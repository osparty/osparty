package net.osparty.model;

/** How a party splits drops. Advertised on the ad and shown in the UI. */
public enum LootRule
{
	UNSPECIFIED("Unspecified"),
	FFA("FFA"),
	SPLIT("Split");

	private final String displayName;

	LootRule(String displayName)
	{
		this.displayName = displayName;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	@Override
	public String toString()
	{
		return displayName;
	}

	/** Parse a stored rule name leniently; unknown/blank -> {@link #UNSPECIFIED}. */
	public static LootRule fromName(String name)
	{
		if (name != null)
		{
			for (LootRule rule : values())
			{
				if (rule.name().equalsIgnoreCase(name.trim()))
				{
					return rule;
				}
			}
		}
		return UNSPECIFIED;
	}
}
