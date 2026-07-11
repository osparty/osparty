package net.osparty.party;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * A targeted host -> member handshake for transferring who hosts the party, honoured cooperatively
 * like the rest of the party network. The current host keeps its P2P authority (and the backend ad)
 * until the exchange completes, so a dropped or ignored message never orphans the party:
 *
 * <ol>
 *   <li>{@link Kind#OFFER} (old host -> target): "will you take over?", carrying the fresh
 *       {@code newHostKey} the new host will use to own the backend ad and whether the old host
 *       {@code hostStays} as a member afterwards.</li>
 *   <li>{@link Kind#ACCEPT} (target -> old host): "I'm here and able" — confirms liveness before the
 *       old host performs the (irreversible) backend re-key.</li>
 *   <li>{@link Kind#COMMIT} (old host -> target): the backend ad is now re-keyed to the new host, who
 *       promotes itself to host and adopts the ad.</li>
 *   <li>{@link Kind#ABORT} (old host -> target): the transfer failed; the target stays a member.</li>
 * </ol>
 *
 * <p>{@code newHostKey} travels over the party bus (visible to current members), consistent with the
 * cooperative-trust model of the party network. The previous host's key is invalidated by the re-key,
 * so the only exposure is the new host's key to players already in the room.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class HostTransferMessage extends PartyMemberMessage
{
	public enum Kind
	{
		OFFER, ACCEPT, COMMIT, ABORT
	}

	private Kind kind;

	/** The member this message is aimed at (the offered new host, or the old host on ACCEPT). */
	private long targetMemberId;

	/** OFFER/COMMIT: the secret the new host uses to own the backend ad after the re-key. */
	private String newHostKey;

	/** OFFER: the new host's display name (what the ad's host is reassigned to). */
	private String newHostName;

	/** OFFER/COMMIT: whether the old host remains a member (true) or leaves (false) after the handoff. */
	private boolean hostStays;
}
