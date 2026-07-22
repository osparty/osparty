package net.osparty.ui;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import net.osparty.api.PartyService;
import net.osparty.model.Party;
import net.osparty.party.HostTransferMessage;
import net.osparty.party.LiveParty;
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
 * Drives the {@link HostTransferHandler} handshake against a mocked {@link LiveParty}/{@link PartyService}
 * to cover the three outcomes: the old host handing off (staying or leaving), and the new host taking over.
 * Inbound messages and socket acks are dispatched via the EDT, so the tests flush it before asserting.
 */
public class HostTransferTest
{
	private static final long OLD_HOST_ID = 1L;
	private static final long NEW_HOST_ID = 2L;

	private LiveParty liveParty;
	private PartyService partyService;
	private PartyState partyState;
	private List<String> notes;
	private HostTransferHandler handler;
	private Party party;

	@Before
	public void setUp()
	{
		liveParty = mock(LiveParty.class);
		partyService = mock(PartyService.class);
		partyState = new PartyState(mock(ConfigManager.class));
		notes = new ArrayList<>();
		handler = new HostTransferHandler(liveParty, partyService, partyState, () -> "LocalName", notes::add);

		party = new Party();
		party.setId("p1");
		party.setHost("OldHost");
		party.setPassphrase("pp");
	}

	private static LiveParty.RosterMember member(long id, String name, LiveParty.Status status, boolean local)
	{
		return new LiveParty.RosterMember(id, name, status, null, local, true);
	}

	private static void flushEdt() throws InterruptedException, InvocationTargetException
	{
		SwingUtilities.invokeAndWait(() -> { });
	}

	@Test
	public void oldHostOfferAndStayHandsOffWithoutLeaving() throws Exception
	{
		// We are hosting; NEW_HOST_ID is an admitted online member we can hand off to.
		partyState.setHosting(party, "old-key");
		when(liveParty.isHosting()).thenReturn(true);
		when(liveParty.roster()).thenReturn(List.of(
			member(OLD_HOST_ID, "LocalName", LiveParty.Status.HOST, true),
			member(NEW_HOST_ID, "NewHost", LiveParty.Status.MEMBER, false)));
		when(liveParty.isForLocalMember(OLD_HOST_ID)).thenReturn(true);
		// The backend re-key succeeds immediately.
		doAnswer(inv -> {
			Consumer<Party> onSuccess = inv.getArgument(4);
			onSuccess.accept(null);
			return null;
		}).when(partyService).transferHost(eq("p1"), eq("old-key"), eq("NewHost"), any(), any(), any());

		handler.offerTransfer(NEW_HOST_ID, true);
		verify(liveParty).offerHostTransfer(eq(NEW_HOST_ID), any(), eq("LocalName"), eq(true));

		// The target confirms it's alive; the old host performs the re-key and hands off.
		handler.onMessage(accept(NEW_HOST_ID, OLD_HOST_ID));
		flushEdt(); // onAccept -> transferHost -> onSuccess schedules the relinquish
		flushEdt(); // relinquish

		verify(liveParty).commitHostTransfer(eq(NEW_HOST_ID), any(), eq(true));
		verify(liveParty).demoteToMember();
		verify(partyService).releaseHostedParty("p1");
		verify(liveParty, never()).leave();
		assertFalse("old host is no longer the host", partyState.isHost());
		assertEquals(party, partyState.getCurrentParty());
		assertEquals("the ad now belongs to the new host", "NewHost", party.getHost());
	}

	@Test
	public void oldHostTransferAndLeaveLeavesTheRoom() throws Exception
	{
		partyState.setHosting(party, "old-key");
		when(liveParty.isHosting()).thenReturn(true);
		when(liveParty.roster()).thenReturn(List.of(
			member(NEW_HOST_ID, "NewHost", LiveParty.Status.MEMBER, false)));
		when(liveParty.isForLocalMember(OLD_HOST_ID)).thenReturn(true);
		doAnswer(inv -> {
			Consumer<Party> onSuccess = inv.getArgument(4);
			onSuccess.accept(null);
			return null;
		}).when(partyService).transferHost(any(), any(), any(), any(), any(), any());

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
		partyState.setHosting(party, "old-key");
		when(liveParty.isHosting()).thenReturn(true);
		when(liveParty.roster()).thenReturn(List.of(
			member(NEW_HOST_ID, "NewHost", LiveParty.Status.MEMBER, false)));
		when(liveParty.isForLocalMember(OLD_HOST_ID)).thenReturn(true);
		doAnswer(inv -> {
			Consumer<Throwable> onError = inv.getArgument(5);
			onError.accept(new RuntimeException("nope"));
			return null;
		}).when(partyService).transferHost(any(), any(), any(), any(), any(), any());

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
		partyState.setMember(party);
		when(liveParty.isHosting()).thenReturn(false);
		when(liveParty.isLocalAdmitted()).thenReturn(true);
		when(liveParty.isForLocalMember(NEW_HOST_ID)).thenReturn(true);

		handler.onMessage(offer(NEW_HOST_ID, OLD_HOST_ID, "new-key", true));
		flushEdt();
		verify(liveParty).acceptHostTransfer(OLD_HOST_ID);

		handler.onMessage(commit(NEW_HOST_ID, OLD_HOST_ID, "new-key", true));
		flushEdt();

		verify(liveParty).promoteToHost("LocalName");
		verify(partyService).adoptHostedParty("p1", "new-key");
		assertTrue("new host now hosts the party", partyState.isHost());
		assertEquals("the ad is ours now, so lookups use our name", "LocalName", party.getHost());
		assertTrue(notes.stream().anyMatch(n -> n.contains("now the host")));
	}

	@Test
	public void commitFromANonOfferingPeerIsIgnored() throws Exception
	{
		partyState.setMember(party);
		when(liveParty.isForLocalMember(NEW_HOST_ID)).thenReturn(true);

		// A COMMIT arrives without us ever having accepted an offer — ignore it.
		handler.onMessage(commit(NEW_HOST_ID, OLD_HOST_ID, "new-key", true));
		flushEdt();

		verify(liveParty, never()).promoteToHost(any());
		verify(partyService, never()).adoptHostedParty(any(), any());
		assertFalse(partyState.isHost());
	}

	private static HostTransferMessage offer(long target, long from, String key, boolean stays)
	{
		HostTransferMessage m = message(HostTransferMessage.Kind.OFFER, target, from);
		m.setNewHostKey(key);
		m.setNewHostName("NewHost");
		m.setHostStays(stays);
		return m;
	}

	private static HostTransferMessage accept(long from, long target)
	{
		return message(HostTransferMessage.Kind.ACCEPT, target, from);
	}

	private static HostTransferMessage commit(long target, long from, String key, boolean stays)
	{
		HostTransferMessage m = message(HostTransferMessage.Kind.COMMIT, target, from);
		m.setNewHostKey(key);
		m.setHostStays(stays);
		return m;
	}

	private static HostTransferMessage message(HostTransferMessage.Kind kind, long target, long from)
	{
		HostTransferMessage m = new HostTransferMessage();
		m.setKind(kind);
		m.setTargetMemberId(target);
		m.setMemberId(from);
		return m;
	}
}
