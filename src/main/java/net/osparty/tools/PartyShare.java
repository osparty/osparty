package net.osparty.tools;

import java.awt.Color;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.osparty.OSPartyConfig;
import net.osparty.api.BoardApiClient;
import net.osparty.model.Activity;
import net.osparty.model.Advertisement;
import net.osparty.party.PlayerNames;
import net.osparty.service.BlockListService;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MessageNode;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/**
 * A party "link" that fits in the game's chat: a host types {@code !osparty} and the message goes out
 * as ordinary chat. For everyone running OSParty the line is looked up by the sender's name — the game
 * supplies it, so it can't be forged — and redrawn as the party it points at, with an "Apply to party"
 * entry on the line's menu; for everyone else it stays the literal text, the way {@code !kc} does.
 * Applying from the line runs the same checks and role picker as the Search tab, and admission still
 * ends with the host.
 *
 * <p>The trigger is a fixed word, not a setting: sender and viewer only find each other because both
 * sides agree on it.
 */
@Singleton
public class PartyShare
{
	/** The chat word that marks a message as a party share. Protocol, not preference — never configurable. */
	public static final String TRIGGER = "!osparty";

	/** How every redrawn line starts; also what the menu hook recognizes a decorated line by. */
	private static final String MARKER = ColorUtil.wrapWithColorTag("[OSParty]", Color.ORANGE);
	private static final Color LINK = new Color(0x5E, 0xB2, 0xFF);

	/** Per host: how often an overheard {@code !osparty} may hit the backend, however often it is said. */
	private static final long LOOKUP_INTERVAL_MS = 10_000;

	private final Client client;
	private final ClientThread clientThread;
	private final OSPartyConfig config;
	private final BoardApiClient board;
	private final BlockListService blockList;

	/** Hosts whose lookup found a party, so their decorated lines can offer Apply without refetching. */
	private final Map<String, Advertisement> found = new ConcurrentHashMap<>();
	private final Map<String, Long> lookedUpAt = new ConcurrentHashMap<>();

	/** Runs the apply flow for an ad clicked in chat; the panel and role chooser live on the plugin. */
	private volatile Consumer<Advertisement> onApply;
	private volatile Supplier<String> selfName;

