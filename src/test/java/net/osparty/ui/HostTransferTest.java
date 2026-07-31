package net.osparty.ui;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import net.osparty.api.BoardService;
import net.osparty.model.Advertisement;
import net.osparty.party.HostTransferEvent;
import net.osparty.party.LivePartyBackend;
import net.osparty.party.PartyStatus;
import net.osparty.party.RosterMember;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the {@link HostTransferHandler} handshake against a mocked {@link LivePartyBackend}/{@link BoardService}
 * to cover the three outcomes: the old host handing off (staying or leaving), and the new host taking over.
 * Inbound messages and socket acks are dispatched via the EDT, so the tests flush it before asserting.
 */
public class HostTransferTest
{
	private static final long OLD_HOST_ID = 1L;
	private static final long NEW_HOST_ID = 2L;

	private LivePartyBackend liveParty;
	private BoardService boardService;
	private PartyState partyState;
	private List<String> notes;
	private HostTransferHandler handler;
	private Advertisement ad;

	@Before
	public void setUp()
	{
		liveParty = mock(LivePartyBackend.class);
		boardService = mock(BoardService.class);
		partyState = new PartyState(mock(ConfigManager.class));
		notes = new ArrayList<>();
		handler = new HostTransferHandler(liveParty, boardService, partyState, () -> "LocalName", notes::add);

		ad = new Advertisement();
		ad.setId("p1");
		ad.setHost("OldHost");
		ad.setPassphrase("pp");
	}

	private static RosterMember member(long id, String name, PartyStatus status, boolean local)
	{
		return new RosterMember(id, name, status, null, local, true);
	}

	private static void flushEdt() throws InterruptedException, InvocationTargetException
	{
		SwingUtilities.invokeAndWait(() -> { });
	}

	@Test
	public void oldHostOfferAndStayHandsOffWithoutLeaving() throws Exception
	{
		// We are hosting; NEW_HOST_ID is an admitted online member we can hand off to.
		partyState.setHosting(ad, "old-key");
		when(liveParty.isHosting()).thenReturn(true);
		when(liveParty.roster()).thenReturn(List.of(
			member(OLD_HOST_ID, "LocalName", PartyStatus.HOST, true),
			member(NEW_HOST_ID, "NewHost", PartyStatus.MEMBER, false)));
		when(liveParty.isForLocalMember(OLD_HOST_ID)).thenReturn(true);
		// The backend re-key succeeds immediately.
		doAnswer(inv -> {
			Consumer<Advertisement> onSuccess = inv.getArgument(4);
			onSuccess.accept(null);
			return null;
		}).when(boardService).transferHost(eq("p1"), eq("old-key"), eq("NewHost"), any(), any(), any());

		handler.offerTransfer(NEW_HOST_ID, true);
		verify(liveParty).offerHostTransfer(eq(NEW_HOST_ID), any(), eq("LocalName"), eq(true));

		// The target confirms it's alive; the old host performs the re-key and hands off.
		handler.onMessage(accept(NEW_HOST_ID, OLD_HOST_ID));
		flushEdt(); // onAccept -> transferHost -> onSuccess schedules the relinquish
		flushEdt(); // relinquish

		verify(liveParty).commitHostTransfer(eq(NEW_HOST_ID), any(), eq(true));
		verify(liveParty).demoteToMember();
		verify(boardService).releaseHostedAd("p1");
		verify(liveParty, never()).leave();
		assertFalse("old host is no longer the host", partyState.isHost());
		assertEquals(ad, partyState.getCurrentAd());
		assertEquals("the ad now belongs to the new host", "NewHost", ad.getHost());
	}

	@Test
	public void oldHostTransferAndLeaveLeavesTheRoom() throws Exception
	{
		partyState.setHosting(ad, "old-key");
		when(liveParty.isHosting()).thenReturn(true);
		when(liveParty.roster()).thenReturn(List.of(
			member(NEW_HOST_ID, "NewHost", PartyStatus.MEMBER, false)));
		when(liveParty.isForLocalMember(OLD_HOST_ID)).thenReturn(true);
		doAnswer(inv -> {
			Consumer<Advertisement> onSuccess = inv.getArgument(4);
			onSuccess.accept(null);
			return null;
		}).when(boardService).transferHost(any(), any(), any(), any(), any(), any());

		handler.offerTransfer(NEW_HOST_ID, false);
		handler.onMessage(accept(NEW_HOST_ID, OLD_HOST_ID));
		flushEdt();
		flushEdt();

		verify(liveParty).demoteToMember();
		verify(liveParty).leave();
		assertFalse(partyState.isInParty());
	}

