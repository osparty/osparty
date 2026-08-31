package net.osparty;

import net.osparty.api.BoardApiClient;
import net.osparty.api.BoardService;
import net.osparty.api.OSPartySocket;
import net.osparty.service.*;
import net.osparty.store.PartyStore;
import net.osparty.tools.*;
import net.osparty.model.AccountTypes;
import net.osparty.model.Activity;
import net.osparty.model.Applicant;
import net.osparty.model.Advertisement;
import net.osparty.model.Member;
import net.osparty.model.Role;
import net.osparty.party.JoinPromptEvent;
import net.osparty.party.PlayerNames;
import net.osparty.party.HostTransferEvent;
import net.osparty.party.LiveParty;
import net.osparty.party.LocalPlayerSnapshot;
import net.osparty.party.LivePartyBackend;
import net.osparty.party.PartyChatEvent;
import net.osparty.party.SpecDrainEvent;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import net.osparty.ui.AccountRecoveryController;
import net.osparty.ui.OSPartyPanel;
import net.osparty.ui.JoinPromptOverlay;
import net.osparty.ui.DefenceInfoBox;
import net.osparty.ui.ChatboxCards;
import net.osparty.ui.InvitePrompt;
import net.osparty.ui.MatchOffer;
import net.osparty.ui.MatchPrompt;
import net.osparty.ui.SimilarParties;
import net.osparty.ui.SimilarPrompt;
import net.osparty.ui.PartyPrompt;
import net.osparty.ui.RaidPartyPrompt;
import net.osparty.ui.RoleChooser;
import net.osparty.ui.NpcDefenceOverlay;
import net.osparty.ui.PartyNameOverlay;
import net.osparty.ui.PlayerMarkerOverlay;
import net.osparty.ui.ReadyCheckOverlay;
import net.osparty.ui.PingArrowOverlay;
import net.osparty.ui.TilePingOverlay;
import net.osparty.ui.VengeanceOverlay;
import com.google.inject.Provides;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.Gson;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Friend;
import net.runelite.api.FriendsChatManager;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NameableContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.FakeXpDrop;
import net.runelite.api.events.FocusChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.vars.AccountType;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ChatboxInput;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.util.*;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.game.WorldService;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldRegion;
import net.runelite.http.api.worlds.WorldResult;
import net.runelite.client.party.PartyService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.osparty.api.PartyInvite;
import net.osparty.enums.EventSound;
import net.osparty.enums.InviteDisplay;
import net.osparty.enums.RaidPartyAutoCreate;