	@Inject
	PartyShare(Client client, ClientThread clientThread, OSPartyConfig config, BoardApiClient board,
		BlockListService blockList)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.board = board;
		this.blockList = blockList;
	}

	public void setOnApply(Consumer<Advertisement> handler)
	{
		this.onApply = handler;
	}

	public void setSelfName(Supplier<String> supplier)
	{
		this.selfName = supplier;
	}

	/** Drop everything learned from chat; the plugin is going down or has restarted. */
	public void reset()
	{
		found.clear();
		lookedUpAt.clear();
		onApply = null;
	}

	/** An overheard chat line; a {@code !osparty} from a hosting player is redrawn as their party. */
	public void onChatMessage(ChatMessage event)
	{
		if (!config.partyShareLinks() || !isPlayerChat(event.getType()))
		{
			return;
		}
		String text = Text.removeTags(event.getMessage()).trim();
		if (!isShare(text))
		{
			return;
		}
		String name = Text.removeTags(event.getName());
		String key = PlayerNames.normalize(name);
		if (key.isEmpty() || blockList.isBlocked(name))
		{
			return;
		}
		String tail = tailOf(text);
		MessageNode node = event.getMessageNode();
		Advertisement known = found.get(key);
		if (known != null)
		{
			decorate(node, known, tail);
		}
		long now = System.currentTimeMillis();
		Long last = lookedUpAt.get(key);
		if (last != null && now - last < LOOKUP_INTERVAL_MS)
		{
			return;
		}
		lookedUpAt.put(key, now);
		board.fetchAdByHost(name,
			ad ->
			{
				found.put(key, ad);
				clientThread.invoke(() -> decorate(node, ad, tail));
			},
			error ->
			{
				// Not hosting (any more). The line stays as typed — except our own, which earns a hint.
				found.remove(key);
				if (key.equals(PlayerNames.normalize(self())))
				{
					notice("You're not hosting a party, so that " + TRIGGER + " isn't clickable. Host one first.");
				}
			});
	}

	/**
	 * The chat line's right-click menu: a decorated line gets "Apply to party" on top, so left-clicking
	 * it applies — the closest the chatbox comes to a link. Anchored the way the Chat History plugin
	 * anchors its copy entry: on the vanilla Report option, which only chat lines carry.
	 */
	public void onMenuEntryAdded(MenuEntryAdded entry)
	{
		if (!config.partyShareLinks() || !"Report".equals(entry.getOption())
			|| WidgetUtil.componentToInterface(entry.getActionParam1()) != InterfaceID.CHATBOX)
		{
			return;
		}
		int childId = WidgetUtil.componentToId(entry.getActionParam1());
		Widget widget = client.getWidget(InterfaceID.CHATBOX, childId);
		Widget parent = widget == null ? null : widget.getParent();
		if (parent == null || InterfaceID.Chatbox.SCROLLAREA != parent.getId())
		{
			return;
		}
		// The right-clicked static widget holds the sender; the text lives in the dynamic children,
		// four per line (sender, message, clan name, clan rank icon).
		int first = WidgetUtil.componentToId(InterfaceID.Chatbox.LINE0);
		Widget contents = parent.getChild((childId - first) * 4 + 1);
		if (contents == null || contents.getText() == null || !contents.getText().contains(MARKER))
		{
			return;
		}
		String name = Text.removeTags(entry.getTarget());
		if (found.get(PlayerNames.normalize(name)) == null)
		{
			return;
		}
		client.createMenuEntry(-1)
			.setOption("Apply to party")
			.setTarget(entry.getTarget())
			.setType(MenuAction.RUNELITE)
			.onClick(e -> applyFromChat(name));
	}

	/** Apply on a fresh copy of the ad, not the one the line was drawn from — parties fill and fold. */
	private void applyFromChat(String name)
	{
		board.fetchAdByHost(name,
			ad ->
			{
				found.put(PlayerNames.normalize(name), ad);
				Consumer<Advertisement> handler = onApply;
				if (handler != null)
				{
					handler.accept(ad);
				}
			},
			error -> notice(name + " isn't hosting a party any more."));
	}

	/** Redraw the line as the party it points at. Client thread only. */
	private void decorate(MessageNode node, Advertisement ad, String tail)
	{
		if (node == null || ad == null)
		{
			return;
		}
		StringBuilder line = new StringBuilder(MARKER).append(" Hosting ").append(activityLabel(ad));
		if (ad.getCapacity() > 0)
		{
			line.append(" (").append(ad.getSize()).append('/').append(ad.getCapacity()).append(')');
		}
		if (!tail.isEmpty())
		{
			line.append(" - ").append(Text.escapeJagex(tail));
		}
		line.append(" - ").append(ColorUtil.wrapWithColorTag("click this line to apply", LINK));
		node.setRuneLiteFormatMessage(line.toString());
		client.refreshChat();
	}

	private static String activityLabel(Advertisement ad)
	{
		Activity activity = Activity.fromId(ad.getActivity());
		return activity == null ? "a party" : activity.displayName(ad.isHardMode(), ad.getInvocation());
	}

	/** The chat types another player's {@code !osparty} can arrive on. */
	private static boolean isPlayerChat(ChatMessageType type)
	{
		switch (type)
		{
			case PUBLICCHAT:
			case MODCHAT:
			case FRIENDSCHAT:
			case CLAN_CHAT:
			case CLAN_GUEST_CHAT:
				return true;
			default:
				return false;
		}
	}

	/** Whether a line is a share: the trigger on its own, or the trigger and whatever the host added. */
	static boolean isShare(String text)
	{
		if (text == null)
		{
			return false;
		}
		String lower = text.toLowerCase();
		return lower.equals(TRIGGER) || lower.startsWith(TRIGGER + " ");
	}

	/** The host's own words after the trigger, kept on the redrawn line. */
	static String tailOf(String text)
	{
		return text.length() <= TRIGGER.length() ? "" : text.substring(TRIGGER.length()).trim();
	}

	private String self()
	{
		Supplier<String> supplier = selfName;
		return supplier == null ? null : supplier.get();
	}

	private void notice(String message)
	{
		clientThread.invoke(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					MARKER + " " + message, null);
			}
		});
	}
}
