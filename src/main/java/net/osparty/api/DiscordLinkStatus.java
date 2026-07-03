package net.osparty.api;

/**
 * Result of a {@code getDiscordLink} lookup: whether the caller's accountHash is bound to a Discord
 * account, and if so its id and username (for display). Immutable; {@code discordId}/{@code username}
 * are null when {@link #isLinked()} is false.
 */
public final class DiscordLinkStatus
{
	private final boolean linked;
	private final String discordId;
	private final String username;

	public DiscordLinkStatus(boolean linked, String discordId, String username)
	{
		this.linked = linked;
		this.discordId = discordId;
		this.username = username;
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
}
