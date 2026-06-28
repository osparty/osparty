package net.osparty.party;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * A map ping: a party member highlights a world tile for everyone to see. The
 * sender's display name and chosen colour travel with the message so peers don't
 * need to resolve them locally.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class PingMessage extends PartyMemberMessage
{
	private int worldX;
	private int worldY;
	private int plane;

	private String name;

	/** Packed ARGB int. */
	private int color;
}
