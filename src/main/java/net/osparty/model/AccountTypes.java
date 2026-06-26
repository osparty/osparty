package net.osparty.model;

import net.runelite.api.vars.AccountType;

/**
 * Display helpers for RuneLite's {@link AccountType} (which we can't add methods
 * to) plus lenient (de)serialisation of its name for the API / P2P payloads.
 */
public final class AccountTypes
{
	private AccountTypes()
	{
	}

	/** Parse a stored account-type name; unknown/blank -> {@link AccountType#NORMAL}. */
	public static AccountType fromName(String name)
	{
		if (name != null)
		{
			try
			{
				return AccountType.valueOf(name.trim().toUpperCase());
			}
			catch (IllegalArgumentException ignored)
			{
				// fall through
			}
		}
		return AccountType.NORMAL;
	}

	public static boolean isIronman(AccountType type)
	{
		return type != null && (type.isIronman() || type.isGroupIronman());
	}

	/** Short tag for a roster/card badge, or {@code null} for a normal account. */
	public static String tag(AccountType type)
	{
		if (type == null)
		{
			return null;
		}
		switch (type)
		{
			case IRONMAN:
				return "IM";
			case HARDCORE_IRONMAN:
				return "HCIM";
			case ULTIMATE_IRONMAN:
				return "UIM";
			case GROUP_IRONMAN:
				return "GIM";
			case HARDCORE_GROUP_IRONMAN:
				return "HCGIM";
			default:
				return null;
		}
	}
}
