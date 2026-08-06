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
import net.osparty.party.JoinPromptEvent;
import net.osparty.party.PlayerNames;
import net.osparty.party.HostTransferEvent;
import net.osparty.party.LiveParty;
import net.osparty.party.LocalPlayerSnapshot;
import net.osparty.party.LivePartyBackend;
import net.osparty.party.SpecDrainEvent;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import net.osparty.ui.HiscoreLookup;
import net.osparty.ui.OSPartyPanel;
import net.osparty.ui.ApplicantOverlay;
import net.osparty.ui.JoinPromptOverlay;
import net.osparty.ui.DefenceInfoBox;
import net.osparty.ui.NpcDefenceOverlay;
import net.osparty.ui.PartyNameOverlay;
import net.osparty.ui.PlayerMarkerOverlay;
import net.osparty.ui.ReadyCheckOverlay;
import net.osparty.ui.PingArrowOverlay;
import net.osparty.ui.TilePingOverlay;
import com.google.inject.Provides;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

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
import net.runelite.api.NameableContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.SoundEffectID;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.FakeXpDrop;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
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
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.config.Keybind;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
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
import net.osparty.enums.InviteDisplay;

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
	private Gson gson;

	@Inject
	private AudioPlayer audioPlayer;

	@Inject
	private KeyManager keyManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private DefenceTracker defenceTracker;

	@Inject
	private SpecialAttackTracker specTracker;

	@Inject
	private CoxRaidScanner coxRaidScanner;

	@Inject
	private InfoBoxManager infoBoxManager;

	// Reaches RuneLite's own plugins: the Hiscore panel for card menus, and Player Indicators so our
	// overhead party names don't print through the names it already draws.
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
	private BufferedImage navIcon;
	private NavigationButton navButton;
	private NavigationButton navButtonAlert;
	private Timer navBlinkTimer;
	private boolean navAlertShown;
	private volatile boolean panelActive;
	private final ConcurrentLinkedDeque<PartyInvite> invitePromptQueue = new ConcurrentLinkedDeque<>();
	private final Map<String, PartyInvite> activeInvites = new ConcurrentHashMap<>();
	private static final long INVITE_COOLDOWN_MS = 30_000;
	private static final String SOUND_READY_CHECK = "/net/osparty/sounds/readycheck.wav";
	private static final String SOUND_ALL_READY = "/net/osparty/sounds/ready.wav";
	private static final String SOUND_KICKED = "/net/osparty/sounds/kicked.wav";
	private static final String SOUND_FRIENDS_CHAT = "/net/osparty/sounds/friendschatsound.wav";
	private final Map<String, Long> lastInviteAt = new ConcurrentHashMap<>();
	private String openInviteId;
	private long identifiedHash;
	private String identifiedName;
	private ApplicantOverlay applicantOverlay;
	private JoinPromptOverlay joinPromptOverlay;
	private ReadyCheckOverlay readyCheckOverlay;
	private TilePingOverlay tilePingOverlay;
	private PingArrowOverlay pingArrowOverlay;
	private NpcDefenceOverlay defenceOverlay;
	private PlayerMarkerOverlay playerMarkerOverlay;

	private PartyNameOverlay partyNameOverlay;
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
	private final ConcurrentLinkedDeque<PendingPrompt> promptQueue = new ConcurrentLinkedDeque<>();
	private volatile boolean promptOpen;
	private long openPromptMemberId;

	/**
	 * The OS-dependent twin of the backtick key: the same physical key is reported as
	 * Back Quote on some platforms/layouts and as Dead Grave on others (e.g. Windows),
	 * so a binding to either must accept both.
	 */
	private static int graveTwin(int keyCode)
	{
		if (keyCode == KeyEvent.VK_BACK_QUOTE)
		{
			return KeyEvent.VK_DEAD_GRAVE;
		}
		if (keyCode == KeyEvent.VK_DEAD_GRAVE)
		{
			return KeyEvent.VK_BACK_QUOTE;
		}
		return KeyEvent.VK_UNDEFINED;
	}

	private boolean pingHotkeyMatches(KeyEvent e, boolean release)
	{
		Keybind bind = config.pingHotkey();
		int twin = graveTwin(bind.getKeyCode());
		if (release)
		{
			// A modifier-only bind (Shift, Ctrl, ...) is normalised to VK_UNDEFINED with the
			// modifier in the mask, so there is no key code to compare; Keybind knows how to
			// match its release.
			if (bind.getKeyCode() == KeyEvent.VK_UNDEFINED)
			{
				return bind.matches(e);
			}
			// Match the key alone so releasing a modifier first can't leave the hotkey stuck down.
			return e.getKeyCode() == bind.getKeyCode()
				|| (twin != KeyEvent.VK_UNDEFINED && e.getKeyCode() == twin);
		}
		return bind.matches(e)
			|| (twin != KeyEvent.VK_UNDEFINED && new Keybind(twin, bind.getModifiers()).matches(e));
	}

	private final KeyListener pingHotkeyListener = new KeyListener()
	{
		@Override
		public void keyTyped(KeyEvent e)
		{
		}

		@Override
		public void keyPressed(KeyEvent e)
		{
			if (pingHotkeyMatches(e, false))
			{
				pingHotkeyDown = true;
			}
		}

		@Override
		public void keyReleased(KeyEvent e)
		{
			if (pingHotkeyMatches(e, true))
			{
				pingHotkeyDown = false;
			}
		}

		@Override
		public void focusLost()
		{
			// A key released while the client is unfocused delivers no keyReleased.
			pingHotkeyDown = false;
		}
	};

	private final MouseAdapter pingMouseListener = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			if (pingHotkeyDown && config.pings() && SwingUtilities.isLeftMouseButton(event)
					&& liveParty.isInParty())
			{
				pingHoveredTile();
				event.consume();
			}
			return event;
		}
	};

	private static final class PendingPrompt
	{
		final Applicant applicant;
		final Activity activity;

		PendingPrompt(Applicant applicant, Activity activity)
		{
			this.applicant = applicant;
			this.activity = activity;
		}
	}

	/**
	 * Attach the coupling conversation to the UI.
	 *
	 * <p>Coupling is how a second machine joins an account this service already knows: the machine that
	 * already holds the credential shows a six-digit code, and the new one has to be told it. Reading it off
	 * the other screen is the whole check -- it is the one thing somebody who merely knows the account
	 * cannot do -- which is why the code never travels to the machine asking for it.
	 *
	 * <p>All four callbacks arrive on the socket reader thread, so each hops to the EDT before touching
	 * Swing.
	 */
	private void wireCoupling()
	{
		socket.setOnCouplingCode(event -> SwingUtilities.invokeLater(() ->
			JOptionPane.showMessageDialog(panel,
				"Another device is signing in to this account.\n\n"
					+ "Code: " + event.code + "\n\n"
					+ "Type it there to allow it. If this wasn't you, ignore this — nothing happens without "
					+ "the code, and this device keeps working either way.",
				"OSParty device code", JOptionPane.INFORMATION_MESSAGE)));

		socket.setOnCouplingRequired(event -> SwingUtilities.invokeLater(() ->
		{
			String code = JOptionPane.showInputDialog(panel,
				"This account is already signed in on another device.\n\n"
					+ "Open OSParty there to see a six-digit code, then type it here.",
				"OSParty device code", JOptionPane.QUESTION_MESSAGE);
			if (code != null && !code.trim().isEmpty())
			{
				socket.couplingConfirm(accountHash, code.trim());
			}
		}));

		socket.setOnCouplingResult(event -> SwingUtilities.invokeLater(() ->
		{
			if (event.success)
			{
				JOptionPane.showMessageDialog(panel,
					"This device is now signed in. Your other devices are unaffected.",
					"OSParty", JOptionPane.INFORMATION_MESSAGE);
				return;
			}
			JOptionPane.showMessageDialog(panel,
				"That didn't work. The code may have been wrong or expired, or no other device was online "
					+ "to show one. OSParty still works — you'll be asked again next time you connect.",
				"OSParty", JOptionPane.WARNING_MESSAGE);
		}));

		// A notice, not a loss: coupling adds a device and this one keeps its credential. Shown because the
		// screen the code was read off is the one place somebody who did not expect it would notice.
		socket.setOnCouplingAccepted(account -> SwingUtilities.invokeLater(() ->
			JOptionPane.showMessageDialog(panel,
				"Another device just signed in to this account.",
				"OSParty", JOptionPane.INFORMATION_MESSAGE)));
	}

	@Override
	protected void startUp()
	{
		BoardService boardService = apiClient;

		wireCoupling();
		socket.start();

		applicantOverlay = new ApplicantOverlay(config);
		overlayManager.add(applicantOverlay);

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

		keyManager.registerKeyListener(pingHotkeyListener);
		mouseManager.registerMouseListener(pingMouseListener);

		// Ready-check notifications: chat pings and an optional all-ready sound.
		liveParty.setOnReadyCheckStarted(starter -> {
			chat(starter + " started a ready check - ready up in the OSParty panel.", true);
			desktopNotify(starter + " started a ready check.");
			if (config.readyCheckSound())
			{
				playResourceSound(SOUND_READY_CHECK);
			}
		});
		liveParty.setOnAllReady(() -> {
			Activity activity = Activity.fromId(liveParty.currentActivityId());
			String name = activity != null ? activity.getDisplayName() : "the activity";
			chat("Everyone is ready for " + name + "!", true);
			desktopNotify("Everyone is ready for " + name + "!");
			playReadySound();
		});
		liveParty.setOnReadyExpired(() -> chat("Ready check expired.", true));
		liveParty.setOnKicked(() -> {
			if (config.kickSound())
			{
				playResourceSound(SOUND_KICKED);
			}
		});

		// Stand up the live party layer; the advertisement only makes the room findable.
		liveParty.register();

		// Pull the scammer watchlist now; it refreshes periodically (see schedule).
		runeWatchService.refresh();

		worldPinger = new WorldPinger();

		// A player can't block themselves.
		blockListService.setSelf(this::getAccountHash, this::getSelfName);

		HiscoreLookup.init(pluginManager);

		panel = new OSPartyPanel(boardService, config, this::getPlayerName, this,
			this::getFriendsChatOwner, this::getCurrentWorld, itemManager, liveParty, runeWatchService,
			this::getAccountType, killcountService, skillIconManager, this::getMapRegions,
			this::regionForWorld, this::getCoxLayout, configManager, gson,
			worldPinger, this::worldAddressForNum, this::getFriendNames, favoritesService, blockListService,
			this::getAccountHash, spriteManager, partyHistoryService, message -> chat(message, true));

		navIcon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");
		buildNavButtons();

		clientToolbar.addNavigation(navButton);
		panel.setOnActivated(this::onPanelActivated);
		panel.setOnDeactivated(this::onPanelDeactivated);
		panel.setInviteHandlers(invite -> resolveInvite(invite, true), invite -> resolveInvite(invite, false));
		apiClient.setInviteListener(this::onPartyInvite);
		log.info("OSParty started (API {})", BoardApiClient.apiBaseUrl());
	}

	@Override
	protected void shutDown()
	{
		liveParty.leave();
		liveParty.unregister();
		// These live on singletons that outlast the plugin, so a restart would otherwise stack callbacks
		// onto a dead instance. Every setter tolerates null.
		apiClient.setInviteListener(null);
		liveParty.setOnReadyCheckStarted(null);
		liveParty.setOnAllReady(null);
		liveParty.setOnReadyExpired(null);
		liveParty.setOnKicked(null);
		keyManager.unregisterKeyListener(pingHotkeyListener);
		mouseManager.unregisterMouseListener(pingMouseListener);
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
		overlayManager.remove(applicantOverlay);
		overlayManager.remove(joinPromptOverlay);
		overlayManager.remove(readyCheckOverlay);
		overlayManager.remove(tilePingOverlay);
		overlayManager.remove(pingArrowOverlay);
		overlayManager.remove(defenceOverlay);
		overlayManager.remove(playerMarkerOverlay);
		overlayManager.remove(partyNameOverlay);
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
		applicantOverlay = null;
		joinPromptOverlay = null;
		readyCheckOverlay = null;
		tilePingOverlay = null;
		pingArrowOverlay = null;
		defenceOverlay = null;
		playerMarkerOverlay = null;
		partyNameOverlay = null;
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
		promptOpen = false;
		openPromptMemberId = 0;
		openInviteId = null;
		rejoinChecked = false;
		promptQueue.clear();
		invitePromptQueue.clear();
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
			promptQueue.clear();
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
		accountType = client.getAccountType();
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

		// Show the next in-game applicant prompt if the chatbox is free.
		drainApplicantPrompts();

		// Show the next in-game invite prompt if the chatbox is free.
		drainInvitePrompts();

		// Push pending host state / our own live snapshot (client thread).
		liveParty.tick();

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

	/** Broadcast a ping at the tile under the cursor (client thread). Called from the mouse listener. */
	private void pingHoveredTile()
	{
		clientThread.invoke(() -> {
			Tile tile = client.getSelectedSceneTile();
			if (tile == null)
			{
				return;
			}
			WorldPoint point = tile.getWorldLocation();
			if (point != null && liveParty.sendPing(point, config.pingColor()) && config.pingSound())
			{
				client.playSoundEffect(SoundEffectID.SMITH_ANVIL_TINK);
			}
		});
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
			desktopNotify(host + " — " + detail);
			if (config.friendsChatRequestSound())
			{
				playResourceSound(SOUND_FRIENDS_CHAT);
			}
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


	@Override
	public void setPendingApplicants(List<Applicant> applicants, Activity activity)
	{
		applicantOverlay.setApplicants(applicants, activity);
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
			notifier.notify(applicant.getName() + " is on your block list — applied to your "
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
			promptQueue.add(new PendingPrompt(applicant, activity));
		}
	}

	@Override
	public void announceAutoDeclinedBlocked(Applicant applicant, Activity activity)
	{
		notifier.notify("Auto-declined " + applicant.getName() + " — on your block list ("
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

		if (runeWatchService.get(applicant.getName()) != null)
		{
			parts.add("(!) RuneWatch listed");
		}

		return String.join(", ", parts);
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

	/** Host: show the next queued applicant as a chatbox Accept/Decline, one at a time. Client thread. */
	private void drainApplicantPrompts()
	{
		if (!config.inGamePrompts())
		{
			promptQueue.clear();
			return;
		}
		if (promptOpen || promptQueue.isEmpty() || chatboxPanelManager.getCurrentInput() != null)
		{
			return;
		}

		// Skip anyone who's no longer a pending applicant (left, or resolved elsewhere).
		PendingPrompt next = null;
		while (!promptQueue.isEmpty())
		{
			PendingPrompt candidate = promptQueue.poll();
			if (liveParty.isPendingApplicant(candidate.applicant.getMemberId()))
			{
				next = candidate;
				break;
			}
		}
		if (next != null)
		{
			openApplicantPrompt(next);
		}
	}

	private void openApplicantPrompt(PendingPrompt prompt)
	{
		Applicant applicant = prompt.applicant;
		Activity activity = prompt.activity;

		String title = applicant.getName() + " - " + applicantSummary(applicant, activity);

		promptOpen = true;
		openPromptMemberId = applicant.getMemberId();
		chatboxPanelManager.openTextMenuInput(title)
			.option("Accept", () -> {
				promptOpen = false;
				openPromptMemberId = 0;
				if (liveParty.admit(applicant.getMemberId(), applicant.getName()))
				{
					announceResolved(applicant, activity, true);
				}
				else
				{
					chat("Party is full - couldn't accept " + applicant.getName() + ".", true);
				}
			})
			.option("Decline", () -> {
				promptOpen = false;
				openPromptMemberId = 0;
				liveParty.reject(applicant.getMemberId());
				announceResolved(applicant, activity, false);
			})
			.option("Decide later", () -> {
				promptOpen = false;
				openPromptMemberId = 0;
			})
			.onClose(() -> {
				promptOpen = false;
				openPromptMemberId = 0;
			})
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

	/** Close the open chatbox applicant prompt if it's the one for {@code memberId}. */
	private void dismissPromptFor(long memberId)
	{
		if (memberId == 0)
		{
			return;
		}
		clientThread.invoke(() -> {
			if (openPromptMemberId == memberId)
			{
				chatboxPanelManager.close();
			}
		});
	}

	private void playReadySound()
	{
		if (config.readyCheckSound())
		{
			playResourceSound(SOUND_ALL_READY);
		}
	}

	private void playResourceSound(String resource)
	{
		try
		{
			audioPlayer.play(getClass(), resource, 0f);
		}
		catch (Exception e)
		{
			log.warn("OSParty: failed to play sound '{}'", resource, e);
		}
	}

	/**
	 * Add an "Invite to party" option to the right-click menu of a friend in the in-game friends list, but
	 * only while we're in a party and that friend isn't already in it.
	 */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
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
			// In-game chatbox Accept/Decline prompt (mirrors the host's applicant prompt); shown next tick.
			invitePromptQueue.add(invite);
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

	/** Resolve an invite (Accept or Decline) from either surface; dismisses both and joins on accept. */
	private void resolveInvite(PartyInvite invite, boolean accept)
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
			acceptInvite(invite);
		}
		else
		{
			chat("Declined " + inviterName(invite) + "'s party invite.", false);
		}
	}

	/** Close the chatbox invite prompt if it's the one for {@code key}. */
	private void dismissInvitePrompt(String key)
	{
		if (key == null)
		{
			return;
		}
		clientThread.invoke(() ->
		{
			if (key.equals(openInviteId))
			{
				chatboxPanelManager.close();
			}
		});
	}

	private static String inviterName(PartyInvite invite)
	{
		String from = invite.getFromName() != null ? invite.getFromName() : invite.getAd().getHost();
		return from != null ? from : "A friend";
	}

	/** Show the next queued invite as an in-game Accept/Decline prompt if the chatbox is free. Client thread. */
	private void drainInvitePrompts()
	{
		if (invitePromptQueue.isEmpty() || promptOpen || chatboxPanelManager.getCurrentInput() != null)
		{
			return;
		}
		PartyInvite next = invitePromptQueue.poll();
		if (next != null)
		{
			openInvitePrompt(next);
		}
	}

	/** Open the chatbox Accept/Decline prompt for a received invite; Accept joins via the invite code. */
	private void openInvitePrompt(PartyInvite invite)
	{
		Advertisement ad = invite.getAd();
		String key = ad.getId();
		if (key == null || !activeInvites.containsKey(key))
		{
			return; // resolved via the sidebar banner before we got to it
		}
		Activity activity = Activity.fromId(ad.getActivity());
		String label = activity != null ? activity.getDisplayName() + " party" : "their party";

		promptOpen = true;
		openInviteId = key;
		chatboxPanelManager.openTextMenuInput(inviterName(invite) + " invites you to " + label)
			.option("Accept", () ->
			{
				promptOpen = false;
				openInviteId = null;
				resolveInvite(invite, true);
			})
			.option("Decline", () ->
			{
				promptOpen = false;
				openInviteId = null;
				resolveInvite(invite, false);
			})
			.onClose(() ->
			{
				promptOpen = false;
				openInviteId = null;
			})
			.build();
	}

	/** Accept an invite: join the party by its invite code, reusing the standard join flow. */
	private void acceptInvite(PartyInvite invite)
	{
		Advertisement ad = invite.getAd();
		String code = ad.getInviteCode();
		OSPartyPanel currentPanel = panel;
		if (code == null || code.isEmpty() || currentPanel == null)
		{
			chat("Couldn't join that party - the invite is missing its code.", false);
			return;
		}
		SwingUtilities.invokeLater(() -> currentPanel.joinByInviteCode(code, message -> chat(message, false)));
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
			.tooltip("OSParty — needs your attention")
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
