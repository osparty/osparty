package net.osparty.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.osparty.model.Activity;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the watcher makes of the game's party state over a run of ticks: a party proven to be the local
 * player's is offered the tick that is settled; anyone else's, the login replay, and a party that comes and
 * goes are not. What the ad takes from the game is read by {@link RaidPartyWatcher#snapshot} afterwards.
 */
public class RaidPartyWatcherTest
{
	private static final String LOCAL = "Local Guy";

	private final Map<Integer, Integer> varbits = new HashMap<>();
	private final Map<Integer, Integer> varps = new HashMap<>();
	private final Map<Integer, String> varcs = new HashMap<>();
	private final Map<Integer, Widget> widgets = new HashMap<>();
	private final List<RaidPartyDetected> detected = new ArrayList<>();
	private Client client;
	private RaidPartyWatcher watcher;

	@Before
	public void setUp()
	{
		client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getVarbitValue(anyInt())).thenAnswer(inv -> varbits.getOrDefault(inv.<Integer>getArgument(0), 0));
		when(client.getVarpValue(anyInt())).thenAnswer(inv -> varps.getOrDefault(inv.<Integer>getArgument(0), 0));
		when(client.getVarcStrValue(anyInt())).thenAnswer(inv -> varcs.get(inv.<Integer>getArgument(0)));
		when(client.getWidget(anyInt())).thenAnswer(inv -> widgets.get(inv.<Integer>getArgument(0)));
		Player local = mock(Player.class);
		when(local.getName()).thenReturn(LOCAL);
		when(client.getLocalPlayer()).thenReturn(local);
		varps.put(VarPlayerID.RAIDS_PARTY_GROUPHOLDER, -1);

		watcher = new RaidPartyWatcher(client);
		watcher.setListener(detected::add);
	}

	private void ticks(int count)
	{
		for (int i = 0; i < count; i++)
		{
			watcher.update();
		}
	}

	private void settle()
	{
		ticks(RaidPartyWatcher.LOGIN_GRACE_TICKS + 1);
	}

	/** The tick that notices the party, and the one that judges it. */
	private void noticeAndJudge()
	{
		ticks(2);
	}

	/** Long enough for a party nobody vouched for to be given up on. */
	private void giveUp()
	{
		ticks(RaidPartyWatcher.OWNERSHIP_TICKS + 2);
	}

	private void makeTobParty(String firstName)
	{
		varbits.put(VarbitID.TOB_CLIENT_PARTYSTATUS, 1);
		varcs.put(VarClientID.TOB_CLIENT_NAME0, firstName);
	}

	private Widget widget(String text)
	{
		Widget widget = mock(Widget.class);
		when(widget.getText()).thenReturn(text);
		return widget;
	}

	private void openTobDetails(String action)
	{
		widgets.put(InterfaceID.TobPartydetails.UNIVERSE, mock(Widget.class));
		Widget button = mock(Widget.class);
		when(button.getActions()).thenReturn(new String[]{action, null});
		widgets.put(InterfaceID.TobPartydetails.ACTION, button);
	}

	@Test
	public void theatrePartyWithOurNameFirstIsOffered()
	{
		settle();
		makeTobParty(LOCAL);
		noticeAndJudge();

		assertEquals(1, detected.size());
		assertEquals(Activity.THEATRE_OF_BLOOD, detected.get(0).getActivity());
		assertNull(detected.get(0).getHardMode());
		assertEquals(0, detected.get(0).getPreferredSize());
	}

	@Test
	public void theatrePartyWithSomeoneElseFirstIsNot()
	{
		settle();
		makeTobParty("Someone Else");
		giveUp();

		assertTrue(detected.isEmpty());
	}

	@Test
	public void ownerNameMatchesAcrossTagsAndSpacing()
	{
		settle();
		makeTobParty("<img=2>local guy");
		noticeAndJudge();

		assertEquals(1, detected.size());
	}

	@Test
	public void makePartyClickVouchesForAPartyWithNoRosterNames()
	{
		settle();
		watcher.onBoardClicked(Activity.THEATRE_OF_BLOOD, "Make party", InterfaceID.TobPartylist.MYPARTY);
		ticks(2);
		varbits.put(VarbitID.TOB_CLIENT_PARTYSTATUS, 1);
		noticeAndJudge();

		assertEquals(1, detected.size());
		assertEquals(Activity.THEATRE_OF_BLOOD, detected.get(0).getActivity());
	}

	@Test
	public void staleMakePartyClickVouchesForNothing()
	{
		settle();
		watcher.onBoardClicked(Activity.THEATRE_OF_BLOOD, "Make party", InterfaceID.TobPartylist.MYPARTY);
		ticks(RaidPartyWatcher.MAKE_PARTY_CLICK_TICKS + 1);
		varbits.put(VarbitID.TOB_CLIENT_PARTYSTATUS, 1);
		giveUp();

		assertTrue(detected.isEmpty());
	}

	@Test
	public void otherBoardClicksVouchForNothing()
	{
		settle();
		watcher.onBoardClicked(Activity.THEATRE_OF_BLOOD, "Apply", InterfaceID.TobPartydetails.ACTION);
		ticks(2);
		varbits.put(VarbitID.TOB_CLIENT_PARTYSTATUS, 1);
		giveUp();

		assertTrue(detected.isEmpty());
	}

	@Test
	public void theLeadersDisbandButtonProvesOwnershipWhileTheScreenIsUp()
	{
		settle();
		varbits.put(VarbitID.TOB_CLIENT_PARTYSTATUS, 1);
		openTobDetails("Disband");
		ticks(3);

		assertEquals(1, detected.size());
	}

	@Test
	public void aMembersLeaveButtonProvesNothing()
	{
		settle();
		varbits.put(VarbitID.TOB_CLIENT_PARTYSTATUS, 1);
		openTobDetails("Leave");
		giveUp();

		assertTrue(detected.isEmpty());
	}

	@Test
	public void aPartyNobodyVouchesForIsGivenUpOnAndNotRevisited()
	{
		settle();
		varbits.put(VarbitID.TOB_CLIENT_PARTYSTATUS, 1);
		giveUp();
		openTobDetails("Disband");
		ticks(3);

		assertTrue("the party was already judged; a later screen does not reopen it", detected.isEmpty());
	}

	@Test
	public void snapshotReadsTheTheatreDetailsWhileTheyAreUpAndKeepsThemAfter()
	{
		settle();
		makeTobParty(LOCAL);
		noticeAndJudge();
		widgets.put(InterfaceID.TobPartydetails.UNIVERSE, mock(Widget.class));
		widgets.put(InterfaceID.TobPartydetails.MODE, widget("Mode: Hard"));
		widgets.put(InterfaceID.TobPartydetails.SIZE, widget("Preferred size: 5"));
		ticks(1);
		widgets.clear();
		ticks(1);

		RaidPartyDetected now = watcher.snapshot(Activity.THEATRE_OF_BLOOD);
		assertEquals(Boolean.TRUE, now.getHardMode());
		assertEquals(5, now.getPreferredSize());

		varbits.put(VarbitID.TOB_CLIENT_PARTYSTATUS, 0);
		ticks(1);
		RaidPartyDetected gone = watcher.snapshot(Activity.THEATRE_OF_BLOOD);
		assertNull(gone.getHardMode());
		assertEquals(0, gone.getPreferredSize());
	}

	@Test
	public void modeAndSizeAreReadFromTheRowsFirstChild()
	{
		settle();
		makeTobParty(LOCAL);
		noticeAndJudge();
		widgets.put(InterfaceID.TobPartydetails.UNIVERSE, mock(Widget.class));
		Widget modeLabel = widget("Mode: <col=ffffff>Entry</col>");
		Widget modeRow = mock(Widget.class);
		when(modeRow.getChild(0)).thenReturn(modeLabel);
		widgets.put(InterfaceID.TobPartydetails.MODE, modeRow);
		Widget sizeLabel = widget("Preferred Size: 4");
		Widget sizeRow = mock(Widget.class);
		when(sizeRow.getChild(0)).thenReturn(sizeLabel);
		widgets.put(InterfaceID.TobPartydetails.SIZE, sizeRow);
		ticks(1);

		RaidPartyDetected now = watcher.snapshot(Activity.THEATRE_OF_BLOOD);
		assertEquals(Boolean.FALSE, now.getHardMode());
		assertEquals(4, now.getPreferredSize());
	}

	@Test
	public void anUnsetPreferredSizeReadsAsNone()
	{
		settle();
		makeTobParty(LOCAL);
		noticeAndJudge();
		widgets.put(InterfaceID.TobPartydetails.UNIVERSE, mock(Widget.class));
		Widget sizeLabel = widget("Preferred Size: -");
		Widget sizeRow = mock(Widget.class);
		when(sizeRow.getChild(0)).thenReturn(sizeLabel);
		widgets.put(InterfaceID.TobPartydetails.SIZE, sizeRow);
		ticks(1);

		assertEquals(0, watcher.snapshot(Activity.THEATRE_OF_BLOOD).getPreferredSize());
	}

	@Test
	public void snapshotReadsTheChambersPartyAsItIsNow()
	{
		settle();
		varps.put(VarPlayerID.RAIDS_PARTY_GROUPHOLDER, 7);
		varbits.put(VarbitID.RAIDS_CLIENT_ISLEADER, 1);
		noticeAndJudge();
		assertEquals(1, detected.size());
		assertEquals(Boolean.FALSE, detected.get(0).getHardMode());

		varbits.put(VarbitID.RAIDS_CHALLENGE_MODE, 1);
		varbits.put(VarbitID.RAIDS_LOBBY_PARTYSIZE, 4);
		RaidPartyDetected now = watcher.snapshot(Activity.CHAMBERS_OF_XERIC);
		assertEquals(Boolean.TRUE, now.getHardMode());
		assertEquals(4, now.getPreferredSize());
	}

	@Test
	public void loginReplayIsNotACreation()
	{
		makeTobParty(LOCAL);
		settle();
		giveUp();

		assertTrue(detected.isEmpty());
	}

	@Test
	public void changeDuringTheLoginGraceIsNotACreationEither()
	{
		ticks(2);
		makeTobParty(LOCAL);
		ticks(RaidPartyWatcher.LOGIN_GRACE_TICKS);
		giveUp();

		assertTrue(detected.isEmpty());
	}

	@Test
	public void tombsPartyCarriesTheInvocationLevel()
	{
		settle();
		varbits.put(VarbitID.TOA_CLIENT_PARTYSTATUS, 1);
		varcs.put(VarClientID.TOA_CLIENT_NAME0, LOCAL);
		varbits.put(VarbitID.TOA_CLIENT_RAID_LEVEL, 150);
		noticeAndJudge();

		assertEquals(1, detected.size());
		assertEquals(Activity.TOMBS_OF_AMASCUT, detected.get(0).getActivity());
		assertEquals(150, detected.get(0).getInvocation());
	}

	@Test
	public void tombsRaidLevelComesFromTheScreenWhileTheVarbitStillReadsZero()
	{
		settle();
		varbits.put(VarbitID.TOA_CLIENT_PARTYSTATUS, 1);
		varcs.put(VarClientID.TOA_CLIENT_NAME0, LOCAL);
		widgets.put(InterfaceID.ToaPartydetails.UNIVERSE, mock(Widget.class));
		widgets.put(InterfaceID.ToaPartydetails.RAID_LEVEL, widget("Raid Level: 300 <col=ffffff>(19)</col>"));
		noticeAndJudge();

		assertEquals(1, detected.size());
		assertEquals(300, detected.get(0).getInvocation());

		varbits.put(VarbitID.TOA_CLIENT_RAID_LEVEL, 150);
		assertEquals("a live varbit outranks the remembered screen text", 150,
			watcher.snapshot(Activity.TOMBS_OF_AMASCUT).getInvocation());
	}

	@Test
	public void chambersLeaderIsOfferedWithChallengeModeAndSize()
	{
		settle();
		varps.put(VarPlayerID.RAIDS_PARTY_GROUPHOLDER, 7);
		varbits.put(VarbitID.RAIDS_CLIENT_ISLEADER, 1);
		varbits.put(VarbitID.RAIDS_CHALLENGE_MODE, 1);
		varbits.put(VarbitID.RAIDS_LOBBY_PARTYSIZE, 3);
		varbits.put(VarbitID.RAIDS_SCALING, 4);
		noticeAndJudge();

		assertEquals(1, detected.size());
		assertEquals(Activity.CHAMBERS_OF_XERIC, detected.get(0).getActivity());
		assertEquals(Boolean.TRUE, detected.get(0).getHardMode());
		assertEquals(3, detected.get(0).getPreferredSize());
		assertEquals("the remembered board scaling rides along", "4", detected.get(0).getCoxScale());
	}

	@Test
	public void chambersPartyOfAnotherChatMemberIsOfferedOnlyOnceAdvertised()
	{
		settle();
		varps.put(VarPlayerID.RAIDS_PARTY_GROUPHOLDER, 7);
		giveUp();
		assertTrue(detected.isEmpty());

		watcher.onBoardClicked(Activity.CHAMBERS_OF_XERIC, "Advertise", InterfaceID.RaidsLobbyPartydetails.ADVERTISE);
		noticeAndJudge();
		assertEquals(1, detected.size());
		assertEquals(Boolean.FALSE, detected.get(0).getHardMode());

		watcher.onAdvertiseClicked();
		noticeAndJudge();
		assertEquals("the same party is offered once", 1, detected.size());
	}

	@Test
	public void chambersPartyReturningAfterARaidIsNotNew()
	{
		settle();
		varbits.put(VarbitID.RAIDS_CLIENT_ISLEADER, 1);
		varbits.put(VarbitID.RAIDS_CLIENT_INDUNGEON, 1);
		ticks(3);
		varbits.put(VarbitID.RAIDS_CLIENT_INDUNGEON, 0);
		ticks(2);
		varps.put(VarPlayerID.RAIDS_PARTY_GROUPHOLDER, 7);
		ticks(RaidPartyWatcher.RAID_EXIT_GRACE_TICKS + RaidPartyWatcher.OWNERSHIP_TICKS);

		assertTrue(detected.isEmpty());
	}

	@Test
	public void partyDissolvedBeforeItIsJudgedIsDropped()
	{
		settle();
		varbits.put(VarbitID.TOB_CLIENT_PARTYSTATUS, 1);
		ticks(2);
		varbits.put(VarbitID.TOB_CLIENT_PARTYSTATUS, 0);
		openTobDetails("Disband");
		giveUp();

		assertTrue(detected.isEmpty());
	}

	@Test
	public void aPartyIsOfferedOnceAndTheNextOneAgain()
	{
		settle();
		makeTobParty(LOCAL);
		ticks(RaidPartyWatcher.OWNERSHIP_TICKS * 5);
		assertEquals(1, detected.size());

		varbits.put(VarbitID.TOB_CLIENT_PARTYSTATUS, 0);
		ticks(2);
		makeTobParty(LOCAL);
		noticeAndJudge();
		assertEquals(2, detected.size());
	}

	@Test
	public void resetForgetsThePendingPartyAndBaselinesAgain()
	{
		settle();
		watcher.onBoardClicked(Activity.THEATRE_OF_BLOOD, "Make party", InterfaceID.TobPartylist.MYPARTY);
		varbits.put(VarbitID.TOB_CLIENT_PARTYSTATUS, 1);
		ticks(1);
		watcher.reset();
		settle();
		giveUp();

		assertTrue(detected.isEmpty());
	}

	@Test
	public void partyMadeAtAnotherRaidsLobbyIsIgnored()
	{
		when(client.getMapRegions()).thenReturn(new int[]{4919});
		settle();
		makeTobParty(LOCAL);
		noticeAndJudge();

		assertTrue(detected.isEmpty());
	}

	@Test
	public void modeAndSizeTextsAreReadLoosely()
	{
		assertEquals(Boolean.TRUE, RaidPartyWatcher.parseMode("Hard Mode"));
		assertEquals(Boolean.FALSE, RaidPartyWatcher.parseMode("Mode: Normal"));
		assertEquals(Boolean.FALSE, RaidPartyWatcher.parseMode("Entry Mode"));
		assertNull(RaidPartyWatcher.parseMode("Mode"));
		assertNull(RaidPartyWatcher.parseMode(null));
		assertEquals(4, RaidPartyWatcher.parseSize("Preferred size: 4", Activity.THEATRE_OF_BLOOD));
		assertEquals(0, RaidPartyWatcher.parseSize("Preferred size: Any", Activity.THEATRE_OF_BLOOD));
		assertEquals(0, RaidPartyWatcher.parseSize("Size: 9", Activity.THEATRE_OF_BLOOD));
	}
}