@Slf4j
@PluginDescriptor(
	name = "OSParty",
	description = "Search, queue and join parties for activities around the game",
	tags = {"party", "group", "raid", "minigame", "boss", "lfg"}
)
public class OSPartyPlugin extends Plugin implements HostApplicationHandler
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private BoardApiClient apiClient;

	@Inject
	private ItemManager itemManager;

	@Inject
	private SkillIconManager skillIconManager;

	@Inject
	private LivePartyBackend liveParty;

	@Inject
	private RuneWatchService runeWatchService;

	@Inject
	private KillcountService killcountService;

	@Inject
	private WorldService worldService;

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	@Inject
	private ChatboxCards cards;

	@Inject
	private Gson gson;

	@Inject
	private PartySounds partySounds;

	@Inject
	private KeyManager keyManager;

	@Inject
	private DefenceTracker defenceTracker;

	@Inject
	private SpecialAttackTracker specTracker;

	@Inject
	private PartyChat partyChat;

	@Inject
	private PartyShare partyShare;

	@Inject
	private CoxRaidScanner coxRaidScanner;

	@Inject
	private RaidPartyWatcher raidPartyWatcher;

	@Inject
	private RaidBoardSync raidBoardSync;

	@Inject
	private InfoBoxManager infoBoxManager;

	// Reaches Player Indicators so our overhead party names don't print through the names it
	// already draws.
	@Inject
	private PluginManager pluginManager;

	@Inject
	private PartyService partyService;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OSPartyConfig config;

	@Inject
	private OSPartySocket socket;

	@Inject
	private FavoritesService favoritesService;

	@Inject
	private BlockListService blockListService;

	@Inject
	private Notifier notifier;

	@Inject
	private PartyStore partyStore;

	@Inject
	private PartyHistoryService partyHistoryService;

	@Inject
	private SpriteManager spriteManager;

	private OSPartyPanel panel;
	/** Owns every conversation about signing this device in, and the recovery routes when it cannot be. */
	private AccountRecoveryController accountRecovery;
	private BufferedImage navIcon;
	private NavigationButton navButton;
	private NavigationButton navButtonAlert;
	private Timer navBlinkTimer;
	private boolean navAlertShown;
	private volatile boolean panelActive;
	private final Map<String, PartyInvite> activeInvites = new ConcurrentHashMap<>();
	private static final long INVITE_COOLDOWN_MS = 30_000;
	private final Map<String, Long> lastInviteAt = new ConcurrentHashMap<>();
	/** The in-game invite or match card while it is up, so accepting turns its page instead of reopening. */
	private PartyPrompt partyCard;
	/** The party currently being offered by the matchmaker, on either surface; null when none is. */
	private volatile String openMatchId;
	/** The in-game raid party being offered for advertising, on either surface; null when none is. */
	private final AtomicReference<RaidPartyDetected> openRaidOffer = new AtomicReference<>();
	/** Set while an in-game role question is outstanding; see {@link #answerRole(String)}. */
	private java.util.function.Consumer<String> pendingRolePick;
	private long identifiedHash;
	private String identifiedName;
	private JoinPromptOverlay joinPromptOverlay;
	private ReadyCheckOverlay readyCheckOverlay;
	private TilePingOverlay tilePingOverlay;
	private PingArrowOverlay pingArrowOverlay;
	private NpcDefenceOverlay defenceOverlay;
	private PlayerMarkerOverlay playerMarkerOverlay;

	private PartyNameOverlay partyNameOverlay;
	private VengeanceOverlay vengeanceOverlay;
	private DefenceInfoBox defenceBox;
	private volatile boolean pingHotkeyDown;
	private volatile String playerName;
	private volatile String friendsChatOwner;
	private volatile int world;
	private volatile int[] mapRegions;
	private volatile String coxLayout;
	private volatile AccountType accountType;
	private volatile long accountHash = -1L;
	private boolean rejoinChecked;
	private WorldPinger worldPinger;

	private volatile Set<String> friendNames = Collections.emptySet();
	private int friendsSignature;

	/** Filled from the EDT (the panel's refresh), drained on the client thread. */

	/**
	 * Whether a printable key would land in a text field rather than reach us. There is no such thing
	 * as "typing" in default chat mode - the chat line always takes keys - so the closest we can get is
	 * "they have already started composing something", plus the interfaces that genuinely capture input.
	 */
	private boolean textEntryActive()
	{
		if (chatboxPanelManager.getCurrentInput() != null || client.getFocusedInputFieldWidget() != null)
		{
			return true;
		}
		String chatInput = client.getVarcStrValue(VarClientID.CHATINPUT);
		return chatInput != null && !chatInput.isEmpty();
	}

	private final HotkeyListener pingHotkeyListener = new HotkeyListener(() -> config.pingHotkey())
	{
		@Override
		public void keyPressed(KeyEvent e)
		{
			// Skipping the press leaves isConsumingTyped false, so the character still reaches the game.
			if (textEntryActive())
			{
				return;
			}
			super.keyPressed(e);
		}

		@Override
		public void hotkeyPressed()
		{
			pingHotkeyDown = true;
		}

		@Override
		public void hotkeyReleased()
		{
			pingHotkeyDown = false;
		}
	};

	@Override
	protected void startUp()
	{
		BoardService boardService = apiClient;

		// Registered before the socket opens: the first connection can enrol this character, and the codes
		// that come back with a brand-new account arrive exactly once.
		accountRecovery = new AccountRecoveryController(socket, this::getAccountHash, this::getPlayerName);
		accountRecovery.register();
		socket.start();

		joinPromptOverlay = new JoinPromptOverlay();
		overlayManager.add(joinPromptOverlay);

		readyCheckOverlay = new ReadyCheckOverlay(liveParty);
		overlayManager.add(readyCheckOverlay);

		tilePingOverlay = new TilePingOverlay(client, liveParty, config);
		overlayManager.add(tilePingOverlay);

		pingArrowOverlay = new PingArrowOverlay(client, liveParty, config);
		overlayManager.add(pingArrowOverlay);

		defenceOverlay = new NpcDefenceOverlay(client, defenceTracker, config,
			ImageUtil.resizeImage(skillIconManager.getSkillImage(Skill.DEFENCE), 16, 16),
			ImageUtil.resizeImage(skillIconManager.getSkillImage(Skill.MAGIC), 16, 16));
		overlayManager.add(defenceOverlay);

		playerMarkerOverlay = new PlayerMarkerOverlay(client, liveParty, config,
			ImageUtil.resizeImage(ImageUtil.loadImageResource(getClass(), "/net/osparty/icons/learner.png"), 12, 12),
			ImageUtil.resizeImage(ImageUtil.loadImageResource(getClass(), "/net/osparty/icons/teacher.png"), 12, 12));
		overlayManager.add(playerMarkerOverlay);

		partyNameOverlay = new PartyNameOverlay(client, liveParty, config, pluginManager, configManager,
			partyService);
		overlayManager.add(partyNameOverlay);

		vengeanceOverlay = new VengeanceOverlay(client, liveParty, config, spriteManager);
		overlayManager.add(vengeanceOverlay);

		keyManager.registerKeyListener(pingHotkeyListener);

		// Ready-check notifications: chat pings and an optional all-ready sound.
		liveParty.setOnReadyCheckStarted(starter -> {
			chat(starter + " started a ready check - ready up in the OSParty panel.", true);
			desktopNotify(starter + " started a ready check.");
			partySounds.play(EventSound.READY_CHECK_STARTED);
		});
		liveParty.setOnAllReady(() -> {
			Activity activity = Activity.fromId(liveParty.currentActivityId());
			String name = activity != null ? activity.getDisplayName() : "the activity";
			chat("Everyone is ready for " + name + "!", true);
			desktopNotify("Everyone is ready for " + name + "!");
			partySounds.play(EventSound.ALL_READY);
		});
		liveParty.setOnReadyExpired(() -> chat("Ready check expired.", true));
		liveParty.setOnKicked(() -> partySounds.play(EventSound.KICKED));
		// A ping we can't see isn't worth a sound, so skip anything off-plane or outside the scene.
		liveParty.setOnPingReceived(point -> {
			if (!config.pings() || point == null)
			{
				return;
			}
			clientThread.invoke(() -> {
				if (point.getPlane() == client.getPlane()
					&& WorldPoint.isInScene(client, point.getX(), point.getY()))
				{
					partySounds.play(EventSound.PING);
				}
			});
		});

		// Stand up the live party layer; the advertisement only makes the room findable.
		liveParty.register();

		// Pull the scammer watchlist now; it refreshes periodically (see schedule).
		runeWatchService.refresh();

		worldPinger = new WorldPinger();

		// A player can't block themselves.
		blockListService.setSelf(this::getPlayerId, this::getSelfName);

		panel = new OSPartyPanel(boardService, config, this::getPlayerName, this,
			this::getFriendsChatOwner, this::getCurrentWorld, itemManager, liveParty, runeWatchService,
			this::getAccountType, killcountService, skillIconManager, this::getMapRegions,
			this::regionForWorld, this::getCoxLayout, configManager, gson,
			worldPinger, this::worldAddressForNum, this::getFriendNames, favoritesService, blockListService,
			this::getAccountHash, spriteManager, partyHistoryService, message -> chat(message, true),
			() -> net.osparty.ui.DeviceManagerDialog.open(panel, socket, accountRecovery),
			accountRecovery::openRecovery);

		navIcon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");
		buildNavButtons();

		// The panel is where the "not signed in" banner lives and where the dialogs parent themselves, so
		// the controller only learns about it once it exists.
		accountRecovery.attachPanel(panel, panel::setSignedIn);
		accountRecovery.setOnLinkDiscord(panel::startDiscordLink);

		clientToolbar.addNavigation(navButton);
		panel.setOnActivated(this::onPanelActivated);
		panel.setOnDeactivated(this::onPanelDeactivated);
		panel.setInviteHandlers(invite -> resolveInvite(invite, true, false),
			invite -> resolveInvite(invite, false, false));
		panel.setMatchHandler(this::onMatchFound);
		// The Create tab always shows its own inline prompt; this adds the in-game card beside it.
		panel.setSimilarHandler(this::onSimilarParties, inGameRoleChooser,
			() -> clientThread.invoke(() -> cards.dismiss(SIMILAR_CARD_KEY)));
		// Turning the toggle off has to take any offer already on screen with it.
		panel.setOnLookingChanged(() -> clientThread.invoke(() -> cards.dismiss(openMatchId)));
		apiClient.setInviteListener(this::onPartyInvite);
		partyShare.setSelfName(this::getPlayerName);
		// A chat-line apply is an invite-style join: checked and role-picked in-game, reported to the chatbox.
		partyShare.setOnApply(ad -> SwingUtilities.invokeLater(() ->
		{
			OSPartyPanel currentPanel = panel;
			if (currentPanel != null)
			{
				currentPanel.applyTo(ad, message -> chat(message, false), inGameRoleChooser);
			}
		}));
		raidPartyWatcher.setListener(this::onRaidPartyDetected);
		// A hosted raid ad follows the in-game board (CoX scale, ToA invocation level) while the setting is on.
		raidBoardSync.setHostedAd(() ->
		{
			OSPartyPanel currentPanel = panel;
			return currentPanel != null && config.raidBoardSync() ? currentPanel.hostedAd() : null;
		});
		raidBoardSync.setListener((activity, coxScale, invocation) -> SwingUtilities.invokeLater(() ->
		{
			if (panel != null)
			{
				panel.applyBoardToHostedAd(activity, coxScale, invocation);
			}
		}));
		log.info("OSParty started (API {})", BoardApiClient.apiBaseUrl());
	}

	@Override
	protected void shutDown()
	{
		liveParty.leave();
		liveParty.unregister();
		partyChat.reset();
		partyShare.reset();
		// These live on singletons that outlast the plugin, so a restart would otherwise stack callbacks
		// onto a dead instance. Every setter tolerates null.
		apiClient.setInviteListener(null);
		raidPartyWatcher.setListener(null);
		raidPartyWatcher.reset();
		raidBoardSync.setListener(null);
		raidBoardSync.setHostedAd(null);
		raidBoardSync.reset();
		liveParty.setOnReadyCheckStarted(null);
		liveParty.setOnAllReady(null);
		liveParty.setOnReadyExpired(null);
		liveParty.setOnKicked(null);
		liveParty.setOnPingReceived(null);
		if (accountRecovery != null)
		{
			accountRecovery.unregister();
			accountRecovery = null;
		}
		keyManager.unregisterKeyListener(pingHotkeyListener);
		pingHotkeyDown = false;
		if (panel != null)
		{
			panel.dispose();
		}
		socket.stop();
		if (navBlinkTimer != null)
		{
			navBlinkTimer.stop();
			navBlinkTimer = null;
		}
		clientToolbar.removeNavigation(navButton);
		if (navButtonAlert != null)
		{
			clientToolbar.removeNavigation(navButtonAlert);
		}
		overlayManager.remove(joinPromptOverlay);
		overlayManager.remove(readyCheckOverlay);
		overlayManager.remove(tilePingOverlay);
		overlayManager.remove(pingArrowOverlay);
		overlayManager.remove(defenceOverlay);
		overlayManager.remove(playerMarkerOverlay);
		overlayManager.remove(partyNameOverlay);
		overlayManager.remove(vengeanceOverlay);
		if (defenceBox != null)
		{
			infoBoxManager.removeInfoBox(defenceBox);
			defenceBox = null;
		}
		defenceTracker.reset();
		specTracker.reset();
		if (worldPinger != null)
		{
			worldPinger.shutdown();
			worldPinger = null;
		}
		joinPromptOverlay = null;
		readyCheckOverlay = null;
		tilePingOverlay = null;
		pingArrowOverlay = null;
		defenceOverlay = null;
		playerMarkerOverlay = null;
		partyNameOverlay = null;
		vengeanceOverlay = null;
		panel = null;
		navButton = null;
		navButtonAlert = null;
		navIcon = null;
		// startUp always registers the normal button, so the alert flag must not survive a restart.
		navAlertShown = false;
		panelActive = false;
		playerName = null;
		accountHash = -1L;
		// A prompt left open would keep both drain loops short-circuited for the rest of the session,
		// and rejoinChecked would eat the once-per-login rejoin offer.
		partyCard = null;
		openMatchId = null;
		openRaidOffer.set(null);
		pendingRolePick = null;
		rejoinChecked = false;
		cards.clear();
		activeInvites.clear();
		lastInviteAt.clear();
		friendNames = Collections.emptySet();
		friendsSignature = 0;
		partyStore.close();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!OSPartyConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		String key = event.getKey();
		if (OSPartyConfig.SHOW_DISCORD_BADGES.equals(key) && panel != null)
		{
			SwingUtilities.invokeLater(panel::refreshDiscordBadgeViews);
		}
		// Re-broadcast our snapshot right away so hiding/unhiding inventory or gear takes effect
		// for the party without waiting for the periodic re-announce.
		if (OSPartyConfig.HIDE_INVENTORY.equals(key) || OSPartyConfig.HIDE_GEAR.equals(key))
		{
			liveParty.markLocalDirty();
		}
		if (OSPartyConfig.SIDE_PANEL_PRIORITY.equals(key))
		{
			SwingUtilities.invokeLater(this::rebuildNavButtons);
		}
		// The watchlist is only fetched on startup and every 15 minutes, so without this the setting
		// looks broken for a quarter of an hour after it's switched on.
		if (OSPartyConfig.RUNE_WATCH.equals(key) && config.runeWatch())
		{
			runeWatchService.refresh();
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		// CoX stairs reload flicks IN_RAID 1->0->1 within one tick; only the event stream catches it.
		if (event.getVarbitId() == VarbitID.RAIDS_CLIENT_INDUNGEON)
		{
			coxRaidScanner.onInRaidChanged(event.getValue());
		}
		// A re-rolled raid is a new party id. The scanner's own lobby check can miss the re-roll; this can't.
		if (event.getVarpId() == VarPlayerID.RAIDS_PARTY_GROUPHOLDER)
		{
			coxRaidScanner.onRaidPartyChanged(event.getValue());
		}
		// The rune pouch has no item container of its own, so runes moving in or out of it only ever show up
		// here. Without this the pouch keeps whatever it held when the inventory last changed.
		if (LocalPlayerSnapshot.isRunePouchVarbit(event.getVarbitId()))
		{
			liveParty.markItemsDirty();
		}
		specTracker.onVarbitChanged(event);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// On a real logout (not a hop), tell the party we're offline so our dot clears now.
		if (event.getGameState() == GameState.LOGIN_SCREEN && playerName != null
			&& liveParty.isInParty())
		{
			liveParty.broadcastOffline(playerName);
		}

		if (event.getGameState() == GameState.LOGIN_SCREEN
			|| event.getGameState() == GameState.HOPPING)
		{
			playerName = null;
			friendsChatOwner = null;
			world = 0;
			mapRegions = null;
			accountType = null;
			accountHash = -1L;
			cards.clear(ChatboxCards.Priority.JOIN_REQUEST);
			// No game ticks arrive on the login screen, so the watcher can't see the logout itself; without
			// this the next login's varbit replay would read as the player making a party.
			raidPartyWatcher.reset();
		}
		// Re-arm the rejoin check on a real logout (not a world hop).
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			rejoinChecked = false;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		Player local = client.getLocalPlayer();
		if (local != null && local.getName() != null)
		{
			playerName = local.getName();
		}

		world = client.getWorld();
		mapRegions = client.getMapRegions();
		// Mapped from the varbit ourselves: the deprecated getAccountType() has no unranked-group-ironman
		// value and reports one as a normal account, locking them out of ironman-only parties.
		accountType = AccountTypes.fromVarbit(client.getVarbitValue(VarbitID.IRONMAN));
		accountHash = client.getAccountHash();
		// Tell the socket which character it should present a credential for. Read on connect, so a switch
		// takes effect on the next reconnect rather than mid-connection -- which is right: the credential
		// settles identity for a whole connection, and one connection is one character.
		socket.setAccountHash(accountHash);

		// Register our identity so friends can route party invites to us (only re-sent on change).
		maybeIdentify();

		// Once per login: offer to resume a party we were hosting before a restart.
		if (playerName != null && !rejoinChecked)
		{
			rejoinChecked = true;
			attemptRejoin(playerName);
		}

		FriendsChatManager fcm = client.getFriendsChatManager();
		friendsChatOwner = fcm != null ? fcm.getOwner() : null;

		// Capture the full friends list for friends-first sorting in the Search panel. Walking the raw
		// names is cheap; normalising them into a fresh set every tick is not, so only do it on a change.
		NameableContainer<Friend> friendContainer = client.getFriendContainer();
		if (friendContainer != null)
		{
			int signature = friendsSignature(friendContainer);
			if (signature != friendsSignature)
			{
				friendsSignature = signature;
				friendNames = Collections.unmodifiableSet(normalizedFriendNames(friendContainer));
			}
		}

		// Accumulate the CoX layout each tick; a single scan can't see the whole raid.
		coxRaidScanner.update();
		coxLayout = coxRaidScanner.layout();

		// Notice a raid party the player just made at the board, so it can be advertised; and keep an ad we
		// host in step with what the board says about it.
		raidPartyWatcher.update();
		raidBoardSync.update();

		// Show the next queued in-game card if the chatbox is free.
		cards.tick();

		// Push pending host state / our own live snapshot (client thread).
		liveParty.tick();
		partyChat.onGameTick();

		// Resolve this tick's local special attack, then apply all queued drains
		// (local and party members') and clear dead targets.
		specTracker.onGameTick();
		defenceTracker.onGameTick();
		updateDefenceInfoBox();
	}

	/** Cheap fingerprint of the friends list, so the normalised set is only rebuilt when it really changed. */
	private static int friendsSignature(NameableContainer<Friend> container)
	{
		Friend[] members = container.getMembers();
		int signature = container.getCount();
		for (Friend friend : members)
		{
			signature = signature * 31 + (friend == null || friend.getName() == null
				? 0 : friend.getName().hashCode());
		}
		return signature;
	}

	private static Set<String> normalizedFriendNames(NameableContainer<Friend> container)
	{
		Set<String> names = new HashSet<>(container.getCount() * 2);
		for (Friend friend : container.getMembers())
		{
			if (friend != null && friend.getName() != null)
			{
				names.add(PlayerFlagService.normalize(friend.getName()));
			}
		}
		return names;
	}

	private void updateDefenceInfoBox()
	{
		boolean show = config.defenceInfoBox() && defenceTracker.state() != null;
		if (show && defenceBox == null)
		{
			defenceBox = new DefenceInfoBox(skillIconManager.getSkillImage(Skill.DEFENCE), this,
				defenceTracker, config);
			infoBoxManager.addInfoBox(defenceBox);
		}
		else if (!show && defenceBox != null)
		{
			infoBoxManager.removeInfoBox(defenceBox);
			defenceBox = null;
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		specTracker.onHitsplatApplied(event);
	}

	@Subscribe
	public void onFakeXpDrop(FakeXpDrop event)
	{
		specTracker.onFakeXpDrop(event);
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		specTracker.onNpcDespawned(event);
	}

	@Subscribe
	public void onSpecDrainEvent(SpecDrainEvent event)
	{
		specTracker.onSpecDrain(event);
	}

	@Subscribe
	public void onChatboxInput(ChatboxInput event)
	{
		partyChat.onChatboxInput(event);
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		partyShare.onChatMessage(event);
	}

	@Subscribe
	public void onPartyChatEvent(PartyChatEvent event)
	{
		partyChat.onPartyChatEvent(event);
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		// Only the two containers a snapshot actually carries. This fires for the bank, the GE and every
		// other container too, so without the filter a banking trip re-sent the whole snapshot continuously.
		int id = event.getContainerId();
		if (id == InventoryID.INV || id == InventoryID.WORN)
		{
			liveParty.markItemsDirty();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		// The backend decides whether this is a real level-up or just a boost; boosts arrive constantly.
		liveParty.markStatsDirty(event.getSkill(), event.getLevel());
		specTracker.onStatChanged(event);
	}

	@Subscribe
	public void onHostTransferEvent(HostTransferEvent event)
	{
		panel.onHostTransferEvent(event);
	}

	@Subscribe
	public void onFocusChanged(FocusChanged focusChanged)
	{
		if (!focusChanged.isFocused())
		{
			pingHotkeyDown = false;
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		Activity raidBoard = raidBoardClick(event);
		if (raidBoard != null)
		{
			raidPartyWatcher.onBoardClicked(raidBoard, event.getMenuOption(), raidBoardComponent(event));
		}
		if (!pingHotkeyDown || client.isMenuOpen() || !liveParty.isInParty() || !config.pings())
		{
			return;
		}

		Tile selectedSceneTile = client.getSelectedSceneTile();
		if (selectedSceneTile == null)
		{
			return;
		}

		// Only bare ground pings, so holding the hotkey doesn't swallow clicks on NPCs, items or widgets.
		boolean isOnCanvas = false;
		for (MenuEntry menuEntry : client.getMenuEntries())
		{
			if (menuEntry != null && "walk here".equalsIgnoreCase(menuEntry.getOption()))
			{
				isOnCanvas = true;
			}
		}

		if (!isOnCanvas)
		{
			return;
		}

		event.consume();

		WorldPoint point = selectedSceneTile.getWorldLocation();
		if (point != null && liveParty.sendPing(point, config.pingColor()))
		{
			partySounds.play(EventSound.PING);
		}
	}

	@Subscribe
	public void onJoinPromptEvent(JoinPromptEvent event)
	{
		// Only show the popup if this request is aimed at us, and we accept them.
		if (!config.receiveFriendsChatRequests())
		{
			return;
		}
		if (!liveParty.isForLocalMember(event.getTargetMemberId()))
		{
			return;
		}
		String host = event.getHostName() != null ? event.getHostName() : "The host";
		String kind = event.getKind() == null ? "FC" : event.getKind();
		String title;
		String detail;
		switch (kind)
		{
			case "NOTICE_BOARD":
				title = "Party reminder";
				detail = "Apply on the Theatre of Blood notice board.";
				break;
			case "OBELISK":
				title = "Party reminder";
				detail = "Apply on the Grouping Obelisk.";
				break;
			default:
				String fc = event.getFriendsChat();
				if (fc == null)
				{
					return;
				}
				title = "Friends chat request";
				detail = "Join the friends chat: " + fc;
		}
		if (joinPromptOverlay != null)
		{
			joinPromptOverlay.show(host, title, detail, config.fcRequestDurationSecs() * 1000L);
			chat(host + " - " + detail, true);
			desktopNotify(host + ": " + detail);
			partySounds.play(EventSound.FRIENDS_CHAT_REQUEST);
		}
	}

	@Schedule(period = 15, unit = ChronoUnit.MINUTES, asynchronous = true)
	public void refreshRuneWatch()
	{
		runeWatchService.refresh();
	}

	/**
	 * Go back into the party we were in before a crash/restart, host or member; both survive for about the
	 * advertisement's TTL. The membership is asked about first because it is the one we can answer locally:
	 * a player is in one party at a time, so a party we were a member of is not one we also hosted.
	 */
	private void attemptRejoin(String rsn)
	{
		if (liveParty.isInParty())
		{
			return; // already in a party
		}
		long hash = accountHash;
		SwingUtilities.invokeLater(() ->
		{
			OSPartyPanel currentPanel = panel;
			if (currentPanel == null || currentPanel.resumeJoinedParty(hash))
			{
				return;
			}
			apiClient.fetchAdByHost(rsn,
				ad -> SwingUtilities.invokeLater(() -> onRejoinFound(ad)),
				error -> { /* no party for this host - normal, nothing to do */ });
		});
	}

	private void onRejoinFound(Advertisement ad)
	{
		if (panel == null || ad == null)
		{
			return;
		}
		panel.resumeHostedParty(ad);
		Activity activity = Activity.fromId(ad.getActivity());
		String name = activity != null ? activity.getDisplayName() : ad.getActivity();
		chat("Rejoined your " + name + " party - disband it from the OSParty panel if you're done.", true);
	}

	public String getPlayerName()
	{
		return playerName;
	}

	public String getSelfName()
	{
		return playerName != null ? playerName : client.getLauncherDisplayName();
	}

	public String getFriendsChatOwner()
	{
		return friendsChatOwner;
	}

	public int getCurrentWorld()
	{
		return world;
	}

	public int[] getMapRegions()
	{
		return mapRegions;
	}

	public String getCoxLayout()
	{
		return coxLayout;
	}

	public WorldRegion regionForWorld(int worldNum)
	{
		if (worldNum <= 0)
		{
			return null;
		}
		WorldResult worlds = worldService.getWorlds();
		if (worlds == null)
		{
			return null;
		}
		World world = worlds.findWorld(worldNum);
		return world != null ? world.getRegion() : null;
	}

	/** Resolve a world number to its server hostname (for TCP-ping latency), or null. Safe from the EDT. */
	public String worldAddressForNum(int worldNum)
	{
		if (worldNum <= 0)
		{
			return null;
		}
		WorldResult worlds = worldService.getWorlds();
		if (worlds == null)
		{
			return null;
		}
		World world = worlds.findWorld(worldNum);
		return world != null ? world.getAddress() : null;
	}

	public Set<String> getFriendNames()
	{
		return friendNames;
	}

	public AccountType getAccountType()
	{
		return accountType;
	}

	public long getAccountHash()
	{
		return accountHash;
	}

	/**
	 * The local player's public id, or null when not seated in a party. The server derives it from the
	 * account hash and hands it back on the roster; it is what other players know us by.
	 */
	public String getPlayerId()
	{
		return liveParty.localPlayerId();
	}


	@Override
	public void setPendingApplicants(List<Applicant> applicants, Activity activity)
	{
		// Badge the sidebar button while applications are waiting, not only for invites: the chat line
		// announcing an applicant scrolls away, so otherwise nothing points at the panel. Driven by the
		// live list rather than the one-shot announcement, so the dot lasts as long as the applications do.
		boolean waiting = applicants != null && !applicants.isEmpty();
		SwingUtilities.invokeLater(() ->
		{
			if (waiting)
			{
				flashInviteButton();
			}
			else
			{
				stopInviteFlash();
			}
		});
	}

	@Override
	public void announceApplicant(Applicant applicant, Activity activity)
	{
		if (applicant.isBlocked())
		{
			// Persistent in-game notification for a blocked applicant, since the chat line scrolls away.
			notifier.notify(applicant.getName() + " is on your block list, and applied to your "
				+ activity.getDisplayName() + " party.");
		}
		else
		{
			desktopNotify(applicant.getName() + " applied to your " + activity.getDisplayName() + " party.");
		}

		chat(applicant.getName() + " applied to your " + activity.getDisplayName()
			+ " party - " + applicantSummary(applicant, activity) + ". Accept or decline in the side panel.", true);

		// Also offer an in-game chatbox Accept/Decline (driven on the game tick).
		if (config.inGamePrompts() && applicant.getMemberId() != 0)
		{
			cards.offer(applicantCard(applicant, activity));
		}
	}

	@Override
	public void announceAutoDeclinedBlocked(Applicant applicant, Activity activity)
	{
		notifier.notify("Auto-declined " + applicant.getName() + ", who is on your block list ("
			+ activity.getDisplayName() + ").");
		chat("Auto-declined " + applicant.getName() + " - on your block list.", true);
	}

	@Override
	public void announceInvitedAdmitted(Applicant applicant, Activity activity)
	{
		chat(applicant.getName() + " accepted your invite and joined your "
			+ activity.getDisplayName() + " party.", true);
	}

	/** A compact one-liner about an applicant (cb, KC, PB, total, account type, RuneWatch). */
	private String applicantSummary(Applicant applicant, Activity activity)
	{
		// Blocked applicants: surface only the block status, not their stats.
		if (applicant.isBlocked())
		{
			return "on your block list";
		}

		List<String> parts = new ArrayList<>();
		parts.add("cb " + applicant.getCombatLevel());
		parts.addAll(applicantParts(applicant, activity));
		if (runeWatchService.get(applicant.getName()) != null)
		{
			parts.add("(!) RuneWatch listed");
		}
		return String.join(", ", parts);
	}

	/** What the block list and RuneWatch have to say about an applicant, or {@code null}. */
	private String applicantWarning(Applicant applicant)
	{
		if (applicant.isBlocked())
		{
			return "On your block list";
		}
		return runeWatchService.get(applicant.getName()) != null ? "RuneWatch listed" : null;
	}

	/** The applicant's stats as separate facts, so the in-game card can lay them out itself. */
	private List<String> applicantParts(Applicant applicant, Activity activity)
	{
		List<String> parts = new ArrayList<>();

		if (applicant.getKillCount() >= 0)
		{
			StringBuilder kc = new StringBuilder(activity.getDisplayName() + " KC " + applicant.getKillCount());
			if (activity.hasHardMode() && applicant.getHardModeKillCount() >= 0)
			{
				kc.append(" (").append(activity.getHardModeLabel()).append(' ')
					.append(applicant.getHardModeKillCount()).append(')');
			}
			parts.add(kc.toString());
		}

		if (PersonalBests.isPbActivity(activity.getId()) && applicant.getPbSeconds() >= 0)
		{
			parts.add("PB " + PersonalBests.format(applicant.getPbSeconds()));
		}

		int total = totalLevel(applicant);
		if (total > 0)
		{
			parts.add("total " + total);
		}

		String tag = AccountTypes.tag(AccountTypes.fromName(applicant.getAccountType()));
		if (tag != null)
		{
			parts.add(tag);
		}

		return parts;
	}

	private static int totalLevel(Applicant applicant)
	{
		if (applicant.getStats() == null)
		{
			return 0;
		}
		int total = 0;
		for (Integer level : applicant.getStats().values())
		{
			if (level != null)
			{
				total += level;
			}
		}
		return total;
	}

	/** Host: the queued card offering one applicant, skipped if they stop being pending before its turn. */
	private ChatboxCards.Card applicantCard(Applicant applicant, Activity activity)
	{
		return new ChatboxCards.Card()
		{
			@Override
			public ChatboxCards.Priority priority()
			{
				return ChatboxCards.Priority.JOIN_REQUEST;
			}

			@Override
			public Object key()
			{
				return applicant.getMemberId();
			}

			@Override
			public boolean isStale()
			{
				return !config.inGamePrompts() || !liveParty.isPendingApplicant(applicant.getMemberId());
			}

			@Override
			public PartyPrompt open()
			{
				return openApplicantPrompt(applicant, activity);
			}
		};
	}

	private PartyPrompt openApplicantPrompt(Applicant applicant, Activity activity)
	{
		PartyPrompt card = PartyPrompt.create(chatboxPanelManager)
			.heading("Join request")
			.title(applicant.getName())
			.subtitle("Wants to join your " + activity.getDisplayName() + " party")
			.warning(applicantWarning(applicant));
		// A blocked applicant gets the block line and nothing else, exactly as the chat line does.
		if (!applicant.isBlocked())
		{
			card.meta("cb " + applicant.getCombatLevel());
			for (String part : applicantParts(applicant, activity))
			{
				card.detail(part);
			}
		}

		return card.option("Accept", PartyPrompt.ACCEPT, () -> {
				if (liveParty.admit(applicant.getMemberId(), applicant.getName()))
				{
					announceResolved(applicant, activity, true);
				}
				else
				{
					chat("Party is full - couldn't accept " + applicant.getName() + ".", true);
				}
			})
			.option("Decline", PartyPrompt.DECLINE, () -> {
				liveParty.reject(applicant.getMemberId());
				announceResolved(applicant, activity, false);
			})
			.option("Decide later", PartyPrompt.NEUTRAL, () -> { })
			.build();
	}

	@Override
	public void announceResolved(Applicant applicant, Activity activity, boolean accepted)
	{
		// If resolved elsewhere while the prompt is open, close it so it can't be actioned twice.
		dismissPromptFor(applicant.getMemberId());
		chat((accepted ? "Accepted " : "Declined ") + applicant.getName()
			+ " for your " + activity.getDisplayName() + " party.", true);
	}

	/** Drop or close the applicant card for {@code memberId}, wherever it got to in the queue. */
	private void dismissPromptFor(long memberId)
	{
		if (memberId == 0)
		{
			return;
		}
		clientThread.invoke(() -> cards.dismiss(memberId));
	}

	/**
	 * Add an "Invite to party" option to the right-click menu of a friend in the in-game friends list, but
	 * only while we're in a party and that friend isn't already in it.
	 */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		partyShare.onMenuEntryAdded(event);
		// Anchor on the friend row's "Message" option, scoped to the friends-list interface.
		if (!"Message".equals(event.getOption())
			|| WidgetUtil.componentToInterface(event.getActionParam1()) != InterfaceID.FRIENDS)
		{
			return;
		}
		OSPartyPanel currentPanel = panel;
		if (currentPanel == null)
		{
			return;
		}
		Advertisement ad = currentPanel.currentAd();
		if (ad == null || ad.getId() == null)
		{
			return; // not hosting or in a party — nothing to invite to
		}
		String friend = Text.removeTags(event.getTarget());
		String normalized = PlayerNames.normalize(friend);
		if (normalized.isEmpty() || normalized.equals(PlayerNames.normalize(playerName)))
		{
			return; // unresolved name, or it's us
		}
		if (!isFriendOnline(normalized))
		{
			return; // offline friends can't receive an invite
		}
		// Don't offer to invite someone already in the party.
		for (Member member : liveParty.currentMembers())
		{
			if (normalized.equals(PlayerNames.normalize(member.getName())))
			{
				return;
			}
		}
		String adId = ad.getId();
		client.createMenuEntry(-1)
			.setOption("Invite to party")
			.setTarget(event.getTarget())
			.setType(MenuAction.RUNELITE)
			.onClick(e -> sendInvite(adId, friend));
	}

	/** Send a party invite to {@code friend} and report the outcome in the chatbox. Rate-limited per friend. */
	private void sendInvite(String adId, String friend)
	{
		String normalized = PlayerNames.normalize(friend);
		long now = System.currentTimeMillis();
		Long last = lastInviteAt.get(normalized);
		if (last != null && now - last < INVITE_COOLDOWN_MS)
		{
			long seconds = (INVITE_COOLDOWN_MS - (now - last) + 999) / 1000;
			chat("You can invite " + friend + " again in " + seconds + "s.", false);
			return;
		}
		lastInviteAt.put(normalized, now);
		String myName = playerName;
		long myHash = accountHash;
		apiClient.inviteFriend(adId, myName, myHash, friend, delivered ->
		{
			if (delivered)
			{
				chat("Invited " + friend + " to the party.", false);
			}
			else
			{
				// Not delivered — drop the cooldown so they can retry the moment the friend is back.
				lastInviteAt.remove(normalized);
				chat(friend + " isn't online in OSParty.", false);
			}
		});
	}

	/** @return whether the OSRS friend named {@code normalizedName} is currently online (world &gt; 0). */
	private boolean isFriendOnline(String normalizedName)
	{
		NameableContainer<Friend> friends = client.getFriendContainer();
		if (friends == null)
		{
			return false;
		}
		for (Friend friend : friends.getMembers())
		{
			if (friend != null && friend.getName() != null
				&& normalizedName.equals(PlayerNames.normalize(friend.getName())))
			{
				return friend.getWorld() > 0;
			}
		}
		return false;
	}

	/** Register our OSRS identity with the server so invites can reach us; only re-sent when it changes. */
	private void maybeIdentify()
	{
		long hash = accountHash;
		String name = playerName;
		if (name == null || hash == -1L || hash == 0L)
		{
			return;
		}
		if (hash == identifiedHash && name.equals(identifiedName))
		{
			return;
		}
		identifiedHash = hash;
		identifiedName = name;
		apiClient.identify(hash, name);
	}

	/** Handle an incoming party invite: surface it per the {@link InviteDisplay} config. May run off the EDT. */
	private void onPartyInvite(PartyInvite invite)
	{
		InviteDisplay mode = config.inviteDisplay();
		Advertisement ad = invite.getAd();
		if (mode == null || mode == InviteDisplay.DISABLED || ad == null)
		{
			return;
		}
		activeInvites.put(ad.getId(), invite);
		desktopNotify(inviterName(invite) + " invited you to their party.");
		if (mode.showsInGame())
		{
			// In-game chatbox Accept/Decline card (mirrors the host's applicant card); shown once the queue frees.
			cards.offer(inviteCardFor(invite));
		}
		if (mode.showsSidebar())
		{
			// Accept/Decline banner in the side panel, plus a blink to draw the eye to the sidebar.
			SwingUtilities.invokeLater(() ->
			{
				if (panel != null)
				{
					panel.addInvite(invite);
				}
				flashInviteButton();
			});
		}
	}

	/**
	 * Resolve an invite (Accept or Decline) from either surface; dismisses both and joins on accept.
	 * {@code inGame} carries which surface answered, so the role question is asked in the same place.
	 */
	private void resolveInvite(PartyInvite invite, boolean accept, boolean inGame)
	{
		Advertisement ad = invite.getAd();
		String key = ad == null ? null : ad.getId();
		boolean firstResolution = key != null && activeInvites.remove(key) != null;
		// Dismiss both surfaces regardless of which one the player used (idempotent).
		SwingUtilities.invokeLater(() ->
		{
			if (panel != null)
			{
				panel.removeInvite(key);
			}
			stopInviteFlash();
		});
		dismissInvitePrompt(key);
		if (!firstResolution)
		{
			return; // already handled via the other surface
		}
		if (accept)
		{
			acceptInvite(invite, inGame);
		}
		else
		{
			chat("Declined " + inviterName(invite) + "'s party invite.", false);
		}
	}

	/** Drop or close the invite card for {@code key}, wherever it got to in the queue. */
	private void dismissInvitePrompt(String key)
	{
		if (key == null)
		{
			return;
		}
		clientThread.invoke(() -> cards.dismiss(key));
	}

	private static String inviterName(PartyInvite invite)
	{
		String from = invite.getFromName() != null ? invite.getFromName() : invite.getAd().getHost();
		return from != null ? from : "A friend";
	}

	/** The queued card offering one invite, skipped if the side panel answers it first. */
	private ChatboxCards.Card inviteCardFor(PartyInvite invite)
	{
		String key = invite.getAd().getId();
		return new ChatboxCards.Card()
		{
			@Override
			public ChatboxCards.Priority priority()
			{
				return ChatboxCards.Priority.INVITE;
			}

			@Override
			public Object key()
			{
				return key;
			}

			@Override
			public boolean isStale()
			{
				return key == null || !activeInvites.containsKey(key);
			}

			@Override
			public PartyPrompt open()
			{
				return openInvitePrompt(invite);
			}
		};
	}

	/** Open the Accept/Decline card for a received invite; Accept turns it to the role question. */
	private PartyPrompt openInvitePrompt(PartyInvite invite)
	{
		// Accepting turns this card to the role question rather than closing it, so it is kept until
		// the join is settled one way or the other.
		partyCard = InvitePrompt.open(chatboxPanelManager, invite, inviterName(invite),
			() -> resolveInvite(invite, true, true),
			() -> resolveInvite(invite, false, true),
			() ->
			{
				partyCard = null;
				answerRole(null);
			});
		return partyCard;
	}

	/** Identity of the similar-parties card. There is only ever one, so it needs no per-party key. */
	private static final Object SIMILAR_CARD_KEY = new Object();

	/**
	 * The player is creating a party and something is already running it. Queued at the top priority:
	 * they clicked Create and nothing happens until this is answered.
	 */
	private void onSimilarParties(SimilarParties similar)
	{
		InviteDisplay mode = config.matchDisplay();
		if (mode == null || !mode.showsInGame())
		{
			return; // the Create tab's own inline prompt is the only surface asked for
		}
		cards.offer(new ChatboxCards.Card()
		{
			@Override
			public ChatboxCards.Priority priority()
			{
				return ChatboxCards.Priority.ACTION;
			}

			@Override
			public Object key()
			{
				return SIMILAR_CARD_KEY;
			}

			@Override
			public PartyPrompt open()
			{
				partyCard = SimilarPrompt.open(chatboxPanelManager, similar,
					() ->
					{
						Advertisement best = similar.matches().get(0);
						// Turn the page first so the card never blanks while the application goes out.
						SimilarPrompt.showRequesting(partyCard, best);
						hideSimilarPanel();
						similar.requestJoin(best, inGameRoleChooser);
					},
					() ->
					{
						hideSimilarPanel();
						similar.createAnyway();
					},
					() ->
					{
						hideSimilarPanel();
						similar.createAndStopAsking();
					},
					() ->
					{
						partyCard = null;
						answerRole(null);
					});
				return partyCard;
			}
		});
	}

	/** Answering in-game takes the Create tab's inline copy of the question down with it. */
	private void hideSimilarPanel()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (panel != null)
			{
				panel.hideSimilar();
			}
		});
	}

	/**
	 * The raid whose party board this click belongs to, or null. A button inside one of the boards'
	 * interfaces names its raid; the boards themselves are scene objects, so a Make party option on one is
	 * tied to the raid by where the player is standing.
	 */
	private Activity raidBoardClick(MenuOptionClicked event)
	{
		if (isWidgetOp(event))
		{
			return raidBoardOf(WidgetUtil.componentToInterface(event.getParam1()));
		}
		String option = event.getMenuOption();
		if (option != null && option.toLowerCase().startsWith("make"))
		{
			Activity near = Activity.nearby(mapRegions);
			return near != null && near.isRaid() ? near : null;
		}
		return null;
	}

	private static boolean isWidgetOp(MenuOptionClicked event)
	{
		MenuAction action = event.getMenuAction();
		return action == MenuAction.CC_OP || action == MenuAction.CC_OP_LOW_PRIORITY;
	}

	/** The component a widget click landed on; -1 for a click on the scene, whose param is not a component. */
	private static int raidBoardComponent(MenuOptionClicked event)
	{
		return isWidgetOp(event) ? event.getParam1() : -1;
	}

	/** The raid whose party board, party-details or lobby interface {@code group} is, or null. */
	private static Activity raidBoardOf(int group)
	{
		switch (group)
		{
			case InterfaceID.TOB_PARTYLIST:
			case InterfaceID.TOB_PARTYDETAILS:
				return Activity.THEATRE_OF_BLOOD;
			case InterfaceID.TOA_PARTYLIST:
			case InterfaceID.TOA_PARTYDETAILS:
			case InterfaceID.TOA_LOBBY:
				return Activity.TOMBS_OF_AMASCUT;
			case InterfaceID.RAIDS_LOBBY_PARTYLIST:
			case InterfaceID.RAIDS_LOBBY_PARTYDETAILS:
			case InterfaceID.RAIDS_SIDEPANEL:
				return Activity.CHAMBERS_OF_XERIC;
			default:
				return null;
		}
	}


	/** Identity of the raid-party card. One party is made at a time, so it needs no per-party key. */
	private static final Object RAID_CARD_KEY = new Object();

	private enum RaidAnswer
	{
		ADVERTISE,
		DISMISS,
		DONT_ASK
	}

	/**
	 * The player just made a raid party in-game. Per the setting: advertise it outright, ask on the
	 * surfaces {@link InviteDisplay} names, or leave it alone. Client thread.
	 */
	private void onRaidPartyDetected(RaidPartyDetected detected)
	{
		RaidPartyAutoCreate mode = config.raidPartyAutoCreate();
		InviteDisplay display = config.raidPartyPromptDisplay();
		OSPartyPanel currentPanel = panel;
		boolean inParty = liveParty.isInParty() || (currentPanel != null && currentPanel.currentAd() != null);
		log.debug("Raid party detected: {} (setting {}, prompt {}, in a party {}, connected {})", detected, mode,
			display, inParty, apiClient.isApiConnected());
		if (mode == null || mode == RaidPartyAutoCreate.OFF || currentPanel == null || inParty)
		{
			return;
		}
		if (!apiClient.isApiConnected())
		{
			chat("Not connected to OSParty, so your " + detected.label() + " party can't be advertised.", true);
			return;
		}
		if (mode == RaidPartyAutoCreate.ALWAYS)
		{
			SwingUtilities.invokeLater(() -> advertiseRaidParty(detected, null));
			return;
		}
		if (display == null || display == InviteDisplay.DISABLED)
		{
			return;
		}
		// A party made after disbanding the last one supersedes its offer; a card still up for the old one
		// would answer for a party that no longer exists.
		cards.dismiss(RAID_CARD_KEY);
		openRaidOffer.set(detected);
		if (display.showsInGame())
		{
			cards.offer(raidPartyCard(detected));
		}
		if (display.showsSidebar())
		{
			SwingUtilities.invokeLater(() ->
			{
				if (panel != null)
				{
					panel.addRaidPartyOffer(detected,
						() -> resolveRaidOffer(detected, RaidAnswer.ADVERTISE, null, false),
						() -> resolveRaidOffer(detected, RaidAnswer.DISMISS, null, false));
				}
				flashInviteButton();
			});
		}
	}

	/** The queued card asking about one raid party, skipped once it was answered on the sidebar. */
	private ChatboxCards.Card raidPartyCard(RaidPartyDetected detected)
	{
		return new ChatboxCards.Card()
		{
			@Override
			public ChatboxCards.Priority priority()
			{
				return ChatboxCards.Priority.ACTION;
			}

			@Override
			public Object key()
			{
				return RAID_CARD_KEY;
			}

			@Override
			public boolean isStale()
			{
				return openRaidOffer.get() != detected || liveParty.isInParty();
			}

			@Override
			public PartyPrompt open()
			{
				partyCard = RaidPartyPrompt.open(chatboxPanelManager, latest(detected), () ->
					{
						OSPartyPanel currentPanel = panel;
						return currentPanel == null
							? Collections.<Role>emptyList() : currentPanel.raidRoleOptions(latest(detected));
					},
					role -> resolveRaidOffer(detected, RaidAnswer.ADVERTISE, role, true),
					() -> resolveRaidOffer(detected, RaidAnswer.DISMISS, null, true),
					() -> resolveRaidOffer(detected, RaidAnswer.DONT_ASK, null, true),
					() -> partyCard = null);
				return partyCard;
			}
		};
	}

	/**
	 * The party as the game has it now rather than as it was when it appeared: its mode, size and
	 * invocations are chosen after it is made, so they are read when the host answers. Client thread.
	 */
	private RaidPartyDetected latest(RaidPartyDetected detected)
	{
		RaidPartyDetected fresh = raidPartyWatcher.snapshot(detected.getActivity());
		return fresh != null ? fresh : detected;
	}

	/**
	 * Resolve the raid-party offer from either surface, clearing it off both. Settled once, by whichever
	 * surface answers first; {@code inGame} leaves the card alone because it is closing itself.
	 */
	private void resolveRaidOffer(RaidPartyDetected detected, RaidAnswer answer, String hostRole, boolean inGame)
	{
		if (!openRaidOffer.compareAndSet(detected, null))
		{
			return;
		}
		if (!inGame)
		{
			clientThread.invoke(() -> cards.dismiss(RAID_CARD_KEY));
		}
		if (answer == RaidAnswer.DONT_ASK)
		{
			configManager.setConfiguration(OSPartyConfig.GROUP, OSPartyConfig.RAID_PARTY_AUTO_CREATE,
				RaidPartyAutoCreate.OFF);
			chat("OSParty won't ask about your raid parties again. Turn it back on under Hosting in the plugin settings.",
				false);
		}
		SwingUtilities.invokeLater(() ->
		{
			if (panel != null)
			{
				panel.removeRaidPartyOffer();
			}
			stopInviteFlash();
		});
		if (answer == RaidAnswer.ADVERTISE)
		{
			// The answer is the moment the host has finished setting the party up, so this is when its
			// mode, size and invocations are read -- on the client thread, whichever surface answered.
			clientThread.invoke(() ->
			{
				RaidPartyDetected fresh = latest(detected);
				SwingUtilities.invokeLater(() -> advertiseRaidParty(fresh, hostRole));
			});
		}
	}

	/** Run the create; when the form still needs a role from the player, point them at it. EDT only. */
	private void advertiseRaidParty(RaidPartyDetected detected, String hostRole)
	{
		OSPartyPanel currentPanel = panel;
		if (currentPanel == null)
		{
			return;
		}
		if (!currentPanel.createFromRaid(detected, hostRole))
		{
			chat("Pick the role you'll fill on the OSParty Party tab, then press Create party.", false);
			flashInviteButton();
		}
	}

	/**
	 * A party turned up while the player has <em>Find me a party</em> on. Surfaced per
	 * {@link InviteDisplay}, the same way a friend's invite is, because it asks the same thing.
	 */
	private void onMatchFound(MatchOffer offer)
	{
		InviteDisplay mode = config.matchDisplay();
		Advertisement ad = offer.ad();
		if (mode == null || mode == InviteDisplay.DISABLED || ad == null || ad.getId() == null)
		{
			return;
		}
		openMatchId = ad.getId();
		desktopNotify(ad.getHost() + " is running " + activityLabel(ad) + ".");
		if (mode.showsInGame())
		{
			cards.offer(matchCardFor(offer));
		}
		if (mode.showsSidebar())
		{
			SwingUtilities.invokeLater(() ->
			{
				if (panel != null)
				{
					panel.addMatch(offer,
						found -> resolveMatch(found, Answer.JOIN, false),
						found -> resolveMatch(found, Answer.DISMISS, false));
				}
				flashInviteButton();
			});
		}
	}

	/** The queued card offering one found party, skipped once the player stops looking or answers it. */
	private ChatboxCards.Card matchCardFor(MatchOffer offer)
	{
		String key = offer.ad().getId();
		return new ChatboxCards.Card()
		{
			@Override
			public ChatboxCards.Priority priority()
			{
				return ChatboxCards.Priority.MATCH;
			}

			@Override
			public Object key()
			{
				return key;
			}

			@Override
			public boolean isStale()
			{
				return !key.equals(openMatchId);
			}

			@Override
			public PartyPrompt open()
			{
				partyCard = MatchPrompt.open(chatboxPanelManager, offer,
					() ->
					{
						// Turn the page first so the card never blanks while the application goes out.
						MatchPrompt.showRequesting(partyCard, offer.ad());
						resolveMatch(offer, Answer.JOIN, true);
					},
					() -> resolveMatch(offer, Answer.DISMISS, true),
					() -> resolveMatch(offer, Answer.STOP_LOOKING, true),
					() ->
					{
						partyCard = null;
						answerRole(null);
					});
				return partyCard;
			}
		};
	}

	private enum Answer
	{
		JOIN,
		DISMISS,
		STOP_LOOKING
	}

	/**
	 * Resolve a match offer from either surface, clearing it off both. {@code inGame} keeps the card
	 * that asked open, because a Request turns that same card to the role question.
	 */
	private void resolveMatch(MatchOffer offer, Answer answer, boolean inGame)
	{
		String key = offer.ad().getId();
		openMatchId = null;
		SwingUtilities.invokeLater(() ->
		{
			if (panel != null)
			{
				panel.removeInvite(key);
			}
			stopInviteFlash();
		});
		if (!inGame)
		{
			clientThread.invoke(() -> cards.dismiss(key));
		}
		switch (answer)
		{
			case JOIN:
				offer.join(inGameRoleChooser, message -> chat(message, false));
				break;
			case DISMISS:
				offer.dismiss();
				break;
			case STOP_LOOKING:
				offer.stopLooking();
				break;
		}
	}

	private static String activityLabel(Advertisement ad)
	{
		Activity activity = Activity.fromId(ad.getActivity());
		return activity == null ? "a party" : activity.displayName(ad.isHardMode(), ad.getInvocation());
	}

	/** Accept an invite: join the party by its invite code, reusing the standard join flow. */
	private void acceptInvite(PartyInvite invite, boolean inGame)
	{
		Advertisement ad = invite.getAd();
		String code = ad.getInviteCode();
		OSPartyPanel currentPanel = panel;
		if (code == null || code.isEmpty() || currentPanel == null)
		{
			chat("Couldn't join that party - the invite is missing its code.", false);
			return;
		}
		RoleChooser chooser = inGame ? inGameRoleChooser : null;
		SwingUtilities.invokeLater(() ->
			currentPanel.joinByInviteCode(code, message -> chat(message, false), chooser));
	}

	/**
	 * Asks which role we'll fill in-game, so an invite taken in-game is finished there. The question is
	 * drawn onto the card the player accepted on, which is still up, rather than opening a second one.
	 */
	private final RoleChooser inGameRoleChooser = new RoleChooser()
	{
		@Override
		public void choose(Advertisement ad, Activity activity, List<Role> options,
			java.util.function.Consumer<String> onPicked)
		{
			clientThread.invoke(() ->
			{
				pendingRolePick = role ->
				{
					if (role == null)
					{
						chat("Didn't join " + ad.getHost() + "'s party - no role picked.", false);
					}
					onPicked.accept(role);
				};
				PartyPrompt card = partyCard;
				if (card != null && cards.current() == card)
				{
					InvitePrompt.showRolePicker(card, ad, activity, options, OSPartyPlugin.this::answerRole);
				}
				else
				{
					partyCard = InvitePrompt.openRolePicker(chatboxPanelManager, ad, activity, options,
						OSPartyPlugin.this::answerRole, () ->
						{
							partyCard = null;
							answerRole(null);
						});
				}
			});
		}

		@Override
		public void dismiss()
		{
			clientThread.invoke(() ->
			{
				PartyPrompt card = partyCard;
				if (card != null && cards.current() == card)
				{
					chatboxPanelManager.close();
				}
			});
		}
	};

	/**
	 * Settle the outstanding role question exactly once, however it was answered: a pick, Cancel, or
	 * the card being closed out from under it.
	 */
	private void answerRole(String role)
	{
		java.util.function.Consumer<String> pending = pendingRolePick;
		if (pending == null)
		{
			return;
		}
		pendingRolePick = null;
		pending.accept(role);
	}

	/** Flash the OSParty sidebar button until the panel is opened. No-op if the panel is already open. EDT only. */
	private void flashInviteButton()
	{
		if (navButtonAlert == null || panelActive || navBlinkTimer != null)
		{
			return;
		}
		navBlinkTimer = new Timer(600, e ->
		{
			// Never swap while our panel is open — removing the selected button would force it closed.
			if (!panelActive)
			{
				showNavButton(!navAlertShown);
			}
		});
		navBlinkTimer.setInitialDelay(0);
		navBlinkTimer.start();
	}

	/** Stop flashing and restore the normal button when it's safe (panel not open). EDT only. */
	private void stopInviteFlash()
	{
		if (navBlinkTimer != null)
		{
			navBlinkTimer.stop();
			navBlinkTimer = null;
		}
		if (navAlertShown && !panelActive)
		{
			showNavButton(false);
		}
	}

	/** Panel opened: stop flashing. We leave the icon as-is (swapping now would close the open panel). EDT. */
	private void onPanelActivated()
	{
		panelActive = true;
		if (navBlinkTimer != null)
		{
			navBlinkTimer.stop();
			navBlinkTimer = null;
		}
	}

	/** Panel closed: now safe to restore the normal icon if the alert one is still showing. EDT. */
	private void onPanelDeactivated()
	{
		panelActive = false;
		if (navAlertShown)
		{
			showNavButton(false);
		}
	}

	/** Build (or rebuild) both sidebar buttons at the configured priority. Neither is registered here. */
	private void buildNavButtons()
	{
		int priority = config.sidePanelPriority();
		navButton = NavigationButton.builder()
			.tooltip("OSParty")
			.icon(navIcon)
			.priority(priority)
			.panel(panel)
			.build();
		navButtonAlert = NavigationButton.builder()
			.tooltip("OSParty: needs your attention")
			.icon(withInviteBadge(navIcon))
			.priority(priority)
			.panel(panel)
			.build();
	}

	/**
	 * Re-register the sidebar button so a priority change moves the icon without a plugin restart.
	 * A NavigationButton's priority is fixed at build time, so both buttons are rebuilt and whichever
	 * was showing (normal vs. alert) is put back. EDT only.
	 */
	private void rebuildNavButtons()
	{
		if (navButton == null || navButtonAlert == null)
		{
			return;
		}
		boolean alert = navAlertShown;
		clientToolbar.removeNavigation(alert ? navButtonAlert : navButton);
		buildNavButtons();
		clientToolbar.addNavigation(alert ? navButtonAlert : navButton);
	}

	/** Swap which sidebar button is registered (normal vs. red-dot alert). EDT only. */
	private void showNavButton(boolean alert)
	{
		if (navButton == null || navButtonAlert == null)
		{
			return;
		}
		clientToolbar.removeNavigation(alert ? navButton : navButtonAlert);
		clientToolbar.addNavigation(alert ? navButtonAlert : navButton);
		navAlertShown = alert;
	}

	/** @return a copy of {@code base} with a small red notification dot in the top-right corner. */
	private static BufferedImage withInviteBadge(BufferedImage base)
	{
		BufferedImage badged = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = badged.createGraphics();
		g.drawImage(base, 0, 0, null);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		int diameter = Math.max(6, base.getWidth() / 3);
		int x = base.getWidth() - diameter;
		g.setColor(new Color(0xE5, 0x39, 0x35));
		g.fillOval(x, 0, diameter, diameter);
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(1f));
		g.drawOval(x, 0, diameter - 1, diameter - 1);
		g.dispose();
		return badged;
	}


	/** Send a desktop notification for an OSParty event, when the user has opted in. */
	private void desktopNotify(String message)
	{
		if (config.desktopNotifications())
		{
			notifier.notify(message);
		}
	}

	/**
	 * Post an OSParty chat line (client thread). No-op when not logged in. {@code optional} lines are
	 * event chatter the chatbox-notifications toggle suppresses; the rest answer something the player did.
	 */
	private void chat(String message, boolean optional)
	{
		if (optional && !config.chatboxNotifications())
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		String formatted = ColorUtil.wrapWithColorTag("[OSParty]", Color.ORANGE) + " " + message;
		clientThread.invoke(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", formatted, null));
	}

	@Provides
	OSPartyConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OSPartyConfig.class);
	}

	@Provides
	@Singleton
	LivePartyBackend provideLivePartyBackend(LiveParty liveParty)
	{
		return liveParty;
	}
}
