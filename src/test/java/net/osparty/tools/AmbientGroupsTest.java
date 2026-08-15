package net.osparty.tools;

import java.util.ArrayList;
import java.util.List;
import net.osparty.OSPartyConfig;
import net.osparty.party.LivePartyBackend;
import net.runelite.api.Client;
import net.runelite.api.FriendsChatManager;
import net.runelite.api.Player;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The group detector: when a party appears out of a friends chat, what it reports, and what it never does.
 *
 * <p>The room key is the load-bearing part — everyone in a group has to derive the same one with nothing
 * passed between them, and two clients deriving different ones would split a group in half with no error
 * anywhere to say so.
 */
public class AmbientGroupsTest
{
	/** A map region inside the Theatre of Blood, which holds five. */
	private static final int TOB_REGION = 14642;

	private Client client;
	private OSPartyConfig config;
	private LivePartyBackend liveParty;
	private AmbientGroups groups;

	@Before
	public void setUp()
	{
		client = mock(Client.class);
		config = mock(OSPartyConfig.class);
		liveParty = mock(LivePartyBackend.class);
		when(config.ambientGroups()).thenReturn(true);
		when(client.getMapRegions()).thenReturn(new int[]{TOB_REGION});
		// Built before the stubbing that returns it: stubbing a mock inside another's thenReturn(…) leaves
		// Mockito mid-stub and fails the whole class.
		Player local = player("Me", false);
		when(client.getLocalPlayer()).thenReturn(local);
		friendsChat("Host");
		nearby();
		groups = new AmbientGroups(client, config, liveParty);
	}

	@Test
	public void everyoneInAGroupDerivesTheSameKey()
	{
		assertEquals(AmbientGroups.roomKey("tob", "Host"), AmbientGroups.roomKey("tob", "Host"));
		// However the name reached each client: the game renders spaces in names as a non-breaking space,
		// and a chat owner is not always cased the same way twice.
		assertEquals(AmbientGroups.roomKey("tob", "Big Boss"), AmbientGroups.roomKey("tob", "big boss"));
	}

	@Test
	public void aDifferentGroupOrActivityIsADifferentRoom()
	{
		assertNotEquals(AmbientGroups.roomKey("tob", "Host"), AmbientGroups.roomKey("cox", "Host"));
		assertNotEquals(AmbientGroups.roomKey("tob", "Host"), AmbientGroups.roomKey("tob", "Other"));
	}

	/** The key must not read as the friends chat it came from, which is who is raiding with whom. */
	@Test
	public void theKeyCarriesNoNames()
	{
		String key = AmbientGroups.roomKey("tob", "Big Boss");

		assertEquals(22, key.length());
		assertTrue(key, key.matches("[A-Za-z0-9_-]{22}"));
		assertTrue(key, !key.toLowerCase().contains("boss"));
	}

	/** A friends chat at an activity, with someone from it standing there, is a group. */
	@Test
	public void attendsOnceSomeoneFromTheChatIsInSight()
	{
		nearby(player("Mate", true));

		groups.tick();

		verify(liveParty).attendGroup(eq(AmbientGroups.roomKey("tob", "Host")), eq("tob"), eq(5),
			eq("Me"), eq(java.util.Collections.singletonList("Mate")));
		assertTrue(groups.isAttending());
	}

	/**
	 * Being in a friends chat while walking past an activity is not a group. Nothing is lost by waiting: the
	 * first two people to stand together each see the other.
	 */
	@Test
	public void doesNotAttendWithNobodyFromTheChatInSight()
	{
		nearby(player("Stranger", false));

		groups.tick();

		verify(liveParty, never()).attendGroup(anyString(), anyString(), anyInt(), anyString(), any());
	}

	@Test
	public void ignoresEverythingWhileTheSettingIsOff()
	{
		when(config.ambientGroups()).thenReturn(false);
		nearby(player("Mate", true));

		groups.tick();

		verify(liveParty, never()).attendGroup(anyString(), anyString(), anyInt(), anyString(), any());
	}

	/** No friends chat, or nowhere in particular, is not a group either. */
	@Test
	public void needsBothAFriendsChatAndAnActivity()
	{
		nearby(player("Mate", true));
		when(client.getFriendsChatManager()).thenReturn(null);
		groups.tick();

		friendsChat("Host");
		when(client.getMapRegions()).thenReturn(new int[]{12850});
		groups.tick();

		verify(liveParty, never()).attendGroup(anyString(), anyString(), anyInt(), anyString(), any());
	}

	/** Only what changed is sent: an unchanged report every tick would be a frame a tick for nothing. */
	@Test
	public void reportsOnlyWhenWhatItCanSeeChanges()
	{
		nearby(player("Mate", true));
		when(liveParty.isAmbient()).thenReturn(true);
		groups.tick();
		groups.tick();

		verify(liveParty, never()).reportSighted(any());

		nearby(player("Mate", true), player("Third", true));
		groups.tick();

		verify(liveParty).reportSighted(java.util.Arrays.asList("Mate", "Third"));
	}

	/** The scene hands players back in no particular order; a reshuffle is not a change. */
	@Test
	public void aReshuffledSceneIsNotAChange()
	{
		nearby(player("Mate", true), player("Third", true));
		when(liveParty.isAmbient()).thenReturn(true);
		groups.tick();

		nearby(player("Third", true), player("Mate", true));
		groups.tick();

		verify(liveParty, never()).reportSighted(any());
	}

	/** Stepping out has to stick: the friends chat is still there, and the next tick would walk back in. */
	@Test
	public void staysOutOfAGroupItWasDismissedFrom()
	{
		nearby(player("Mate", true));
		when(liveParty.isAmbient()).thenReturn(true);
		groups.tick();

		groups.dismissCurrent();
		groups.tick();
		groups.tick();

		verify(liveParty).leave();
		verify(liveParty, times(1)).attendGroup(anyString(), anyString(), anyInt(), anyString(), any());
	}

	/** A party the player chose is not one to be dropped because a friends chat happens to be open. */
	@Test
	public void leavesAChosenPartyAlone()
	{
		nearby(player("Mate", true));
		when(liveParty.isInParty()).thenReturn(true);
		when(liveParty.isAmbient()).thenReturn(false);

		groups.tick();

		verify(liveParty, never()).attendGroup(anyString(), anyString(), anyInt(), anyString(), any());
		verify(liveParty, never()).leave();
	}

	/** The group broke up (or we left the chat): the room goes with it. */
	@Test
	public void leavesTheRoomWhenTheGroupIsOver()
	{
		nearby(player("Mate", true));
		when(liveParty.isAmbient()).thenReturn(true);
		groups.tick();

		friendsChatGone();
		groups.tick();

		verify(liveParty).leave();
	}

	// ---- helpers ------------------------------------------------------------

	private void friendsChat(String owner)
	{
		FriendsChatManager manager = mock(FriendsChatManager.class);
		when(manager.getOwner()).thenReturn(owner);
		when(client.getFriendsChatManager()).thenReturn(manager);
	}

	private void friendsChatGone()
	{
		when(client.getFriendsChatManager()).thenReturn(null);
	}

	private void nearby(Player... players)
	{
		List<Player> list = new ArrayList<>(java.util.Arrays.asList(players));
		when(client.getPlayers()).thenReturn(list);
	}

	private static Player player(String name, boolean inFriendsChat)
	{
		Player player = mock(Player.class);
		when(player.getName()).thenReturn(name);
		when(player.isFriendsChatMember()).thenReturn(inFriendsChat);
		return player;
	}
}
