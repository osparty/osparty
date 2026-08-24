package net.osparty.tools;

import java.awt.Color;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.osparty.OSPartyConfig;
import net.osparty.enums.PartyChatChannel;
import net.osparty.model.AccountTypes;
import net.osparty.party.LivePartyBackend;
import net.osparty.party.PartyChatEvent;
import net.osparty.service.BlockListService;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.vars.AccountType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.ChatboxInput;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/**
 * Party chat through the game's own chatbox. A line typed with the configured prefix in front of it is
 * taken off the game — it never reaches public or clan chat — and sent to the party instead; lines from the
 * party, our own included, are added to the chatbox the way a friends chat's are, under an {@code [OSParty]}
 * channel name, so they land in whichever tab the player keeps that kind of chat in.
 *
 * <p>The prefix is matched on what RuneLite hands us, which is not always what was typed: the game resolves
 * its own {@code /} prefixes first (see {@link #extract}).
 */
@Singleton
public class PartyChat
{
	/** What the chatbox shows in front of every party line, where a friends chat shows its channel name. */
	static final String CHANNEL = "OSParty";

	// ChatboxInput.getChatType(): 0 public, 1 cheat, 2 friends chat, 3 clan, 4 guest clan.
	private static final int FRIENDS_CHAT = 2;
	private static final int GUEST_CLAN_CHAT = 4;

	private final Client client;
	private final ClientThread clientThread;
	private final OSPartyConfig config;
	private final LivePartyBackend liveParty;
	private final BlockListService blockList;
	private final EventBus eventBus;

	/** Set while a party line is re-posted for the chat commands, so it is not read as a party line again. */
	private boolean relaying;

	@Inject
	PartyChat(Client client, ClientThread clientThread, OSPartyConfig config, LivePartyBackend liveParty,
		BlockListService blockList, EventBus eventBus)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.liveParty = liveParty;
		this.blockList = blockList;
		this.eventBus = eventBus;
	}

	/** A line typed into the chatbox. Ours if it starts with the prefix, in which case the game never sees it. */
	public void onChatboxInput(ChatboxInput input)
	{
		if (relaying || !config.partyChat())
		{
			return;
		}
		String prefix = config.partyChatPrefix();
		String text = extract(input.getValue(), input.getChatType(), prefix);
		if (text == null)
		{
			return;
		}
		// Consumed whether or not it can be sent: a line meant for the party must not go out as public chat.
		input.consume();
		if (text.isEmpty())
		{
			notice("Type " + prefix.trim() + " followed by a message to talk to your party.");
		}
		else if (!liveParty.isInParty())
		{
			notice("You're not in a party.");
		}
		else
		{
			offerToChatCommands(text, input.getChatType());
		}
	}

	/**
	 * Give RuneLite's chat commands the turn at a party line that they get at a public one. A command like
	 * {@code !kc} first uploads the sender's count to RuneLite's service and only then lets the line go out
	 * — it consumes the input and resumes it once the upload is done — and without that turn every
	 * receiver's lookup comes back empty and the line stays as typed. So the text is re-posted as a chatbox
	 * input of its own, with the party send as its resumption: a command that claims it sends it when ready,
	 * and if none does it is sent at once.
	 */
	private void offerToChatCommands(String text, int chatType)
	{
		ChatboxInput offered = new ChatboxInput(text, chatType, () -> clientThread.invoke(() -> send(text)));
		relaying = true;
		try
		{
			eventBus.post(offered);
		}
		finally
		{
			relaying = false;
		}
		if (!offered.isConsumed())
		{
			send(text);
		}
	}

	private void send(String text)
	{
		if (!liveParty.sendChat(text))
		{
			notice(liveParty.isLocalAdmitted()
				? "Party chat is reconnecting; that line wasn't sent."
				: "The host hasn't admitted you yet.");
		}
	}

	/** A line of party chat: a peer's, or our own posted back by the backend once it went out. */
	public void onPartyChatEvent(PartyChatEvent event)
	{
		if (!config.partyChat())
		{
			return;
		}
		if (!liveParty.isForLocalMember(event.getMemberId())
			&& blockList.isBlocked(liveParty.accountHashForMember(event.getMemberId()), event.getName()))
		{
			return;
		}
		clientThread.invoke(() -> show(event.getName(), event.getAccountType(), event.getText()));
	}

	private void show(String name, AccountType accountType, String text)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		// The ironman icon in front of the name, as the game itself prints a friends-chat line.
		String who = AccountTypes.chatIcon(accountType)
			+ (name == null || name.isEmpty() ? "?" : Text.removeTags(name));
		String what = escape(text);
		PartyChatChannel channel = config.partyChatChannel();
		if (channel == PartyChatChannel.GAME)
		{
			// Only friends-chat and clan lines get a channel name from the game; a game message is all ours.
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[" + CHANNEL + "] " + who + ": " + what, null);
		}
		else
		{
			client.addChatMessage(channel.messageType(), who, what, CHANNEL);
		}
	}

	private void notice(String message)
	{
		clientThread.invoke(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					ColorUtil.wrapWithColorTag("[" + CHANNEL + "]", Color.ORANGE) + " " + message, null);
			}
		});
	}

	/**
	 * The party message inside a chatbox line, or null when the line is not ours. Empty when the prefix was
	 * typed on its own.
	 *
	 * <p>Matched on what RuneLite hands us, which the game has already routed: {@code /p hi} typed into
	 * public chat arrives as {@code p hi} headed for clan (or friends) chat, exactly as {@code p hi} typed
	 * with the chatbox in clan mode does. So a prefix that starts with {@code /} is also matched without its
	 * slash on a line the game has pointed at a clan or friends chat, and only there — a public line that
	 * merely starts with the same letter is left alone.
	 */
	static String extract(String value, int chatType, String prefix)
	{
		if (value == null || prefix == null)
		{
			return null;
		}
		String line = value.trim();
		String p = prefix.trim();
		if (p.isEmpty())
		{
			return null;
		}
		String rest = after(line, p);
		if (rest == null && p.length() > 1 && p.charAt(0) == '/'
			&& chatType >= FRIENDS_CHAT && chatType <= GUEST_CLAN_CHAT)
		{
			rest = after(line, p.substring(1));
		}
		return rest;
	}

	/** What follows {@code prefix} in {@code line} when the line starts with it as a word of its own, else null. */
	private static String after(String line, String prefix)
	{
		if (!line.regionMatches(true, 0, prefix, 0, prefix.length()))
		{
			return null;
		}
		if (line.length() == prefix.length())
		{
			return "";
		}
		if (line.charAt(prefix.length()) != ' ')
		{
			return null;
		}
		return line.substring(prefix.length()).trim();
	}

	/**
	 * A peer's text as the chatbox may render it: anything that would read as a tag is escaped, since a line
	 * that could carry {@code <col>} or {@code <img>} is a line that can pass itself off as the game.
	 */
	static String escape(String text)
	{
		if (text == null)
		{
			return "";
		}
		StringBuilder kept = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if (!Character.isISOControl(c))
			{
				kept.append(c);
			}
		}
		return Text.escapeJagex(kept.toString());
	}
}
