package net.osparty.api;

/**
 * Result of a {@code getDiscordLink} lookup: whether the caller's accountHash is bound to a Discord
 * account, and if so its id and username (for display). Immutable; {@code discordId}/{@code username}
 * are null when {@link #isLinked()} is false.
 *
 * <p>{@code badgesVisible} is the caller's badge-privacy preference (server-side): when false, the
 * API strips this account's Discord-role badges from party ads so other players never see them.
 */
public final class DiscordLinkStatus
{
	private final boolean linked;
	private final String discordId;
	private final String username;
	private final boolean badgesVisible;

	public DiscordLinkStatus(boolean linked, String discordId, String username, boolean badgesVisible)
	{
		this.linked = linked;
		this.discordId = discordId;
		this.username = username;
		this.badgesVisible = badgesVisible;
	}

	public boolean isLinked()
	{
		return linked;
	}

	public String getDiscordId()
	{
		return discordId;
	}

	public String getUsername()
	{
		return username;
	}

	public boolean isBadgesVisible()
	{
		return badgesVisible;
	}
}
