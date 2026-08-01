package net.osparty.ui;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.osparty.api.BoardService;
import net.osparty.model.Advertisement;
import net.osparty.party.HostTransferEvent;
import net.osparty.party.LivePartyBackend;
import net.osparty.party.PartyStatus;
import net.osparty.party.RosterMember;

/**
 * Coordinates handing the party to another member without destroying it, driving the
 * {@link HostTransferEvent} handshake (OFFER → ACCEPT → COMMIT / ABORT) and the matching backend
 * ad re-key. The current host keeps its authority and its ownership of the backend ad until the
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

	private final LivePartyBackend liveParty;
	private final BoardService boardService;
	private final PartyState partyState;
	private final Supplier<String> localNameSupplier;
	private final Consumer<String> notifier;

	/** The one handshake in flight: ours to give (OUTGOING) or ours to take (INCOMING). Null when idle. */
	private PendingTransfer pending;

	HostTransferHandler(LivePartyBackend liveParty, BoardService boardService, PartyState partyState,
		Supplier<String> localNameSupplier, Consumer<String> notifier)
	{
		this.liveParty = liveParty;
		this.boardService = boardService;
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
		if (!liveParty.isHosting() || !partyState.isHost() || pending != null)
		{
			return;
		}
		Advertisement ad = partyState.getCurrentAd();
		if (ad == null)
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
		pending = new PendingTransfer(Direction.OUTGOING, targetMemberId, targetName, newKey, hostStays, timeout);
		liveParty.offerHostTransfer(targetMemberId, newKey, localNameSupplier.get(), hostStays);
		// ASCII only: these land in the game chatbox, whose font has no ellipsis or dash glyph.
		notifier.accept("Transferring the party to " + targetName + "...");
		timeout.start();
	}

	/** Dispatch an inbound handshake message (arrives off-EDT; marshalled on before mutating state). */
	public void onMessage(HostTransferEvent message)
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
		clearPending();
	}

	private void handle(HostTransferEvent message)
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

	private void onOffer(HostTransferEvent message)
	{
		if (!liveParty.isForLocalMember(message.getTargetMemberId()))
		{
			return; // not aimed at us
		}
		// We can only take over if we're actually an admitted member of a party we don't already host.
		if (liveParty.isHosting() || !liveParty.isLocalAdmitted() || partyState.getCurrentAd() == null)
		{
			return;
		}
		clearPending();
		Timer timeout = new Timer(HANDSHAKE_TIMEOUT_MS, e -> clearPending());
		timeout.setRepeats(false);
		pending = new PendingTransfer(Direction.INCOMING, message.getMemberId(), null,
			message.getNewHostKey(), false, timeout);
		liveParty.acceptHostTransfer(message.getMemberId());
		timeout.start();
	}

	private void onCommit(HostTransferEvent message)
	{
		PendingTransfer transfer = pending(Direction.INCOMING);
		if (transfer == null || !liveParty.isForLocalMember(message.getTargetMemberId())
			|| message.getMemberId() != transfer.peerId)
		{
			return;
		}
		Advertisement ad = partyState.getCurrentAd();
		if (ad == null)
		{
			clearPending();
			return;
		}
		String key = message.getNewHostKey() != null ? message.getNewHostKey() : transfer.newKey;
		String localName = localNameSupplier.get();
		liveParty.promoteToHost(localName);
		// The backend re-keyed the ad to us; mirror that locally or host-name lookups (and the
		// ad-still-exists check) would keep asking about the old host and fold the tab.
		ad.setHost(localName);
		boardService.adoptHostedAd(ad.getId(), key);
		partyState.setHosting(ad, key);
		notifier.accept("You are now the host of this party.");
		clearPending();
	}

	private void onAbort(HostTransferEvent message)
	{
		if (pending(Direction.INCOMING) != null && liveParty.isForLocalMember(message.getTargetMemberId()))
		{
			clearPending();
		}
	}

	// ---- old host side -------------------------------------------------------

	private void onAccept(HostTransferEvent message)
	{
		final PendingTransfer transfer = pending(Direction.OUTGOING);
		if (transfer == null || !liveParty.isForLocalMember(message.getTargetMemberId())
			|| message.getMemberId() != transfer.peerId)
		{
			return;
		}
		Advertisement ad = partyState.getCurrentAd();
		if (ad == null)
		{
			clearPending();
			return;
		}
		transfer.stopTimeout();
		boardService.transferHost(ad.getId(), partyState.getHostKey(), transfer.peerName, transfer.newKey,
			ignored -> SwingUtilities.invokeLater(() -> onTransferAcked(ad, transfer)),
			error -> SwingUtilities.invokeLater(() -> onTransferFailed(transfer)));
	}

	private void onTransferAcked(Advertisement ad, PendingTransfer transfer)
	{
		// Guard against a party that ended (or a second transfer) while the ack was in flight.
		if (pending != transfer)
		{
			return;
		}
		liveParty.commitHostTransfer(transfer.peerId, transfer.newKey, transfer.hostStays);
		liveParty.demoteToMember();
		boardService.releaseHostedAd(ad.getId());
		ad.setHost(transfer.peerName);
		if (transfer.hostStays)
		{
			partyState.demoteToMember(ad);
			notifier.accept("You handed the party to " + transfer.peerName + " and are now a member.");
		}
		else
		{
			liveParty.leave();
			partyState.clear();
			notifier.accept("You handed the party to " + transfer.peerName + " and left.");
		}
		clearPending();
	}

	private void onTransferFailed(PendingTransfer transfer)
	{
		if (pending != transfer)
		{
			return;
		}
		liveParty.abortHostTransfer(transfer.peerId);
		notifier.accept("Couldn't transfer the party. You're still the host.");
		clearPending();
	}

	private void onOfferTimedOut()
	{
		PendingTransfer transfer = pending(Direction.OUTGOING);
		if (transfer == null)
		{
			return;
		}
		liveParty.abortHostTransfer(transfer.peerId);
		clearPending();
		notifier.accept(transfer.peerName + " didn't respond - you're still the host.");
	}

	// ---- helpers -------------------------------------------------------------

	/** The display name of an admitted, online member (excluding us), or null if not a valid target. */
	private String memberName(long memberId)
	{
		for (RosterMember member : liveParty.roster())
		{
			if (member.getMemberId() == memberId)
			{
				return isCandidate(member) ? member.getName() : null;
			}
		}
		return null;
	}

	/** A member we could hand the party to: an admitted, online member that isn't us. */
	private static boolean isCandidate(RosterMember member)
	{
		return member.getStatus() == PartyStatus.MEMBER && member.isOnline() && !member.isLocal();
	}

	/** The in-flight transfer when it runs in {@code direction}, else null. */
	private PendingTransfer pending(Direction direction)
	{
		return pending != null && pending.direction == direction ? pending : null;
	}

	private void clearPending()
	{
		if (pending != null)
		{
			pending.stopTimeout();
			pending = null;
		}
	}

	private enum Direction
	{
		/** We're the old host, awaiting the target's ACCEPT. */
		OUTGOING,
		/** We're the new host, awaiting the old host's COMMIT. */
		INCOMING
	}

	private static final class PendingTransfer
	{
		final Direction direction;
		/** The other side: the target we offered, or the old host that offered us. */
		final long peerId;
		/** Outgoing only: the target's display name. */
		final String peerName;
		final String newKey;
		/** Outgoing only: whether we stay in the party after handing it over. */
		final boolean hostStays;
		final Timer timeout;

		PendingTransfer(Direction direction, long peerId, String peerName, String newKey, boolean hostStays,
			Timer timeout)
		{
			this.direction = direction;
			this.peerId = peerId;
			this.peerName = peerName;
			this.newKey = newKey;
			this.hostStays = hostStays;
			this.timeout = timeout;
		}

		void stopTimeout()
		{
			timeout.stop();
		}
	}
}
