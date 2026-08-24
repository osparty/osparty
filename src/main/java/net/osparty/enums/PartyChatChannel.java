package net.osparty.enums;

import net.runelite.api.ChatMessageType;

/**
 * Where party chat lines land in the chatbox, by way of the message type they are added as: the game files
 * a line under a tab by its type, so a friends-chat line sits in the Channel tab, a clan line in Clan, and
 * a game message in Game. Configurable via {@link net.osparty.OSPartyConfig#partyChatChannel()}.
 */
public enum PartyChatChannel
{
	FRIENDS_CHAT("Channel tab (as friends chat)", ChatMessageType.FRIENDSCHAT),
	CLAN_CHAT("Clan tab (as clan chat)", ChatMessageType.CLAN_CHAT),
	GAME("Game tab (as a game message)", ChatMessageType.GAMEMESSAGE);

	private final String label;
	private final ChatMessageType messageType;

	PartyChatChannel(String label, ChatMessageType messageType)
	{
		this.label = label;
		this.messageType = messageType;
	}

	public ChatMessageType messageType()
	{
		return messageType;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
