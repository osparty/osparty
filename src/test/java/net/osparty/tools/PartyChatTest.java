package net.osparty.tools;

import java.util.ArrayList;
import java.util.List;
import net.osparty.OSPartyConfig;
import net.osparty.enums.PartyChatChannel;
import net.osparty.party.LivePartyBackend;
import net.osparty.party.PartyChatEvent;
import net.osparty.service.BlockListService;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.vars.AccountType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ChatboxInput;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Party chat's two edges: what typed lines it claims from the chatbox (and what it leaves for the game),
 * and how a party line is put into the chatbox.
 */
public class PartyChatTest
{
	private Client client;
	private OSPartyConfig config;
	private LivePartyBackend liveParty;
	private BlockListService blockList;
	private ChatCommands commands;
	private PartyChat chat;

	/** Stands in for RuneLite's chat commands: sees every chatbox input, and claims them when told to. */
	private static final class ChatCommands
	{
		final List<ChatboxInput> seen = new ArrayList<>();
		boolean claims;

		@Subscribe
		public void onChatboxInput(ChatboxInput input)
		{
			seen.add(input);
			if (claims)
			{
				input.consume();
			}
		}
	}

	@Before
	public void setUp()
	{
		client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		ClientThread clientThread = mock(ClientThread.class);
		// Everything here is already on the client thread, so invoke() runs its task at once.
		doAnswer(invocation ->
		{
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(clientThread).invoke(any(Runnable.class));
		config = mock(OSPartyConfig.class);
		when(config.partyChat()).thenReturn(true);
		when(config.partyChatPrefix()).thenReturn("!p");
		when(config.partyChatChannel()).thenReturn(PartyChatChannel.FRIENDS_CHAT);
		liveParty = mock(LivePartyBackend.class);
		blockList = mock(BlockListService.class);
		EventBus eventBus = new EventBus();
		commands = new ChatCommands();
		eventBus.register(commands);
		chat = new PartyChat(client, clientThread, config, liveParty, blockList, eventBus);
		// As the plugin wires it: every chatbox input on the bus reaches party chat too.
		eventBus.register(new Object()
		{
			@Subscribe
			public void onChatboxInput(ChatboxInput input)
			{
				chat.onChatboxInput(input);
			}
		});
	}

	// ---- what counts as a party line ----------------------------------------

	@Test
	public void thePrefixIsAWholeWordMatchedRegardlessOfCase()
	{
		assertEquals("on my way", PartyChat.extract("!p on my way", 0, "!p"));
		assertEquals("on my way", PartyChat.extract("  !P   on my way ", 3, "!p"));
		// "!pb" is somebody else's command, and a prefix mid-line is just chat.
		assertNull(PartyChat.extract("!pb", 0, "!p"));
		assertNull(PartyChat.extract("hello !p", 0, "!p"));
		// The prefix on its own: ours, with nothing to send.
		assertEquals("", PartyChat.extract("!p", 0, "!p"));
		assertEquals("", PartyChat.extract("!p   ", 0, "!p"));
	}

	@Test
	public void aSlashPrefixMatchesWhatTheGameLeavesAfterTakingTheSlash()
	{
		// "/p hi" typed into public chat reaches RuneLite as "p hi" routed to clan (3) or friends (2) chat.
		assertEquals("hi", PartyChat.extract("p hi", 3, "/p"));
		assertEquals("hi", PartyChat.extract("p hi", 2, "/p"));
		assertEquals("hi", PartyChat.extract("p hi", 4, "/p"));
		// Should the slash ever come through untouched, it still matches.
		assertEquals("hi", PartyChat.extract("/p hi", 0, "/p"));
		// A public line that merely starts with the letter is not ours.
		assertNull(PartyChat.extract("p hi", 0, "/p"));
	}

	@Test
	public void aBlankPrefixClaimsNothing()
	{
		assertNull(PartyChat.extract("hi", 0, "  "));
		assertNull(PartyChat.extract("hi", 0, null));
		assertNull(PartyChat.extract(null, 0, "!p"));
	}

	// ---- typed lines --------------------------------------------------------

	@Test
	public void aPrefixedLineIsTakenOffTheGameAndSentToTheParty()
	{
		when(liveParty.isInParty()).thenReturn(true);
		when(liveParty.sendChat("gz")).thenReturn(true);
		ChatboxInput input = new ChatboxInput("!p gz", 0, () -> { });

		chat.onChatboxInput(input);

		assertTrue(input.isConsumed());
		verify(liveParty).sendChat("gz");
		verify(client, never()).addChatMessage(any(), any(), any(), any());
	}

	@Test
	public void anOrdinaryLineIsLeftForTheGame()
	{
		ChatboxInput input = new ChatboxInput("gz", 0, () -> { });

		chat.onChatboxInput(input);

		assertFalse(input.isConsumed());
		verify(liveParty, never()).sendChat(anyString());
	}

	@Test
	public void outsideAPartyThePrefixedLineIsSwallowedWithANotice()
	{
		when(liveParty.isInParty()).thenReturn(false);
		ChatboxInput input = new ChatboxInput("!p gz", 0, () -> { });

		chat.onChatboxInput(input);

		// Still consumed: a line meant for the party must never go out as public chat.
		assertTrue(input.isConsumed());
		verify(liveParty, never()).sendChat(anyString());
		verify(client).addChatMessage(eq(ChatMessageType.GAMEMESSAGE), eq(""), contains("not in a party"),
			isNull());
	}

	@Test
	public void anUnadmittedApplicantIsToldWhyNothingWasSent()
	{
		when(liveParty.isInParty()).thenReturn(true);
		when(liveParty.isLocalAdmitted()).thenReturn(false);
		when(liveParty.sendChat("gz")).thenReturn(false);

		chat.onChatboxInput(new ChatboxInput("!p gz", 0, () -> { }));

		verify(client).addChatMessage(eq(ChatMessageType.GAMEMESSAGE), eq(""), contains("hasn't admitted"),
			isNull());
	}

	// ---- RuneLite's chat commands inside a party line --------------------------

	@Test
	public void theLineIsOfferedToTheChatCommandsAndSentAtOnceWhenNoneClaimsIt()
	{
		when(liveParty.isInParty()).thenReturn(true);
		when(liveParty.sendChat("!kc cox")).thenReturn(true);

		chat.onChatboxInput(new ChatboxInput("!p !kc cox", 0, () -> { }));

		assertEquals(1, commands.seen.size());
		assertEquals("!kc cox", commands.seen.get(0).getValue());
		verify(liveParty).sendChat("!kc cox");
	}

	@Test
	public void aCommandThatClaimsTheLineSendsItWhenItResumes()
	{
		when(liveParty.isInParty()).thenReturn(true);
		when(liveParty.sendChat("!kc cox")).thenReturn(true);
		commands.claims = true;

		chat.onChatboxInput(new ChatboxInput("!p !kc cox", 0, () -> { }));
		// !kc is still uploading the count; nothing may go out before it has.
		verify(liveParty, never()).sendChat(anyString());

		commands.seen.get(0).resume();
		verify(liveParty).sendChat("!kc cox");
	}

	@Test
	public void theOfferedLineIsNotReadAsAPartyLineAgain()
	{
		when(liveParty.isInParty()).thenReturn(true);
		when(liveParty.sendChat("!p hi")).thenReturn(true);

		chat.onChatboxInput(new ChatboxInput("!p !p hi", 0, () -> { }));

		verify(liveParty).sendChat("!p hi");
		verify(liveParty, never()).sendChat("hi");
		assertEquals(1, commands.seen.size());
	}

	@Test
	public void switchedOffItLeavesEveryLineAlone()
	{
		when(config.partyChat()).thenReturn(false);
		ChatboxInput input = new ChatboxInput("!p gz", 0, () -> { });

		chat.onChatboxInput(input);

		assertFalse(input.isConsumed());
		verify(liveParty, never()).sendChat(anyString());
	}

	// ---- party lines --------------------------------------------------------

	@Test
	public void aPeersLineIsAddedAsFriendsChatUnderTheOSPartyChannelWithTagsEscaped()
	{
		chat.onPartyChatEvent(new PartyChatEvent(7, "Bob", AccountType.NORMAL, "gz <col=ff0000>all</col>"));

		verify(client).addChatMessage(ChatMessageType.FRIENDSCHAT, "Bob", "gz <lt>col=ff0000<gt>all<lt>/col<gt>",
			"OSParty");
	}

	@Test
	public void anIronmansNameCarriesTheGamesOwnIconLikeARealFriendsChatLine()
	{
		chat.onPartyChatEvent(new PartyChatEvent(7, "Bob", AccountType.HARDCORE_IRONMAN, "gz"));
		chat.onPartyChatEvent(new PartyChatEvent(8, "Gim", AccountType.GROUP_IRONMAN, "gz"));
		// No snapshot yet, so no account type: the name stands on its own.
		chat.onPartyChatEvent(new PartyChatEvent(9, "New", null, "gz"));

		verify(client).addChatMessage(ChatMessageType.FRIENDSCHAT, "<img=10>Bob", "gz", "OSParty");
		verify(client).addChatMessage(ChatMessageType.FRIENDSCHAT, "<img=41>Gim", "gz", "OSParty");
		verify(client).addChatMessage(ChatMessageType.FRIENDSCHAT, "New", "gz", "OSParty");
	}

	@Test
	public void theGameTabLineCarriesTheIconToo()
	{
		when(config.partyChatChannel()).thenReturn(PartyChatChannel.GAME);

		chat.onPartyChatEvent(new PartyChatEvent(7, "Bob", AccountType.IRONMAN, "gz"));

		verify(client).addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[OSParty] <img=2>Bob: gz", null);
	}

	@Test
	public void theGameTabGetsTheWholeLineComposedByHand()
	{
		when(config.partyChatChannel()).thenReturn(PartyChatChannel.GAME);

		chat.onPartyChatEvent(new PartyChatEvent(7, "Bob", AccountType.NORMAL, "gz"));

		verify(client).addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[OSParty] Bob: gz", null);
	}

	@Test
	public void aBlockedPeerIsNotHeard()
	{
		when(liveParty.accountHashForMember(7)).thenReturn(123L);
		when(blockList.isBlocked(123L, "Bob")).thenReturn(true);

		chat.onPartyChatEvent(new PartyChatEvent(7, "Bob", AccountType.NORMAL, "gz"));

		verify(client, never()).addChatMessage(any(), any(), any(), any());
	}

	@Test
	public void ourOwnEchoSkipsTheBlockList()
	{
		when(liveParty.isForLocalMember(1)).thenReturn(true);

		chat.onPartyChatEvent(new PartyChatEvent(1, "Me", AccountType.NORMAL, "gz"));

		verify(blockList, never()).isBlocked(anyLong(), any());
		verify(client).addChatMessage(ChatMessageType.FRIENDSCHAT, "Me", "gz", "OSParty");
	}

	@Test
	public void aLineArrivingWhileLoggedOutIsDropped()
	{
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);

		chat.onPartyChatEvent(new PartyChatEvent(7, "Bob", AccountType.NORMAL, "gz"));

		verify(client, never()).addChatMessage(any(), any(), any(), any());
	}
}
