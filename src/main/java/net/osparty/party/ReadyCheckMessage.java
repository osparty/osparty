package net.osparty.party;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.runelite.client.party.messages.PartyMemberMessage;

/** A party ready-check signal: anyone {@link Type#START}s, each member {@link Type#READY}s, keyed by {@link #checkId}. */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ReadyCheckMessage extends PartyMemberMessage
{
	public enum Type
	{
		/** Begin a new check (sender is implicitly ready). */
		START,
		READY,
	}

	// Not "type": RuneLite's party serialization reserves that field as its class discriminator.
	private Type kind;
	private long checkId;

	/** Set on START only. */
	private String starter;
}
