package net.osparty.model;

import net.runelite.api.IconID;
import net.runelite.api.vars.AccountType;

/**
 * Display helpers for RuneLite's {@link AccountType} (which we can't add methods
 * to) plus lenient (de)serialisation of its name for the API / live payloads.
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

	/**
	 * The account type as the {@code IRONMAN} varbit reports it (0 normal .. 6 unranked group ironman).
	 * Read directly rather than through the deprecated {@code Client#getAccountType()}, whose enum has no
	 * value for an unranked group ironman and so reports one as a normal account -- which is what locked
	 * unranked group ironmen out of ironman-only parties. Unranked is still a group ironman here.
	 */
	public static AccountType fromVarbit(int value)
	{
		switch (value)
		{
			case 1:
				return AccountType.IRONMAN;
			case 2:
				return AccountType.ULTIMATE_IRONMAN;
			case 3:
				return AccountType.HARDCORE_IRONMAN;
			case 4:
			case 6:
				return AccountType.GROUP_IRONMAN;
			case 5:
				return AccountType.HARDCORE_GROUP_IRONMAN;
			default:
				return AccountType.NORMAL;
		}
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

	/**
	 * The icon the game puts in front of this account type's name in chat ({@code <img=N>}), or an empty
	 * string for a normal account.
	 */
	public static String chatIcon(AccountType type)
	{
		if (type == null)
		{
			return "";
		}
		switch (type)
		{
			case IRONMAN:
				return IconID.IRONMAN.toString();
			case HARDCORE_IRONMAN:
				return IconID.HARDCORE_IRONMAN.toString();
			case ULTIMATE_IRONMAN:
				return IconID.ULTIMATE_IRONMAN.toString();
			case GROUP_IRONMAN:
				return IconID.GROUP_IRONMAN.toString();
			case HARDCORE_GROUP_IRONMAN:
				return IconID.HARDCORE_GROUP_IRONMAN.toString();
			default:
				return "";
		}
	}
}