	@Test
	public void failedReKeyAbortsAndKeepsUsHost() throws Exception
	{
		partyState.setHosting(ad, "old-key");
		when(liveParty.isHosting()).thenReturn(true);
		when(liveParty.roster()).thenReturn(List.of(
			member(NEW_HOST_ID, "NewHost", PartyStatus.MEMBER, false)));
		when(liveParty.isForLocalMember(OLD_HOST_ID)).thenReturn(true);
		doAnswer(inv -> {
			Consumer<Throwable> onError = inv.getArgument(5);
			onError.accept(new RuntimeException("nope"));
			return null;
		}).when(boardService).transferHost(any(), any(), any(), any(), any(), any());

		handler.offerTransfer(NEW_HOST_ID, true);
		handler.onMessage(accept(NEW_HOST_ID, OLD_HOST_ID));
		flushEdt();
		flushEdt();

		verify(liveParty).abortHostTransfer(NEW_HOST_ID);
		verify(liveParty, never()).demoteToMember();
		assertTrue("we remain the host after a failed transfer", partyState.isHost());
	}

	@Test
	public void newHostAcceptsThenTakesOverOnCommit() throws Exception
	{
		// We are a plain member being offered the party.
		partyState.setMember(ad);
		when(liveParty.isHosting()).thenReturn(false);
		when(liveParty.isLocalAdmitted()).thenReturn(true);
		when(liveParty.isForLocalMember(NEW_HOST_ID)).thenReturn(true);

		handler.onMessage(offer(NEW_HOST_ID, OLD_HOST_ID, "new-key", true));
		flushEdt();
		verify(liveParty).acceptHostTransfer(OLD_HOST_ID);

		handler.onMessage(commit(NEW_HOST_ID, OLD_HOST_ID, "new-key", true));
		flushEdt();

		verify(liveParty).promoteToHost("LocalName");
		verify(boardService).adoptHostedAd("p1", "new-key");
		assertTrue("new host now hosts the party", partyState.isHost());
		assertEquals("the ad is ours now, so lookups use our name", "LocalName", ad.getHost());
		assertTrue(notes.stream().anyMatch(n -> n.contains("now the host")));
	}

	@Test
	public void commitFromANonOfferingPeerIsIgnored() throws Exception
	{
		partyState.setMember(ad);
		when(liveParty.isForLocalMember(NEW_HOST_ID)).thenReturn(true);

		// A COMMIT arrives without us ever having accepted an offer — ignore it.
		handler.onMessage(commit(NEW_HOST_ID, OLD_HOST_ID, "new-key", true));
		flushEdt();

		verify(liveParty, never()).promoteToHost(any());
		verify(boardService, never()).adoptHostedAd(any(), any());
		assertFalse(partyState.isHost());
	}

	private static HostTransferEvent offer(long target, long from, String key, boolean stays)
	{
		HostTransferEvent m = message(HostTransferEvent.Kind.OFFER, target, from);
		m.setNewHostKey(key);
		m.setNewHostName("NewHost");
		m.setHostStays(stays);
		return m;
	}

	private static HostTransferEvent accept(long from, long target)
	{
		return message(HostTransferEvent.Kind.ACCEPT, target, from);
	}

	private static HostTransferEvent commit(long target, long from, String key, boolean stays)
	{
		HostTransferEvent m = message(HostTransferEvent.Kind.COMMIT, target, from);
		m.setNewHostKey(key);
		m.setHostStays(stays);
		return m;
	}

	private static HostTransferEvent message(HostTransferEvent.Kind kind, long target, long from)
	{
		HostTransferEvent m = new HostTransferEvent();
		m.setKind(kind);
		m.setTargetMemberId(target);
		m.setMemberId(from);
		return m;
	}
}
