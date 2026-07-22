package net.osparty.ui;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.osparty.api.PartyService;
import net.osparty.model.Party;
import net.osparty.party.HostTransferMessage;
import net.osparty.party.LiveParty;

/**
 * Coordinates handing the party to another member without destroying it, driving the
 * {@link HostTransferMessage} handshake (OFFER → ACCEPT → COMMIT / ABORT) and the matching backend
 * ad re-key. The current host keeps its P2P authority and its ownership of the backend ad until the
 * exchange completes, so a dropped/ignored message or an unreachable target never orphans the party —
 * the old host simply stays host.
 *
 * <p>All state here is touched only on the EDT: UI callbacks arrive on the EDT already, and inbound
 * messages / socket acks (which arrive off-EDT) are marshalled on before they mutate anything.
 */
public class HostTransferHandler
{
	/** How long the old host waits for the target to ACCEPT, and the target waits for the COMMIT. */
	private static final int HANDSHAKE_TIMEOUT_MS = 12_000;

	private final LiveParty liveParty;
	private final PartyService partyService;
	private final PartyState partyState;
	private final Supplier<String> localNameSupplier;
	private final Consumer<String> notifier;

	/** Old host: an offer we've sent and are awaiting an ACCEPT for. Null when idle. */
	private OutgoingTransfer outgoing;
	/** New host: an offer we've accepted and are awaiting the COMMIT for. Null when idle. */
	private IncomingTransfer incoming;

	HostTransferHandler(LiveParty liveParty, PartyService partyService, PartyState partyState,
		Supplier<String> localNameSupplier, Consumer<String> notifier)
	{
		this.liveParty = liveParty;
		this.partyService = partyService;
		this.partyState = partyState;
		this.localNameSupplier = localNameSupplier;
		this.notifier = notifier;
	}

	/**
	 * Old host: offer the party to {@code targetMemberId}. No-op if we aren't hosting, a transfer is
	 * already in flight, or the target isn't a live admitted member. Call on the EDT.
	 */
	void offerTransfer(long targetMemberId, boolean hostStays)
	{
		if (!liveParty.isHosting() || !partyState.isHost() || outgoing != null)
		{
			return;
		}
		Party party = partyState.getCurrentParty();
		if (party == null)
		{
			return;
		}
		String targetName = memberName(targetMemberId);
		if (targetName == null)
		{
			notifier.accept("Couldn't transfer the party: that member is no longer available.");
			return;
		}
		String newKey = UUID.randomUUID().toString();
		Timer timeout = new Timer(HANDSHAKE_TIMEOUT_MS, e -> onOfferTimedOut());
		timeout.setRepeats(false);
		outgoing = new OutgoingTransfer(targetMemberId, targetName, newKey, hostStays, timeout);
		liveParty.offerHostTransfer(targetMemberId, newKey, localNameSupplier.get(), hostStays);
		// ASCII only: these land in the game chatbox, whose font has no ellipsis or dash glyph.
		notifier.accept("Transferring the party to " + targetName + "...");
		timeout.start();
	}

	/** Dispatch an inbound handshake message (arrives off-EDT; marshalled on before mutating state). */
	public void onMessage(HostTransferMessage message)
	{
		if (message == null || message.getKind() == null)
		{
			return;
		}
		SwingUtilities.invokeLater(() -> handle(message));
	}

	/** Drop any in-flight transfer (called when the party ends). Call on the EDT. */
	void reset()
	{
		clearOutgoing();
		clearIncoming();
	}

	private void handle(HostTransferMessage message)
	{
		switch (message.getKind())
		{
			case OFFER:
				onOffer(message);
				break;
			case ACCEPT:
				onAccept(message);
				break;
			case COMMIT:
				onCommit(message);
				break;
			case ABORT:
				onAbort(message);
				break;
			default:
				break;
		}
	}

	// ---- new host side -------------------------------------------------------

	private void onOffer(HostTransferMessage message)
	{
		if (!liveParty.isForLocalMember(message.getTargetMemberId()))
		{
			return; // not aimed at us
		}
		// We can only take over if we're actually an admitted member of a party we don't already host.
		if (liveParty.isHosting() || !liveParty.isLocalAdmitted() || partyState.getCurrentParty() == null)
		{
			return;
		}
		clearIncoming();
		Timer timeout = new Timer(HANDSHAKE_TIMEOUT_MS, e -> clearIncoming());
		timeout.setRepeats(false);
		incoming = new IncomingTransfer(message.getMemberId(), message.getNewHostKey(), timeout);
		liveParty.acceptHostTransfer(message.getMemberId());
		timeout.start();
	}

