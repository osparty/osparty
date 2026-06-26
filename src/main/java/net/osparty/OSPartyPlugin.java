package net.osparty;

import net.osparty.api.PartyApiClient;
import net.osparty.api.PartyService;
import net.osparty.model.Activity;
import net.osparty.model.Applicant;
import net.osparty.model.Party;
import net.osparty.party.FcRequestMessage;
import net.osparty.party.LiveParty;
import net.osparty.party.MemberCommand;
import net.osparty.party.PartyStateMessage;
import net.osparty.party.PlayerUpdate;
import net.osparty.party.ReadyCheckMessage;
import net.osparty.runewatch.RuneWatchService;
import net.osparty.ui.OSPartyPanel;
import net.osparty.ui.ApplicantOverlay;
import net.osparty.ui.FcRequestOverlay;
import net.osparty.ui.ReadyCheckOverlay;
import com.google.inject.Provides;
import java.awt.Color;
import java.time.temporal.ChronoUnit;
import javax.inject.Inject;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.FriendsChatManager;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.vars.AccountType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.api.World;
import net.runelite.api.widgets.ComponentID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.game.WorldService;
import net.runelite.client.util.WorldUtil;
import net.runelite.http.api.worlds.WorldResult;
import net.runelite.client.party.events.UserJoin;
import net.runelite.client.party.events.UserPart;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
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
	private ConfigManager configManager;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private WorldService worldService;

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	@Inject
	private OSPartyConfig config;

	/** Config key storing whether the user has consented to data sharing. */
	private static final String CONSENT_KEY = "dataShareConsent";

	private OSPartyPanel panel;
	private NavigationButton navButton;
	private ApplicantOverlay applicantOverlay;
	private FcRequestOverlay fcRequestOverlay;
	private ReadyCheckOverlay readyCheckOverlay;

	/**
	 * Last known logged in player name. Updated on the client thread and read
	 * (volatile) from the EDT by the panel.
	 */
	private volatile String playerName;

	/** Owner (name) of the friends chat the player is currently in, or null. */
	private volatile String friendsChatOwner;
	/** Current world, or 0 when not logged in. */
	private volatile int world;
	/** Currently loaded map regions (for location-aware activity suggestions). */
	private volatile int[] mapRegions;
	/** Local player's account type, or null when not logged in. */
	private volatile AccountType accountType;
	/** Whether we've checked for a resumable hosted party since this login. */
	private boolean rejoinChecked;

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

	/** An applicant queued for an in-game accept/decline prompt. */
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

		applicantOverlay = new ApplicantOverlay();
		overlayManager.add(applicantOverlay);

		fcRequestOverlay = new FcRequestOverlay();
		overlayManager.add(fcRequestOverlay);

		readyCheckOverlay = new ReadyCheckOverlay(liveParty);
		overlayManager.add(readyCheckOverlay);

		// Ready-check notifications: a chat ping when one starts/expires, and a chat
		// line plus optional sound when everyone is ready.
		liveParty.setOnReadyCheckStarted(starter ->
			gameMessage(starter + " started a ready check - ready up in the OSParty panel."));
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

		panel = new OSPartyPanel(partyService, config, this::getPlayerName, this,
			this::getFriendsChatOwner, this::getCurrentWorld, itemManager, liveParty, runeWatchService,
			this::getAccountType, killcountService, skillIconManager, this::hopTo, this::getMapRegions);

		navButton = NavigationButton.builder()
			.tooltip("OSParty")
			.icon(ImageUtil.loadImageResource(getClass(), "panel_icon.png"))
			.priority(7)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);
		log.info("OSParty started (API {})", config.apiBaseUrl());

		// First activation: require explicit consent before any data leaves the
		// client (party ads are posted to a public API). Persisted, so shown once.
		if (!hasConsent())
		{
			SwingUtilities.invokeLater(this::promptForConsent);
		}
	}

	private boolean hasConsent()
	{
		return Boolean.TRUE.equals(
			configManager.getConfiguration(OSPartyConfig.GROUP, CONSENT_KEY, Boolean.class));
	}

	/**
	 * Ask the user to consent to sharing party data with the public API. On
	 * decline the plugin disables itself (it can't function without it). Runs on
	 * the EDT; the dialog is modal so nothing is sent before they answer.
	 */
	private void promptForConsent()
	{
		String message = "<html><body style='width: 330px'>"
			+ "<b>OSParty shares data with a public service.</b><br><br>"
			+ "To advertise and find parties, OSParty sends your <b>player name</b> and the "
			+ "<b>party details you enter</b> (activity, world, requirements and a join "
			+ "passphrase) to a public party-listing API (<code>api.osparty.net</code>), and "
			+ "exchanges live party data (gear, inventory, stats) over RuneLite's "
			+ "peer-to-peer party network with people you group with.<br><br>"
			+ "No data is sent until you create or join a party. Do you consent?"
			+ "</body></html>";

		int choice = JOptionPane.showConfirmDialog(panel, message,
			"OSParty — data sharing consent", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);

		if (choice == JOptionPane.YES_OPTION)
		{
			configManager.setConfiguration(OSPartyConfig.GROUP, CONSENT_KEY, true);
			log.info("OSParty data-sharing consent granted.");
		}
		else
		{
			log.info("OSParty data-sharing consent declined — disabling the plugin.");
			disableSelf();
		}
	}

	/** Turn the plugin off (used when the user declines the data-sharing consent). */
	private void disableSelf()
	{
		try
		{
			pluginManager.setPluginEnabled(this, false);
			pluginManager.stopPlugin(this);
		}
		catch (PluginInstantiationException e)
		{
			log.warn("Failed to disable OSParty after declined consent", e);
		}
	}

	@Override
	protected void shutDown()
	{
		liveParty.leave();
		liveParty.unregister();
		clientToolbar.removeNavigation(navButton);
		overlayManager.remove(applicantOverlay);
		overlayManager.remove(fcRequestOverlay);
		overlayManager.remove(readyCheckOverlay);
		applicantOverlay = null;
		fcRequestOverlay = null;
		readyCheckOverlay = null;
		panel = null;
		navButton = null;
		playerName = null;
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

		FriendsChatManager fcm = client.getFriendsChatManager();
		friendsChatOwner = fcm != null ? fcm.getOwner() : null;

		// Drive any in-progress world hop (needs the client thread).
		processQuickHop();

		// Show the next in-game applicant prompt if the chatbox is free.
		drainApplicantPrompts();

		// Push pending host state / our own live snapshot (client thread).
		liveParty.tick();
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
	public void onReadyCheckMessage(ReadyCheckMessage event)
	{
		liveParty.onReadyCheck(event);
	}

	@Subscribe
	public void onFcRequestMessage(FcRequestMessage event)
	{
		// Only show the popup if this request is aimed at us.
		if (!liveParty.isForLocalMember(event.getTargetMemberId()))
		{
			return;
		}
		String fc = event.getFriendsChat();
		if (fcRequestOverlay != null && fc != null)
		{
			fcRequestOverlay.show(event.getHostName(), fc, 3000);
			gameMessage((event.getHostName() != null ? event.getHostName() : "The host")
				+ " asks you to join the friends chat \"" + fc + "\".");
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
	 * logged in. Safe to call from the EDT.
	 */
	public String getPlayerName()
	{
		return playerName;
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

	/** @return the local player's account type, or null when not logged in. Safe from the EDT. */
	public AccountType getAccountType()
	{
		return accountType;
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

	/** Build a client {@link World} from the world-list entry. */
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
		StringBuilder line = new StringBuilder()
			.append(applicant.getName())
			.append(" applied to your ").append(activity.getDisplayName())
			.append(" party (cb ").append(applicant.getCombatLevel());
		if (applicant.getKillCount() >= 0)
		{
			line.append(", KC ").append(applicant.getKillCount());
			if (activity.hasHardMode() && applicant.getHardModeKillCount() >= 0)
			{
				line.append(", ").append(activity.getHardModeLabel())
					.append(' ').append(applicant.getHardModeKillCount());
			}
		}
		line.append("). Accept or decline in the side panel.");

		gameMessage(line.toString());

		// Also offer an in-game chatbox Accept/Decline (driven on the game tick).
		if (config.inGamePrompts() && applicant.getMemberId() != 0)
		{
			promptQueue.add(new PendingPrompt(applicant, activity));
		}
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

		StringBuilder title = new StringBuilder(applicant.getName() + " wants to join");
		title.append(" (cb ").append(applicant.getCombatLevel());
		if (applicant.getKillCount() >= 0)
		{
			title.append(", ").append(activity.getDisplayName()).append(" KC ").append(applicant.getKillCount());
		}
		title.append(')');

		promptOpen = true;
		chatboxPanelManager.openTextMenuInput(title.toString())
			.option("Accept", () -> {
				promptOpen = false;
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
				liveParty.reject(applicant.getMemberId());
				announceResolved(applicant, activity, false);
			})
			.option("Decide later", () -> promptOpen = false)
			.onClose(() -> promptOpen = false)
			.build();
	}

	@Override
	public void announceResolved(Applicant applicant, Activity activity, boolean accepted)
	{
		gameMessage((accepted ? "Accepted " : "Declined ") + applicant.getName()
			+ " for your " + activity.getDisplayName() + " party.");
	}

	/** Play the bundled ready-check sound when everyone is ready (if enabled). */
	private void playReadySound()
	{
		if (config.readyCheckSound())
		{
			playResourceSound("/net/osparty/sounds/ready.wav");
		}
	}

	/** Play a bundled .wav sound resource (e.g. /net/osparty/sounds/kicked.wav). */
	private void playResourceSound(String resource)
	{
		new Thread(() -> {
			try (java.io.InputStream raw = getClass().getResourceAsStream(resource))
			{
				if (raw == null)
				{
					log.warn("OSParty: sound resource not found '{}'", resource);
					return;
				}
				try (javax.sound.sampled.AudioInputStream in =
					javax.sound.sampled.AudioSystem.getAudioInputStream(new java.io.BufferedInputStream(raw)))
				{
					playClip(in);
				}
			}
			catch (Exception e)
			{
				log.warn("OSParty: failed to play sound '{}'", resource, e);
			}
		}, "osparty-sound").start();
	}

	/**
	 * Open and start a clip for the given stream. {@code Clip.open} buffers all the
	 * audio, so the caller may close the source stream afterwards; the line listener
	 * frees the clip once playback finishes.
	 */
	private void playClip(javax.sound.sampled.AudioInputStream in) throws Exception
	{
		javax.sound.sampled.Clip clip = javax.sound.sampled.AudioSystem.getClip();
		clip.addLineListener(event -> {
			if (event.getType() == javax.sound.sampled.LineEvent.Type.STOP)
			{
				clip.close();
			}
		});
		clip.open(in);
		clip.start();
	}

	/**
	 * Post a game message to the in-game chatbox on the client thread. No-op
	 * when not logged in (the chatbox only exists in-game).
	 */
	private void gameMessage(String message)
	{
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
