package net.osparty.party;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * A party ready-check signal. Anyone can {@link Type#START} one; each member
 * then broadcasts {@link Type#READY} when they ready up. The starter counts as
 * ready by virtue of starting it. All members share one active check, keyed by
 * {@link #checkId}.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ReadyCheckMessage extends PartyMemberMessage
{
	public enum Type
	{
		/** Begin a new ready check (the sender is implicitly ready). */
		START,
		/** The sender has readied up for the active check. */
		READY,
	}

	// NB: not named "type" - RuneLite's party serialization reserves a "type" field
	// as its message-class discriminator, and a collision throws at send time.
	private Type kind;
	private long checkId;

	/** Display name of the player who started the check (START only). */
	private String starter;
}
