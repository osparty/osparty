package net.osparty;

import net.osparty.api.PartyApiClient;
import net.osparty.api.PartyService;
import net.osparty.combat.CoxRaidScanner;
import net.osparty.combat.DefenceTracker;
import net.osparty.model.Activity;
import net.osparty.model.Applicant;
import net.osparty.model.Party;
import net.osparty.party.FcRequestMessage;
import net.osparty.party.HostTransferMessage;
import net.osparty.party.LiveParty;
import net.osparty.party.MemberCommand;
import net.osparty.party.PartyStateMessage;
import net.osparty.party.PingMessage;
import net.osparty.party.PlayerUpdate;
import net.osparty.party.ReadyCheckMessage;
import net.osparty.runewatch.RuneWatchService;
import net.osparty.ui.OSPartyPanel;
import net.osparty.ui.ApplicantOverlay;
import net.osparty.ui.FcRequestOverlay;
import net.osparty.ui.DefenceInfoBox;
import net.osparty.ui.NpcDefenceOverlay;
import net.osparty.ui.PlayerMarkerOverlay;
import net.osparty.ui.ReadyCheckOverlay;
import net.osparty.ui.TilePingOverlay;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.event.MouseEvent;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import com.google.gson.Gson;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.FriendsChatManager;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.vars.AccountType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.api.World;
import net.runelite.api.widgets.ComponentID;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.game.WorldService;
import net.runelite.client.util.WorldUtil;
import net.runelite.http.api.worlds.WorldRegion;
import net.runelite.http.api.worlds.WorldResult;
import net.runelite.client.party.events.UserJoin;
import net.runelite.client.party.events.UserPart;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.specialcounter.SpecialCounterUpdate;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;

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
	private PartyApiClient apiClient;

	@Inject
	private ItemManager itemManager;

	@Inject
	private SkillIconManager skillIconManager;

	@Inject
	private LiveParty liveParty;

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
	private CoxRaidScanner coxRaidScanner;

	@Inject
	private InfoBoxManager infoBoxManager;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OSPartyConfig config;

	@Inject
	private net.osparty.api.PartySocket partySocket;

	private OSPartyPanel panel;
	private NavigationButton navButton;
	private ApplicantOverlay applicantOverlay;
	private FcRequestOverlay fcRequestOverlay;
	private ReadyCheckOverlay readyCheckOverlay;
	private TilePingOverlay tilePingOverlay;
	private NpcDefenceOverlay defenceOverlay;
	private PlayerMarkerOverlay playerMarkerOverlay;
	/** Status-bar defence info box, present only while tracking and the toggle is on. */
	private DefenceInfoBox defenceBox;

	/** True while the ping hotkey is held; a left-click then pings the hovered tile. */
	private volatile boolean pingHotkeyDown;

	/** Holding the ping hotkey arms a tile ping on the next left-click. */
	private final HotkeyListener pingHotkeyListener = new HotkeyListener(() -> config.pingHotkey())
	{
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

	/** Consumes the hotkey+left-click and pings the tile under the cursor instead. */
	private final MouseAdapter pingMouseListener = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			if (pingHotkeyDown && config.pings() && javax.swing.SwingUtilities.isLeftMouseButton(event)
				&& liveParty.isConnected())
			{
				pingHoveredTile();
				event.consume();
			}
			return event;
		}
	};

	/**
	 * Last known logged in player name. Updated on the client thread and read
	 * (volatile) from the EDT by the panel.
	 */
	private volatile String playerName;

	private volatile String friendsChatOwner;
	private volatile int world;
	/** Currently loaded map regions (for location-aware activity suggestions). */
	private volatile int[] mapRegions;
	private volatile String coxLayout;
	private volatile AccountType accountType;
	/** Local player's stable account id; {@code -1} when logged out. Sent to the API so blocks/favourites survive name changes. */
	private volatile long accountHash = -1L;
	/** Whether we've checked for a resumable hosted party since this login. */
	private boolean rejoinChecked;

	private WorldPinger worldPinger;

	@Inject
	private FavoritesService favoritesService;

	@Inject
	private BlockListService blockListService;

	@Inject
	private net.runelite.client.Notifier notifier;

	@Inject
	private net.osparty.store.PartyStore partyStore;

	@Inject
	private net.osparty.history.PartyHistoryService partyHistoryService;

	@Inject
	private SpriteManager spriteManager;
	/** Snapshot of the local player's friends list, updated each game tick. */
	private volatile Set<String> friendNames = java.util.Collections.emptySet();

	/** Pending quick-hop target (driven on game ticks), or null when not hopping. */
	private volatile net.runelite.http.api.worlds.World quickHopTarget;
	/** How many ticks we've waited for the world switcher widget to appear. */
	private int quickHopAttempts;
	/** Give up opening the world switcher after this many ticks. */
	private static final int QUICK_HOP_MAX_ATTEMPTS = 35;

	/** Applicants awaiting an in-game Accept/Decline prompt (host only). */
	private final java.util.Deque<PendingPrompt> promptQueue = new java.util.ArrayDeque<>();
	/** True while one of our chatbox prompts is open, so we show them one at a time. */
	private boolean promptOpen;
	/** Member id the open chatbox prompt is for, so we can close it if they're resolved elsewhere; 0 = none. */
	private long openPromptMemberId;

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

	@Override
	protected void startUp()
	{
		// Discovery/advertising goes through the real HTTP API (osrs-party-api);
		// the live party itself runs peer-to-peer (see LiveParty).
		PartyService partyService = apiClient;

		// Open the live socket for the plugin session: search reads and host writes both
		// run over it, and an open connection is the host's keep-alive.
		partySocket.start();

		applicantOverlay = new ApplicantOverlay(config);
		overlayManager.add(applicantOverlay);

		fcRequestOverlay = new FcRequestOverlay();
		overlayManager.add(fcRequestOverlay);

		readyCheckOverlay = new ReadyCheckOverlay(liveParty);
		overlayManager.add(readyCheckOverlay);

		tilePingOverlay = new TilePingOverlay(client, liveParty, config);
		overlayManager.add(tilePingOverlay);

		defenceOverlay = new NpcDefenceOverlay(client, defenceTracker, config,
			ImageUtil.resizeImage(skillIconManager.getSkillImage(Skill.DEFENCE), 16, 16));
		overlayManager.add(defenceOverlay);

		playerMarkerOverlay = new PlayerMarkerOverlay(client, liveParty, config,
			ImageUtil.resizeImage(ImageUtil.loadImageResource(getClass(), "/net/osparty/icons/learner.png"), 12, 12),
			ImageUtil.resizeImage(ImageUtil.loadImageResource(getClass(), "/net/osparty/icons/teacher.png"), 12, 12));
		overlayManager.add(playerMarkerOverlay);
		// The defence tracker reads RuneLite's Special Attack Counter events, which
		// only fire while that plugin is running, so make sure it's enabled.
		enablePluginByName("Special Attack Counter");

		// Hotkey + click to ping a tile for the party.
		keyManager.registerKeyListener(pingHotkeyListener);
		mouseManager.registerMouseListener(pingMouseListener);

		// Ready-check notifications: a chat ping when one starts/expires, and a chat
		// line plus optional sound when everyone is ready.
		liveParty.setOnReadyCheckStarted(starter -> {
			gameMessage(starter + " started a ready check - ready up in the OSParty panel.");
			if (config.readyCheckSound())
			{
				playResourceSound("/net/osparty/sounds/readycheck.wav");
			}
		});
		liveParty.setOnAllReady(() -> {
			Activity activity = Activity.fromId(liveParty.currentActivityId());
			String name = activity != null ? activity.getDisplayName() : "the activity";
			gameMessage("Everyone is ready for " + name + "!");
			playReadySound();
		});
		liveParty.setOnReadyExpired(() -> gameMessage("Ready check expired."));

		// Stand up the live P2P party layer (real roster + gear/inv/stats + host
		// management); the API only advertises the room.
		liveParty.register();

		// Pull the scammer watchlist now; it refreshes periodically (see schedule).
		runeWatchService.refresh();

		worldPinger = new WorldPinger();

		// Guard against a player blocking themselves (self-block is meaningless and hides
		// your own ads); the service matches by hash when known, else by current name. Uses the
		// pre-login-aware name so "that's you" is recognised on the login screen too (the account
		// hash stays -1 until logged in, so pre-login the match is by name).
		blockListService.setSelf(this::getAccountHash, this::getSelfName);

		panel = new OSPartyPanel(partyService, config, this::getPlayerName, this,
			this::getFriendsChatOwner, this::getCurrentWorld, itemManager, liveParty, runeWatchService,
			this::getAccountType, killcountService, skillIconManager, this::hopTo, this::getMapRegions,
			this::regionForWorld, this::getCoxLayout, configManager, gson,
			worldPinger, this::worldAddressForNum, this::getFriendNames, favoritesService, blockListService,
			this::getAccountHash, spriteManager, partyHistoryService, this::gameMessage);

		navButton = NavigationButton.builder()
			.tooltip("OSParty")
			.icon(ImageUtil.loadImageResource(getClass(), "panel_icon.png"))
			.priority(7)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);
		log.info("OSParty started (API {})", PartyApiClient.apiBaseUrl());
	}

	@Override
	protected void shutDown()
	{
		liveParty.leave();
		liveParty.unregister();
		keyManager.unregisterKeyListener(pingHotkeyListener);
		mouseManager.unregisterMouseListener(pingMouseListener);
		pingHotkeyDown = false;
		if (panel != null)
		{
			panel.dispose();
		}
		partySocket.stop();
		clientToolbar.removeNavigation(navButton);
		overlayManager.remove(applicantOverlay);
		overlayManager.remove(fcRequestOverlay);
		overlayManager.remove(readyCheckOverlay);
		overlayManager.remove(tilePingOverlay);
		overlayManager.remove(defenceOverlay);
		overlayManager.remove(playerMarkerOverlay);
		if (defenceBox != null)
		{
			infoBoxManager.removeInfoBox(defenceBox);
			defenceBox = null;
		}
		defenceTracker.reset();
		if (worldPinger != null)
		{
			worldPinger.shutdown();
			worldPinger = null;
		}
		applicantOverlay = null;
		fcRequestOverlay = null;
		readyCheckOverlay = null;
		tilePingOverlay = null;
		defenceOverlay = null;
		playerMarkerOverlay = null;
		panel = null;
		navButton = null;
		playerName = null;
		accountHash = -1L;
		partyStore.close();
	}

	@Subscribe
	public void onConfigChanged(net.runelite.client.events.ConfigChanged event)
	{
		if (OSPartyConfig.GROUP.equals(event.getGroup())
			&& "showDiscordBadges".equals(event.getKey()) && panel != null)
		{
			SwingUtilities.invokeLater(panel::refreshDiscordBadgeViews);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// The local player (and its name) is usually not yet available at the
		// LOGGED_IN event, so capturing it here is unreliable; we instead read
		// it each tick (see onGameTick) and only clear it on logout here.
		// On a real logout (not a world hop), tell the party we've gone offline so
		// our presence dot clears immediately instead of waiting to go stale.
		if (event.getGameState() == GameState.LOGIN_SCREEN && playerName != null
			&& liveParty.isConnected())
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
			quickHopTarget = null;
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

		// Once per login: offer to resume a party we were hosting before a restart.
		if (playerName != null && !rejoinChecked)
		{
			rejoinChecked = true;
			attemptRejoin(playerName);
		}

		world = client.getWorld();
		mapRegions = client.getMapRegions();
		accountType = client.getAccountType();
		accountHash = client.getAccountHash();

		FriendsChatManager fcm = client.getFriendsChatManager();
		friendsChatOwner = fcm != null ? fcm.getOwner() : null;

		// Capture the full friends list for friends-first sorting in the Search panel.
		net.runelite.api.NameableContainer<net.runelite.api.Friend> friendContainer = client.getFriendContainer();
		if (friendContainer != null)
		{
			java.util.Set<String> names = new java.util.HashSet<>(friendContainer.getCount() * 2);
			for (int i = 0; i < friendContainer.getCount(); i++)
			{
				net.runelite.api.Friend f = friendContainer.getMembers()[i];
				if (f != null && f.getName() != null)
				{
					names.add(f.getName().replace('\u00A0', ' ').trim().toLowerCase());
				}
			}
			friendNames = java.util.Collections.unmodifiableSet(names);
		}

		// Accumulate the CoX raid layout each tick as the player explores (a single
		// scan can't see the whole raid), so it keeps filling in and updating.
		coxRaidScanner.update();
		coxLayout = coxRaidScanner.layout();

		// Drive any in-progress world hop (needs the client thread).
		processQuickHop();

		// Show the next in-game applicant prompt if the chatbox is free.
		drainApplicantPrompts();

		// Push pending host state / our own live snapshot (client thread).
		liveParty.tick();

		// Apply this tick's defence-draining specs and clear dead targets.
		defenceTracker.onGameTick();
		updateDefenceInfoBox();
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
	public void onSpecialCounterUpdate(SpecialCounterUpdate event)
	{
		// Fired by RuneLite's Special Attack Counter for our own and party members'
		// special attacks; the tracker turns the draining ones into a defence figure.
		defenceTracker.queue(event);
	}

	/** Enable a sibling RuneLite plugin by display name (used for a hard dependency). */
	private void enablePluginByName(String name)
	{
		try
		{
			for (Plugin plugin : pluginManager.getPlugins())
			{
				if (name.equals(plugin.getName()))
				{
					if (!pluginManager.isPluginEnabled(plugin))
					{
						pluginManager.setPluginEnabled(plugin, true);
						pluginManager.startPlugin(plugin);
					}
					return;
				}
			}
		}
		catch (PluginInstantiationException e)
		{
			log.warn("OSParty: could not enable '{}' plugin", name, e);
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		// Inventory or equipment changed — re-broadcast our loadout next tick.
		liveParty.markLocalDirty();
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		liveParty.markLocalDirty();
	}

	@Subscribe
	public void onPlayerUpdate(PlayerUpdate event)
	{
		liveParty.onPlayerUpdate(event);
	}

	@Subscribe
	public void onPartyStateMessage(PartyStateMessage event)
	{
		liveParty.onPartyState(event);
	}

	@Subscribe
	public void onMemberCommand(MemberCommand event)
	{
		// Check before handling: onMemberCommand makes us leave, after which we're no
		// longer the local member.
		boolean kickedUs = event.getAction() == MemberCommand.Action.KICK
			&& liveParty.isForLocalMember(event.getTargetMemberId());
		liveParty.onMemberCommand(event);
		if (kickedUs && config.kickSound())
		{
			playResourceSound("/net/osparty/sounds/kicked.wav");
		}
	}

	@Subscribe
	public void onHostTransferMessage(HostTransferMessage event)
	{
		panel.onHostTransferMessage(event);
	}

	@Subscribe
	public void onPingMessage(PingMessage event)
	{
		liveParty.onPing(event);
	}

	/**
	 * Read the tile currently under the cursor (client thread) and broadcast a
	 * ping there in the configured colour. Called from the AWT mouse listener.
	 */
	private void pingHoveredTile()
	{
		clientThread.invoke(() -> {
			Tile tile = client.getSelectedSceneTile();
			if (tile == null)
			{
				return;
			}
			WorldPoint point = tile.getWorldLocation();
			if (point != null)
			{
				liveParty.sendPing(point, config.pingColor());
			}
		});
	}

	@Subscribe
	public void onReadyCheckMessage(ReadyCheckMessage event)
	{
		liveParty.onReadyCheck(event);
	}

	@Subscribe
	public void onFcRequestMessage(FcRequestMessage event)
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
		if (fcRequestOverlay != null)
		{
			fcRequestOverlay.show(host, title, detail, config.fcRequestDurationSecs() * 1000L);
			gameMessage(host + " — " + detail);
			if (config.friendsChatRequestSound())
			{
				playResourceSound("/net/osparty/sounds/friendschatsound.wav");
			}
		}
	}

	@Subscribe
	public void onUserJoin(UserJoin event)
	{
		liveParty.onPeerJoined(event.getMemberId());
	}

	@Subscribe
	public void onUserPart(UserPart event)
	{
		liveParty.onPeerLeft(event.getMemberId());
	}

	@Schedule(period = 15, unit = ChronoUnit.MINUTES, asynchronous = true)
	public void refreshRuneWatch()
	{
		runeWatchService.refresh();
	}

	/** Look up an ad still hosted by us (survives a crash/restart for ~the ad TTL). */
	private void attemptRejoin(String rsn)
	{
		if (liveParty.isConnected())
		{
			return; // already in a party
		}
		apiClient.getPartyByHost(rsn,
			party -> SwingUtilities.invokeLater(() -> onRejoinFound(party)),
			error -> { /* no party for this host - normal, nothing to do */ });
	}

	private void onRejoinFound(Party party)
	{
		if (panel == null || party == null)
		{
			return;
		}
		panel.resumeHostedParty(party);
		Activity activity = Activity.fromId(party.getActivity());
		String name = activity != null ? activity.getDisplayName() : party.getActivity();
		gameMessage("Rejoined your " + name + " party - disband it from the OSParty panel if you're done.");
	}

	/**
	 * @return the currently logged in player's name, or {@code null} if not
	 * logged in. Safe to call from the EDT. Callers use a null result as the
	 * "logged out" signal (it gates hosting/applying), so this stays strictly the
	 * in-game name; for pre-login identity use {@link #getSelfName()}.
	 */
	public String getPlayerName()
	{
		return playerName;
	}

	/**
	 * The local player's name for <em>identity</em> matching (isSelf, favourites, blocks), which we can
	 * know before login: once in-game it's {@link #getPlayerName()}, otherwise the Jagex-launcher display
	 * name of the character about to play (the one on the "Play Now" button), via RuneLite's
	 * {@code getLauncherDisplayName()}. {@code null} only when logged out and not started from the Jagex
	 * launcher. Unlike {@link #getPlayerName()} this is not a "logged in?" signal. Safe from the EDT.
	 */
	public String getSelfName()
	{
		return playerName != null ? playerName : client.getLauncherDisplayName();
	}

	/** @return owner of the friends chat the player is in, or null. Safe from the EDT. */
	public String getFriendsChatOwner()
	{
		return friendsChatOwner;
	}

	/** @return the current world, or 0 when not logged in. Safe from the EDT. */
	public int getCurrentWorld()
	{
		return world;
	}

	/** @return the currently loaded map regions, or null when not logged in. Safe from the EDT. */
	public int[] getMapRegions()
	{
		return mapRegions;
	}

	/** @return the current CoX raid layout string, or null when not in a raid. Safe from the EDT. */
	public String getCoxLayout()
	{
		return coxLayout;
	}

	/**
	 * Resolve a world number to its geographic {@link WorldRegion} (for the flag
	 * shown on a party ad), or null when the world list isn't loaded yet or the
	 * world is unknown. Reads the cached world list, so it's safe from the EDT.
	 */
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
		net.runelite.http.api.worlds.World world = worlds.findWorld(worldNum);
		return world != null ? world.getRegion() : null;
	}

	/**
	 * Resolve a world number to its server hostname (for TCP-ping latency). Returns
	 * null when the world list isn't loaded yet or the world number is unknown.
	 * Reads the cached world list, so it's safe from the EDT.
	 */
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
		net.runelite.http.api.worlds.World world = worlds.findWorld(worldNum);
		return world != null ? world.getAddress() : null;
	}

	/** @return the set of normalised (lowercase) friend names. Safe from any thread. */
	public Set<String> getFriendNames()
	{
		return friendNames;
	}

	/** @return the local player's account type, or null when not logged in. Safe from the EDT. */
	public AccountType getAccountType()
	{
		return accountType;
	}

	/** @return the local player's account hash, or {@code -1} when not logged in. Safe from the EDT. */
	public long getAccountHash()
	{
		return accountHash;
	}

	/**
	 * Hop the client to {@code worldNum} (a party member's world). Safe to call
	 * from the EDT. At the login screen we can switch directly; while logged in the
	 * actual hop is driven over game ticks (see {@link #processQuickHop()}), which
	 * - following RuneLite's World Hopper - opens the world switcher first so
	 * {@code hopToWorld} works from anywhere without the player opening it manually.
	 */
	public void hopTo(int worldNum)
	{
		if (worldNum <= 0 || client.getWorld() == worldNum)
		{
			return;
		}

		WorldResult worlds = worldService.getWorlds();
		net.runelite.http.api.worlds.World target = worlds != null ? worlds.findWorld(worldNum) : null;
		if (target == null)
		{
			gameMessage("Could not find world " + worldNum + " to hop to.");
			return;
		}

		if (client.getGameState() == GameState.LOGIN_SCREEN)
		{
			clientThread.invoke(() -> client.changeWorld(toRsWorld(target)));
			return;
		}

		// Queue it; the game-tick handler opens the switcher then hops.
		quickHopAttempts = 0;
		quickHopTarget = target;
	}

	/**
	 * Progress a queued world hop. RuneLite's {@code hopToWorld} only works once the
	 * world-switcher widget has been built, so we open it first and retry over a few
	 * ticks until it exists, then hop. Runs on the client thread (from GameTick).
	 */
	private void processQuickHop()
	{
		net.runelite.http.api.worlds.World target = quickHopTarget;
		if (target == null)
		{
			return;
		}
		if (client.getWorld() == target.getId() || client.getGameState() != GameState.LOGGED_IN)
		{
			quickHopTarget = null;
			return;
		}

		if (client.getWidget(ComponentID.WORLD_SWITCHER_WORLD_LIST) == null)
		{
			// Switcher not built yet - open it and try again next tick.
			client.openWorldHopper();
			if (++quickHopAttempts >= QUICK_HOP_MAX_ATTEMPTS)
			{
				quickHopTarget = null;
				gameMessage("Could not open the world switcher to hop to world " + target.getId() + ".");
			}
			return;
		}

		client.hopToWorld(toRsWorld(target));
		quickHopTarget = null;
	}

	private World toRsWorld(net.runelite.http.api.worlds.World source)
	{
		World rsWorld = client.createWorld();
		rsWorld.setActivity(source.getActivity());
		rsWorld.setAddress(source.getAddress());
		rsWorld.setId(source.getId());
		rsWorld.setPlayerCount(source.getPlayers());
		rsWorld.setLocation(source.getLocation());
		rsWorld.setTypes(WorldUtil.toWorldTypes(source.getTypes()));
		return rsWorld;
	}

	@Override
	public void setPendingApplicants(java.util.List<Applicant> applicants, Activity activity)
	{
		applicantOverlay.setApplicants(applicants, activity);
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

		gameMessage(applicant.getName() + " applied to your " + activity.getDisplayName()
			+ " party - " + applicantSummary(applicant, activity) + ". Accept or decline in the side panel.");

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
		gameMessage("Auto-declined " + applicant.getName() + " - on your block list.");
	}

	/**
	 * A compact, information-dense one-liner about an applicant: combat level,
	 * activity killcount (+ hard-mode), personal best, total level, account type
	 * and a RuneWatch flag. Used in both the chat ping and the in-game prompt.
	 */
	private String applicantSummary(Applicant applicant, Activity activity)
	{
		// Blocked applicants: surface only the block status, not their stats. The full alert goes
		// out as an in-game notification (see announceApplicant), which doesn't scroll away.
		if (applicant.isBlocked())
		{
			return "on your block list";
		}

		java.util.List<String> parts = new java.util.ArrayList<>();
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

		String tag = net.osparty.model.AccountTypes.tag(
			net.osparty.model.AccountTypes.fromName(applicant.getAccountType()));
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

	/**
	 * Host: show the next queued applicant as an in-game chatbox Accept/Decline
	 * menu, one at a time and only when the chatbox is free. Runs on the client
	 * thread (from GameTick).
	 */
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
					gameMessage("Party is full - couldn't accept " + applicant.getName() + ".");
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
		// If this applicant was resolved elsewhere (e.g. the side panel) while their
		// in-game prompt is still open, close it so it can't be actioned twice.
		dismissPromptFor(applicant.getMemberId());
		gameMessage((accepted ? "Accepted " : "Declined ") + applicant.getName()
			+ " for your " + activity.getDisplayName() + " party.");
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
			playResourceSound("/net/osparty/sounds/ready.wav");
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
	 * Post a game message to the in-game chatbox on the client thread. No-op
	 * when not logged in (the chatbox only exists in-game).
	 */
	private void gameMessage(String message)
	{
		if (!config.chatboxNotifications())
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
}
