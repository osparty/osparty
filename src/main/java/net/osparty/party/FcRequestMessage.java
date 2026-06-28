package net.osparty.party;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Host -> member request asking a specific peer to join the host's friends chat.
 * The target client shows it as a brief, self-dismissing in-game popup. Purely
 * cooperative: it cannot move anyone, it just asks.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class FcRequestMessage extends PartyMemberMessage
{
	/** Ignored by everyone but this member. */
	private long targetMemberId;

	private String hostName;

	private String friendsChat;
}