	private void onCommit(HostTransferMessage message)
	{
		if (incoming == null || !liveParty.isForLocalMember(message.getTargetMemberId())
			|| message.getMemberId() != incoming.oldHostId)
		{
			return;
		}
		Party party = partyState.getCurrentParty();
		if (party == null)
		{
			clearIncoming();
			return;
		}
		String key = message.getNewHostKey() != null ? message.getNewHostKey() : incoming.newKey;
		String localName = localNameSupplier.get();
		liveParty.promoteToHost(localName);
		// The backend re-keyed the ad to us; mirror that locally or host-name lookups (and the
		// ad-still-exists check) would keep asking about the old host and fold the tab.
		party.setHost(localName);
		partyService.adoptHostedParty(party.getId(), key);
		partyState.setHosting(party, key);
		notifier.accept("You are now the host of this party.");
		clearIncoming();
	}

	private void onAbort(HostTransferMessage message)
	{
		if (incoming != null && liveParty.isForLocalMember(message.getTargetMemberId()))
		{
			clearIncoming();
		}
	}

	// ---- old host side -------------------------------------------------------

	private void onAccept(HostTransferMessage message)
	{
		if (outgoing == null || !liveParty.isForLocalMember(message.getTargetMemberId())
			|| message.getMemberId() != outgoing.targetId)
		{
			return;
		}
		Party party = partyState.getCurrentParty();
		if (party == null)
		{
			clearOutgoing();
			return;
		}
		outgoing.stopTimeout();
		final OutgoingTransfer transfer = outgoing;
		partyService.transferHost(party.getId(), partyState.getHostKey(), transfer.targetName, transfer.newKey,
			ignored -> SwingUtilities.invokeLater(() -> onTransferAcked(party, transfer)),
			error -> SwingUtilities.invokeLater(() -> onTransferFailed(transfer)));
	}

	private void onTransferAcked(Party party, OutgoingTransfer transfer)
	{
		// Guard against a party that ended (or a second transfer) while the ack was in flight.
		if (outgoing != transfer)
		{
			return;
		}
		liveParty.commitHostTransfer(transfer.targetId, transfer.newKey, transfer.hostStays);
		liveParty.demoteToMember();
		partyService.releaseHostedParty(party.getId());
		party.setHost(transfer.targetName);
		if (transfer.hostStays)
		{
			partyState.demoteToMember(party);
			notifier.accept("You handed the party to " + transfer.targetName + " and are now a member.");
		}
		else
		{
			liveParty.leave();
			partyState.clear();
			notifier.accept("You handed the party to " + transfer.targetName + " and left.");
		}
		clearOutgoing();
	}

	private void onTransferFailed(OutgoingTransfer transfer)
	{
		if (outgoing != transfer)
		{
			return;
		}
		liveParty.abortHostTransfer(transfer.targetId);
		notifier.accept("Couldn't transfer the party. You're still the host.");
		clearOutgoing();
	}

	private void onOfferTimedOut()
	{
		if (outgoing == null)
		{
			return;
		}
		String name = outgoing.targetName;
		liveParty.abortHostTransfer(outgoing.targetId);
		clearOutgoing();
		notifier.accept(name + " didn't respond - you're still the host.");
	}

	// ---- helpers -------------------------------------------------------------

	/** The display name of an admitted, online member (excluding us), or null if not a valid target. */
	private String memberName(long memberId)
	{
		for (LiveParty.RosterMember member : liveParty.roster())
		{
			if (member.getMemberId() == memberId)
			{
				return isCandidate(member) ? member.getName() : null;
			}
		}
		return null;
	}

	/** A member we could hand the party to: an admitted, online member that isn't us. */
	private static boolean isCandidate(LiveParty.RosterMember member)
	{
		return member.getStatus() == LiveParty.Status.MEMBER && member.isOnline() && !member.isLocal();
	}

	private void clearOutgoing()
	{
		if (outgoing != null)
		{
			outgoing.stopTimeout();
			outgoing = null;
		}
	}

	private void clearIncoming()
	{
		if (incoming != null)
		{
			incoming.stopTimeout();
			incoming = null;
		}
	}

	private static final class OutgoingTransfer
	{
		final long targetId;
		final String targetName;
		final String newKey;
		final boolean hostStays;
		final Timer timeout;

		OutgoingTransfer(long targetId, String targetName, String newKey, boolean hostStays, Timer timeout)
		{
			this.targetId = targetId;
			this.targetName = targetName;
			this.newKey = newKey;
			this.hostStays = hostStays;
			this.timeout = timeout;
		}

		void stopTimeout()
		{
			timeout.stop();
		}
	}

	private static final class IncomingTransfer
	{
		final long oldHostId;
		final String newKey;
		final Timer timeout;

		IncomingTransfer(long oldHostId, String newKey, Timer timeout)
		{
			this.oldHostId = oldHostId;
			this.newKey = newKey;
			this.timeout = timeout;
		}

		void stopTimeout()
		{
			timeout.stop();
		}
	}
}
