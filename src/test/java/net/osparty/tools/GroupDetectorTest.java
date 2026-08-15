package net.osparty.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.osparty.OSPartyConfig;
import net.osparty.model.Activity;
import net.osparty.party.LivePartyBackend;
import net.runelite.api.Client;
import net.runelite.api.FriendsChatManager;
import net.runelite.api.Player;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The group detector: when it offers to host a party for the group you are standing in, when it stays quiet,
 * and who it will say is standing with you.
 */
public class GroupDetectorTest
{
	/** A map region inside the Theatre of Blood. */
	private static final int TOB_REGION = 14642;
	/** Somewhere that is no activity at all. */
	private static final int LUMBRIDGE_REGION = 12850;

	private Client client;
	private OSPartyConfig config;
	private LivePartyBackend liveParty;
	private GroupDetector detector;

	private final AtomicReference<GroupDetector.Group> suggested = new AtomicReference<>();
	private final AtomicInteger suggestions = new AtomicInteger();
	private final AtomicInteger gone = new AtomicInteger();

	@Before
	public void setUp()
	{
		client = mock(Client.class);
		config = mock(OSPartyConfig.class);
		liveParty = mock(LivePartyBackend.class);
		when(config.suggestParty()).thenReturn(true);
		when(client.getMapRegions()).thenReturn(new int[]{TOB_REGION});
		Player local = player("Me", false);
		when(client.getLocalPlayer()).thenReturn(local);
		// The player owns the chat, which is the case the suggestion is for.
		friendsChat("Me");
		nearby();

		detector = new GroupDetector(client, config, liveParty);
		detector.setOnSuggest(group ->
		{
			suggested.set(group);
			suggestions.incrementAndGet();
		});
		detector.setOnGone(gone::incrementAndGet);
	}

	@Test
	public void offersAPartyForAChatWeOwnAtAnActivity()
	{
		nearby(player("Mate", true), player("Third", true));

		detector.tick();

		GroupDetector.Group group = suggested.get();
		assertNotNull(group);
		assertEquals(Activity.THEATRE_OF_BLOOD, group.getActivity());
		assertEquals("Me", group.getFriendsChat());
		assertEquals(2, group.getStandingWith());
	}

	/** Prompting the other four would offer four more parties for the group they are already in. */
	@Test
	public void doesNotOfferInSomeoneElsesChat()
	{
		friendsChat("Someone Else");
		nearby(player("Mate", true));

		detector.tick();

		assertNull(suggested.get());
	}

	/** A chat open while walking past an activity is not a group. */
	@Test
	public void doesNotOfferWithNobodyFromTheChatHere()
	{
		nearby(player("Stranger", false));

		detector.tick();

		assertNull(suggested.get());
	}

	@Test
	public void doesNotOfferAwayFromAnActivity()
	{
		when(client.getMapRegions()).thenReturn(new int[]{LUMBRIDGE_REGION});
		nearby(player("Mate", true));

		detector.tick();

		assertNull(suggested.get());
	}

	@Test
	public void doesNotOfferWhileAlreadyInAParty()
	{
		when(liveParty.isInParty()).thenReturn(true);
		nearby(player("Mate", true));

		detector.tick();

		assertNull(suggested.get());
	}

	@Test
	public void doesNotOfferWhileTheSettingIsOff()
	{
		when(config.suggestParty()).thenReturn(false);
		nearby(player("Mate", true));

		detector.tick();

		assertNull(suggested.get());
	}

	/** The offer is a banner, not a nag: it is made once for a group, not once a tick. */
	@Test
	public void offersOncePerGroup()
	{
		nearby(player("Mate", true));

		detector.tick();
		detector.tick();
		nearby(player("Mate", true), player("Third", true));
		detector.tick();

		assertEquals(1, suggestions.get());
	}

	/** A different activity with the same chat is a different group, and worth asking about again. */
	@Test
	public void offersAgainWhenTheGroupMovesOn()
	{
		nearby(player("Mate", true));
		detector.tick();

		when(client.getMapRegions()).thenReturn(new int[]{Activity.NEX.getRegionIds()[0]});
		detector.tick();

		assertEquals(2, suggestions.get());
		assertEquals(Activity.NEX, suggested.get().getActivity());
	}

	@Test
	public void withdrawsTheOfferWhenTheGroupIsGone()
	{
		nearby(player("Mate", true));
		detector.tick();

		nearby();
		detector.tick();

		assertEquals(1, gone.get());
	}

	@Test
	public void staysQuietAboutAGroupItWasWavedAwayFrom()
	{
		nearby(player("Mate", true));
		detector.tick();

		detector.dismissCurrent();
		detector.tick();
		detector.tick();

		assertEquals(1, suggestions.get());
	}

	/** The question the host side asks about an applicant: is this one of the people standing here? */
	@Test
	public void answersWhoIsStandingWithUs()
	{
		nearby(player("Mate", true), player("Stranger", false));

		detector.tick();

		assertTrue(detector.isStandingWith("Mate"));
		// Names cross the wire as the game writes them: nbsp for spaces, and any casing.
		assertTrue(detector.isStandingWith("mate"));
		assertFalse(detector.isStandingWith("Stranger"));
		assertFalse(detector.isStandingWith("Someone Who Applied From Elsewhere"));
		assertFalse(detector.isStandingWith(null));
		assertEquals(1, detector.standingWithCount());
	}

	@Test
	public void namesTheGameWritesWithNonBreakingSpacesStillMatch()
	{
		nearby(player("Big Boss", true));

		detector.tick();

		assertTrue(detector.isStandingWith("Big Boss"));
	}

	/** Out of a friends chat, nobody is standing with us however many players are on screen. */
	@Test
	public void nobodyIsStandingWithUsOutsideAFriendsChat()
	{
		nearby(player("Mate", true));
		when(client.getFriendsChatManager()).thenReturn(null);

		detector.tick();

		assertFalse(detector.isStandingWith("Mate"));
		assertEquals(0, detector.standingWithCount());
	}

	// ---- helpers ------------------------------------------------------------

	private void friendsChat(String owner)
	{
		FriendsChatManager manager = mock(FriendsChatManager.class);
		when(manager.getOwner()).thenReturn(owner);
		when(client.getFriendsChatManager()).thenReturn(manager);
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
