package net.osparty.party;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * The host-authoritative state of a party, broadcast <b>only by the host</b>.
 * RuneLite's party network has no host or access control, so this is how one peer
 * declares itself the authority and publishes the agreed roster and rules. Re-sent
 * whenever the state changes and whenever a new peer joins (so newcomers learn it).
 * Cooperative, not enforced: a modified client could ignore or spoof it.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class PartyStateMessage extends PartyMemberMessage
{
	private long hostMemberId;
	private String hostName;

	private String activityId;

	private int capacity;
	private int minKillCount;
	private int minHardModeKillCount;

	/** Host admits no further applicants. */
	private boolean locked;

	/** Set in the host's final broadcast; the party is closing. */
	private boolean closed;

	/** Discord voice-channel invite URL the host provisioned, or null. Members render a "Join voice" button. */
	private String discordInviteUrl;

	/** Admitted only, host first; no pending applicants. */
	private List<RosterEntry> roster = new ArrayList<>();
}
