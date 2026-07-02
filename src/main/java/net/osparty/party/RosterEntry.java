package net.osparty.party;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One admitted member in the host-authoritative roster carried by
 * {@link PartyStateMessage}. Kept Gson-friendly (no-arg constructor) so it
 * serialises cleanly inside a party message.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RosterEntry
{
	private long memberId;
	private String name;
	/** The member's stable accountHash; {@code 0} when unknown. Lets peers block/favourite by account. */
	private long accountHash;

	public RosterEntry(long memberId, String name)
	{
		this(memberId, name, 0L);
	}
}
