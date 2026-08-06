package net.osparty.ui;

import net.osparty.service.BlockListService;
import net.osparty.service.FavoritesService;
import net.osparty.tools.HostApplicationHandler;
import net.osparty.service.KillcountService;
import net.osparty.OSPartyConfig;
import net.osparty.api.DiscordLinkStatus;
import net.osparty.api.BoardService;
import net.osparty.service.PartyHistoryService;
import net.osparty.model.Advertisement;
import net.osparty.party.HostTransferEvent;
import net.osparty.party.LivePartyBackend;
import com.google.gson.Gson;
import net.osparty.service.RuneWatchService;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.runelite.api.ItemID;
import net.runelite.api.SpriteID;
import net.runelite.api.vars.AccountType;
import net.runelite.http.api.worlds.WorldRegion;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.Timer;
import javax.swing.JPanel;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import net.osparty.tools.WorldPinger;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

/** Root side-panel: tabs share one {@link PartyState} so the one-party-at-a-time rule stays in sync. */
public class OSPartyPanel extends PluginPanel
{
	// PluginHub's build omits runelite-plugin.properties; keep in step with it each release.
	// Also sent on every socket connect as X-OSParty-Client, so the service can see which versions are
	// actually deployed rather than infer it: released plugins update on their own schedule and there is
	// no way to ask them.
	public static final String VERSION = "1.0.52";
	private static final String GITHUB_URL = "https://github.com/osparty/osparty";
	private static final String DISCORD_URL = "https://discord.gg/EtMRxTHXWJ";
	/** How long an unanswered invite banner stays on the panel, and how often we sweep for expired ones. */
	private static final long INVITE_TTL_MS = 5 * 60_000;
	private static final int INVITE_SWEEP_MS = 30_000;
	/** How long to wait before re-asking for link status after the server didn't answer. */
	private static final long LINK_RETRY_MS = 30_000;

	/** Green "party running" underline for the Party tab, distinct from the orange selection underline. */
	private static final Color PARTY_ACTIVE_COLOR = new Color(0x4C, 0xAF, 0x50);
	/** Party-active underline border; matches the selected border's insets so swapping never resizes the tab. */
	private static final Border PARTY_ACTIVE_BORDER = BorderFactory.createCompoundBorder(
		BorderFactory.createMatteBorder(0, 0, 1, 0, PARTY_ACTIVE_COLOR),
		BorderFactory.createEmptyBorder(5, 10, 4, 10));

	/** Greyed-out Discord glyph shown next to "Link Discord" (the linked state is username-only, no icon). */
	private static final ImageIcon DISCORD_LINK_ICON_GREY = loadDiscordIconGrey();

	private static ImageIcon loadDiscordIconGrey()
	{
		BufferedImage img = ImageUtil.loadImageResource(OSPartyPanel.class, "/net/osparty/icons/discord.png");
		if (img == null)
		{
			return null;
		}
		return new ImageIcon(ImageUtil.grayscaleImage(ImageUtil.resizeImage(img, 14, 14)));
	}

	private final PartyState partyState;
	private final LivePartyBackend liveParty;
	private final BoardService boardService;
	private final Runnable openDeviceManager;
	/** The backend party we're in (host or member), mirrored for off-EDT reads (invite menu); null when none. */
	private volatile Advertisement contextAd;
	/** Run when the side panel is opened (used to stop the sidebar invite blink). */
	private volatile Runnable onActivated;
	/** Run when the side panel is closed (used to restore the normal sidebar icon). */
	private volatile Runnable onDeactivated;
	/** Stacked invite banners shown at the top of the panel; keyed by backend party id. EDT only. */
	private final JPanel invitePanel = buildInvitePanel();
	private final java.util.Map<String, JPanel> inviteBanners = new java.util.HashMap<>();
	/** When each banner stops being offered, so an ignored invite doesn't sit there for the session. */
	private final java.util.Map<String, Long> inviteExpiry = new java.util.HashMap<>();
	private Timer inviteSweepTimer;
	private Consumer<net.osparty.api.PartyInvite> onInviteAccept;
	private Consumer<net.osparty.api.PartyInvite> onInviteDecline;
	private final HostTransferHandler hostTransferHandler;
	private final LongSupplier accountHashSupplier;
	/** Writes a line to the game chatbox (the plugin's own notifier). */
	private final Consumer<String> gameMessage;
	private final JLabel activeUsersLabel = new JLabel();
	private final JButton discordLinkButton = new JButton();
	private Timer presenceTimer;
	private Timer linkPollTimer;
	/**
	 * Keeps the resume marker for a joined party fresh. A timer rather than the live-party listener: a
	 * quiet party can go minutes without a roster or state change, and the marker would age as though we
	 * had left it.
	 */
	private final Timer membershipTimer = new Timer(5000, e -> rememberMembership());
	/** Last accountHash we queried link status for, so we only re-query when the logged-in account changes. */
	private long lastLinkQueryHash = Long.MIN_VALUE;
	/** Epoch millis before which a failed link query isn't retried. */
	private long linkRetryAfter;
	/** Whether the local account is currently Discord-linked; gates the Party tab's voice buttons. */
	private volatile boolean discordLinked;
	/** The account's server-side badge-privacy preference, mirrored from the last link status. */
	private volatile boolean badgesVisible = true;
	private final SearchPanel searchPanel;
	private final FavoritesPanel favoritesPanel;
	private final BlockedPanel blockedPanel;
	private final CreatePanel createPanel;
	private final PartyPanel partyPanel;
	private final HistoryPanel historyPanel;
	private final PartyHistoryService historyService;
	private final MaterialTabGroup tabGroup;
	private final MaterialTab searchTab;
	private final MaterialTab createTab;
	private final MaterialTab favoritesTab;
	private final MaterialTab blockedTab;
	private final MaterialTab partyTab;
	private final MaterialTab historyTab;
	private boolean wasInParty;
	/** Id of the party currently logged in history, so we can stamp it ended once we leave it. */
	private String currentHistoryPartyId;
	/** Whether the current party has a history row yet — false while our application is still pending. */
	private boolean historyRecorded;
	/** Whether the tab bar is in the in-party layout (Party shown, Create hidden). */
	private boolean inPartyTabLayout;
	/** Whether the host is editing their party (the create form is shown alongside the roster). */
	private boolean editing;

	public OSPartyPanel(BoardService boardService, OSPartyConfig config, Supplier<String> playerNameSupplier,
		HostApplicationHandler hostApplicationHandler, Supplier<String> friendsChatOwnerSupplier,
		IntSupplier worldSupplier, ItemManager itemManager, LivePartyBackend liveParty,
		RuneWatchService runeWatchService, Supplier<AccountType> accountTypeSupplier,
		KillcountService killcountService, SkillIconManager skillIconManager,
		Supplier<int[]> mapRegionsSupplier, IntFunction<WorldRegion> worldRegionResolver,
		Supplier<String> coxLayoutSupplier, ConfigManager configManager, Gson gson,
		WorldPinger worldPinger, IntFunction<String> worldAddressResolver,
		Supplier<Set<String>> friendNamesSupplier, FavoritesService favoritesService,
		BlockListService blockListService, LongSupplier accountHashSupplier,
		SpriteManager spriteManager, PartyHistoryService historyService, Consumer<String> gameMessage,
		Runnable openDeviceManager)
	{
		super(false);

		this.liveParty = liveParty;
		this.boardService = boardService;
		this.accountHashSupplier = accountHashSupplier;
		this.gameMessage = gameMessage;
		this.historyService = historyService;
		this.openDeviceManager = openDeviceManager;
		this.partyState = new PartyState(configManager);
		this.hostTransferHandler = new HostTransferHandler(liveParty, boardService, partyState,
			playerNameSupplier, accountTypeSupplier, gameMessage);

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		// super(false) skips PluginPanel's default border, so add our own padding.
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		searchPanel = new SearchPanel(boardService, playerNameSupplier, partyState, liveParty,
			accountTypeSupplier, mapRegionsSupplier, worldRegionResolver, killcountService, configManager,
			worldPinger, worldAddressResolver, friendNamesSupplier, favoritesService, blockListService,
			spriteManager, config);
		favoritesPanel = new FavoritesPanel(boardService, playerNameSupplier, partyState,
			liveParty, accountTypeSupplier, killcountService, worldPinger, worldRegionResolver,
			worldAddressResolver, favoritesService, blockListService, friendNamesSupplier, spriteManager,
			config);
		blockedPanel = new BlockedPanel(blockListService);

		// Cross-notify: a favourite/block toggle in one tab refreshes the others.
		searchPanel.setOnFavoriteChanged(favoritesPanel::render);
		favoritesPanel.setOnFavoriteChanged(searchPanel::renderCurrent);
		searchPanel.setOnBlockChanged(() -> { favoritesPanel.render(); blockedPanel.render(); });
		favoritesPanel.setOnBlockChanged(() -> { searchPanel.renderCurrent(); blockedPanel.render(); });
		blockedPanel.setOnBlockChanged(() -> { searchPanel.renderCurrent(); favoritesPanel.render(); });
		createPanel = new CreatePanel(boardService, config, playerNameSupplier, partyState, liveParty,
			accountTypeSupplier, accountHashSupplier, mapRegionsSupplier, configManager, gson,
			killcountService, worldSupplier);
		createPanel.setJoinByCodeHandler(searchPanel::joinByCode);
		partyPanel = new PartyPanel(boardService,
			hostApplicationHandler, partyState, itemManager, liveParty, runeWatchService, killcountService,
			skillIconManager, worldSupplier, friendsChatOwnerSupplier, coxLayoutSupplier,
			config, configManager, favoritesService, blockListService, spriteManager,
			() -> discordLinked, this::startDiscordLink, accountHashSupplier, hostTransferHandler);
		historyPanel = new HistoryPanel(historyService, favoritesService, blockListService);
		// Favouriting/blocking a player from a history roster refreshes the affected tabs.
		historyPanel.setOnFlagChanged(() ->
		{
			searchPanel.renderCurrent();
			favoritesPanel.render();
			blockedPanel.render();
		});

		// Host edit flow: "Edit party" opens the create form in edit mode; saving returns to the roster.
		partyPanel.setOnEditParty(this::openEditParty);
		createPanel.setOnEditDone(this::finishEditParty);

		JPanel display = new JPanel(new BorderLayout());
		display.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane createScroll = new JScrollPane(createPanel,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		createScroll.setBorder(BorderFactory.createEmptyBorder());
		createScroll.getVerticalScrollBar().setUnitIncrement(16);
		createScroll.setBackground(ColorScheme.DARK_GRAY_COLOR);

		tabGroup = new MaterialTabGroup(display);
		// Icon tabs (text labels overflow the sidebar): drawn fallback now, OSRS sprite once loaded.
		searchTab = new MaterialTab(TabIcons.SEARCH, tabGroup, searchPanel);
		searchTab.setToolTipText("Search");
		createTab = new MaterialTab(TabIcons.PARTY, tabGroup, createScroll);
		createTab.setToolTipText("Party");
		favoritesTab = new MaterialTab(TabIcons.FAVORITES, tabGroup, favoritesPanel);
		favoritesTab.setToolTipText("Favorites");
		blockedTab = new MaterialTab(TabIcons.BLOCK, tabGroup, blockedPanel);
		blockedTab.setToolTipText("Blocked");
		partyTab = new MaterialTab(TabIcons.PARTY, tabGroup, partyPanel)
		{
			@Override
			public void unselect()
			{
				super.unselect();
				// Keep the green "in a party" underline even when another tab is selected.
				if (partyState.isInParty())
				{
					setBorder(PARTY_ACTIVE_BORDER);
				}
				// Repaint the whole bar so the underline doesn't lag a frame behind the selection.
				if (tabGroup != null)
				{
					tabGroup.repaint();
				}
			}
		};
		partyTab.setToolTipText("Party");
		historyTab = new MaterialTab(TabIcons.HISTORY, tabGroup, historyPanel);
		historyTab.setToolTipText("History");

		// Upgrade tabs to OSRS sprites; Party keeps its bundled PNG (no clean square sprite exists).
		applyTabSprite(spriteManager, SpriteID.GE_SEARCH, searchTab::setIcon);
		applyTabSprite(spriteManager, SpriteID.WORLD_SWITCHER_STAR_MEMBERS, favoritesTab::setIcon);
		applyTabSprite(spriteManager, SpriteID.TAB_IGNORES, blockedTab::setIcon);
		applyTabItem(itemManager, ItemID.HOURGLASS, historyTab::setIcon);

		// Register all tabs (needed for select()); rebuildTabs lays out the idle bar.
		tabGroup.addTab(searchTab);
		tabGroup.addTab(createTab);
		tabGroup.addTab(favoritesTab);
		tabGroup.addTab(blockedTab);
		tabGroup.addTab(partyTab);
		tabGroup.addTab(historyTab);
		rebuildTabs(false);
		tabGroup.select(searchTab);

		JPanel north = new JPanel(new BorderLayout());
		north.setBackground(ColorScheme.DARK_GRAY_COLOR);
		north.add(invitePanel, BorderLayout.NORTH);
		north.add(tabGroup, BorderLayout.CENTER);

		add(north, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);
		add(buildFooter(), BorderLayout.SOUTH);

		partyState.addListener(this::onPartyStateChanged);
		// Live joins/leaves arrive off-EDT; marshal back before touching history/Swing.
		liveParty.addListener(() -> SwingUtilities.invokeLater(this::syncHistoryRoster));
		membershipTimer.start();
	}


	private JPanel buildFooter()
	{
		// Row 1: community links. Row 2: online (left) | link state (centre) | version (right).
		JPanel footer = new JPanel(new GridLayout(2, 1, 0, 2));
		footer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		footer.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		JPanel row1 = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
		row1.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row1.add(linkButton("GitHub", "Open the OSParty GitHub page", "github.png", GITHUB_URL));
		row1.add(linkButton("Discord", "Open the OSParty Discord", "discord.png", DISCORD_URL));
		row1.add(devicesButton());

		JPanel row2 = new JPanel(new BorderLayout());
		row2.setBackground(ColorScheme.DARK_GRAY_COLOR);

		activeUsersLabel.setHorizontalAlignment(SwingConstants.LEFT);
		activeUsersLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		activeUsersLabel.setFont(FontManager.getRunescapeSmallFont());
		activeUsersLabel.setToolTipText("Players currently using the plugin");
		row2.add(activeUsersLabel, BorderLayout.WEST);

		configureDiscordLinkButton();
		JPanel linkWrap = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
		linkWrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		linkWrap.add(discordLinkButton);
		row2.add(linkWrap, BorderLayout.CENTER);

		JLabel version = new JLabel("v" + VERSION);
		version.setHorizontalAlignment(SwingConstants.RIGHT);
		version.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		version.setFont(FontManager.getRunescapeSmallFont());
		row2.add(version, BorderLayout.EAST);

		footer.add(row1);
		footer.add(row2);
		updateActiveUsers();

		// Poll the cached online count onto the footer (no network); EDT timer, Swing-safe.
		presenceTimer = new Timer(3000, e -> updateActiveUsers());
		presenceTimer.start();

		return footer;
	}

	/** A borderless icon button opening {@code url}, falling back to a text label when the icon is missing. */
	/**
	 * Plain text rather than {@link #linkButton}'s icon style: this opens a dialog in-client, not a browser
	 * tab, and looking like the other two would suggest it does the same thing.
	 */
	private JButton devicesButton()
	{
		JButton button = new JButton("Devices");
		button.setToolTipText("See and manage the devices signed in to your OSParty account");
		button.setFocusPainted(false);
		button.setContentAreaFilled(false);
		button.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
		button.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setCursor(new Cursor(Cursor.HAND_CURSOR));
		button.addActionListener(e -> openDeviceManager.run());
		return button;
	}

	private JButton linkButton(String text, String tooltip, String iconFile, String url)
	{
		JButton button = new JButton();
		button.setToolTipText(tooltip);
		button.setFocusPainted(false);
		button.setBorder(BorderFactory.createEmptyBorder());
		button.setContentAreaFilled(false);
		button.setCursor(new Cursor(Cursor.HAND_CURSOR));
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/net/osparty/icons/" + iconFile);
		if (icon != null)
		{
			button.setIcon(new ImageIcon(ImageUtil.resizeImage(icon, 16, 16)));
		}
		else
		{
			button.setText(text);
			button.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			button.setFont(FontManager.getRunescapeSmallFont());
		}
		button.addActionListener(e -> LinkBrowser.browse(url));
		return button;
	}

	private void updateActiveUsers()
	{
		int online = boardService.onlineUserCount();
		activeUsersLabel.setText(online < 0 ? "" : online + " online");

		// Only re-query link status when the logged-in account changes, not every tick.
		long hash = accountHashSupplier.getAsLong();
		if (hash != lastLinkQueryHash && System.currentTimeMillis() >= linkRetryAfter)
		{
			lastLinkQueryHash = hash;
			refreshDiscordLinkStatus();
		}
	}

	private void configureDiscordLinkButton()
	{
		discordLinkButton.setFocusPainted(false);
		discordLinkButton.setBorder(BorderFactory.createEmptyBorder());
		discordLinkButton.setContentAreaFilled(false);
		discordLinkButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
		discordLinkButton.setFont(FontManager.getRunescapeSmallFont());
		discordLinkButton.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		applyLinkStatus(null);
		discordLinkButton.addActionListener(e -> startDiscordLink());
		// Right-click (when linked) offers Relink / Unlink.
		discordLinkButton.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				maybeShowLinkMenu(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				maybeShowLinkMenu(e);
			}
		});
	}

	private void maybeShowLinkMenu(MouseEvent e)
	{
		if (!e.isPopupTrigger() || !discordLinked)
		{
			return;
		}
		JPopupMenu menu = new JPopupMenu();
		JMenuItem relink = new JMenuItem("Relink");
		relink.addActionListener(a -> startDiscordLink());
		JMenuItem unlink = new JMenuItem("Unlink");
		unlink.addActionListener(a -> unlinkDiscord());
		// Server-side privacy: unticking strips this account's badges from ads for everyone.
		JCheckBoxMenuItem showBadges = new JCheckBoxMenuItem("Show my role badges to others", badgesVisible);
		showBadges.setToolTipText("When unticked, other players won't see your Discord role badges on your parties.");
		showBadges.addActionListener(a -> setBadgeVisibility(showBadges.isSelected()));
		menu.add(relink);
		menu.add(unlink);
		menu.addSeparator();
		menu.add(showBadges);
		menu.show(discordLinkButton, e.getX(), e.getY());
	}

	/** Push the badge-privacy preference server-side; the ack refreshes the cached link state. */
	private void setBadgeVisibility(boolean visible)
	{
		long hash = accountHashSupplier.getAsLong();
		if (hash == 0 || hash == -1)
		{
			return;
		}
		boardService.setBadgeVisibility(hash, visible,
			status -> SwingUtilities.invokeLater(() -> applyLinkStatus(status)));
	}

	/** Remove the Discord binding server-side and reset the local UI state. */
	private void unlinkDiscord()
	{
		long hash = accountHashSupplier.getAsLong();
		if (hash == 0 || hash == -1)
		{
			return;
		}
		boardService.unlinkDiscord(hash);
		applyLinkStatus(null); // reset the button (and re-gate the Party tab's voice buttons)
	}

	/** Ask the server whether the current account is linked and reflect it on the button. */
	private void refreshDiscordLinkStatus()
	{
		long hash = accountHashSupplier.getAsLong();
		if (hash == 0 || hash == -1)
		{
			applyLinkStatus(null);
			return;
		}
		boardService.fetchDiscordLink(hash, status -> SwingUtilities.invokeLater(() ->
		{
			if (status == null)
			{
				// Unknown, not "not linked": a server we couldn't reach must not latch the footer to
				// "Link Discord" (and the Party tab to "Authorize") for the rest of the session.
				lastLinkQueryHash = Long.MIN_VALUE;
				linkRetryAfter = System.currentTimeMillis() + LINK_RETRY_MS;
				return;
			}
			applyLinkStatus(status);
		}));
	}

	private void applyLinkStatus(DiscordLinkStatus status)
	{
		// Not logged in: hide the button entirely — no username, no link prompt.
		long hash = accountHashSupplier.getAsLong();
		boolean loggedIn = hash != 0 && hash != -1;
		discordLinkButton.setVisible(loggedIn);

		boolean linked = loggedIn && status != null && status.isLinked();
		if (status != null)
		{
			badgesVisible = status.isBadgesVisible();
		}
		discordLinkButton.setIconTextGap(4);
		if (linked)
		{
			// Verified: just the username, no icon.
			discordLinkButton.setText(status.getUsername());
			discordLinkButton.setIcon(null);
			discordLinkButton.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			discordLinkButton.setToolTipText("Discord linked as " + status.getUsername()
				+ " - right-click for more options");
		}
		else
		{
			// Logged in but not verified: greyed-out Discord glyph + "Link Discord".
			discordLinkButton.setText("Link Discord");
			discordLinkButton.setIcon(DISCORD_LINK_ICON_GREY);
			discordLinkButton.setForeground(new Color(0x80, 0x80, 0x80));
			discordLinkButton.setToolTipText("Link your Discord account (for private party voice channels)");
		}
		java.awt.Container parent = discordLinkButton.getParent();
		if (parent != null)
		{
			parent.revalidate();
			parent.repaint();
		}
		// Let the Party tab re-evaluate its voice buttons (authorize vs create/join) when link state flips.
		if (linked != discordLinked)
		{
			discordLinked = linked;
			partyPanel.refresh();
		}
	}

	private void startDiscordLink()
	{
		long hash = accountHashSupplier.getAsLong();
		if (hash == 0 || hash == -1)
		{
			discordLinkButton.setToolTipText("Log into your OSRS account first, then link Discord.");
			return;
		}
		discordLinkButton.setEnabled(false);
		discordLinkButton.setText("Linking…");
		boardService.startDiscordLink(hash,
			url -> SwingUtilities.invokeLater(() ->
			{
				LinkBrowser.browse(url);
				discordLinkButton.setEnabled(true);
				startLinkPolling(hash);
			}),
			err -> SwingUtilities.invokeLater(() ->
			{
				discordLinkButton.setEnabled(true);
				applyLinkStatus(null);
				discordLinkButton.setToolTipText("Couldn't start linking (server unreachable or linking disabled).");
			}));
	}

	/** After opening the browser, poll link status until it flips to linked (or we give up after ~2 min). */
	private void startLinkPolling(long hash)
	{
		if (linkPollTimer != null)
		{
			linkPollTimer.stop();
		}
		discordLinkButton.setText("Waiting for Discord…");
		final int[] ticks = {0};
		linkPollTimer = new Timer(2000, e ->
		{
			ticks[0]++;
			boardService.fetchDiscordLink(hash, status -> SwingUtilities.invokeLater(() ->
			{
				if (status != null && status.isLinked())
				{
					if (linkPollTimer != null)
					{
						linkPollTimer.stop();
					}
					applyLinkStatus(status);
				}
			}));
			if (ticks[0] >= 60)
			{
				linkPollTimer.stop();
				refreshDiscordLinkStatus(); // settle back to whatever the current state is
			}
		});
		linkPollTimer.setRepeats(true);
		linkPollTimer.start();
	}

	/** Stop every timer this panel owns and drop pending invites. Call when the plugin unloads. */
	public void dispose()
	{
		if (presenceTimer != null)
		{
			presenceTimer.stop();
		}
		if (linkPollTimer != null)
		{
			linkPollTimer.stop();
		}
		if (inviteSweepTimer != null)
		{
			inviteSweepTimer.stop();
		}
		membershipTimer.stop();
		inviteBanners.clear();
		inviteExpiry.clear();
		invitePanel.removeAll();
		searchPanel.dispose();
		favoritesPanel.dispose();
		partyPanel.dispose();
		createPanel.dispose();
		historyPanel.dispose();
	}

	/** The ad for the party we're currently in (host or member), or null. Safe to read off the EDT. */
	public Advertisement currentAd()
	{
		return contextAd;
	}

	/** Register a callback invoked when the side panel is opened (used to clear the invite blink). */
	public void setOnActivated(Runnable onActivated)
	{
		this.onActivated = onActivated;
	}

	/** Register a callback invoked when the side panel is closed. */
	public void setOnDeactivated(Runnable onDeactivated)
	{
		this.onDeactivated = onDeactivated;
	}

	@Override
	public void onActivate()
	{
		super.onActivate();
		Runnable callback = onActivated;
		if (callback != null)
		{
			callback.run();
		}
	}

	@Override
	public void onDeactivate()
	{
		super.onDeactivate();
		Runnable callback = onDeactivated;
		if (callback != null)
		{
			callback.run();
		}
	}

	/** Accept a party invite by joining via its invite code, reusing the Search panel's join-by-code flow. */
	public void joinByInviteCode(String code, java.util.function.Consumer<String> status)
	{
		searchPanel.joinByCode(code, status, true);
	}

	/** Register what the sidebar invite banner's Accept/Decline buttons do. */
	public void setInviteHandlers(Consumer<net.osparty.api.PartyInvite> onAccept,
		Consumer<net.osparty.api.PartyInvite> onDecline)
	{
		this.onInviteAccept = onAccept;
		this.onInviteDecline = onDecline;
	}

	/** Show an Accept/Decline invite banner at the top of the panel. Idempotent per party. EDT only. */
	public void addInvite(net.osparty.api.PartyInvite invite)
	{
		Advertisement ad = invite.getAd();
		if (ad == null || ad.getId() == null || inviteBanners.containsKey(ad.getId()))
		{
			return;
		}
		JPanel banner = buildInviteBanner(invite);
		inviteBanners.put(ad.getId(), banner);
		inviteExpiry.put(ad.getId(), System.currentTimeMillis() + INVITE_TTL_MS);
		invitePanel.add(banner);
		invitePanel.revalidate();
		invitePanel.repaint();
		startInviteSweep();
	}

	/** Drop banners whose invite has gone stale, so an ignored one can't sit on the panel forever. */
	private void startInviteSweep()
	{
		if (inviteSweepTimer == null)
		{
			inviteSweepTimer = new Timer(INVITE_SWEEP_MS, e ->
			{
				long now = System.currentTimeMillis();
				for (String adId : new java.util.ArrayList<>(inviteExpiry.keySet()))
				{
					if (inviteExpiry.get(adId) <= now)
					{
						removeInvite(adId);
					}
				}
				if (inviteBanners.isEmpty())
				{
					inviteSweepTimer.stop();
				}
			});
		}
		if (!inviteSweepTimer.isRunning())
		{
			inviteSweepTimer.start();
		}
	}

	private static JPanel buildInvitePanel()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		return panel;
	}

	/** Remove the invite banner for a party (once accepted/declined elsewhere). EDT only. */
	public void removeInvite(String adId)
	{
		JPanel banner = adId == null ? null : inviteBanners.remove(adId);
		inviteExpiry.remove(adId);
		if (banner != null)
		{
			invitePanel.remove(banner);
			invitePanel.revalidate();
			invitePanel.repaint();
		}
	}

	private JPanel buildInviteBanner(net.osparty.api.PartyInvite invite)
	{
		Advertisement ad = invite.getAd();
		String from = invite.getFromName() != null ? invite.getFromName() : ad.getHost();
		if (from == null)
		{
			from = "A friend";
		}
		net.osparty.model.Activity activity = net.osparty.model.Activity.fromId(ad.getActivity());
		String label = activity != null ? activity.getDisplayName() : "a party";

		JPanel banner = new JPanel(new BorderLayout(0, 4));
		banner.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		banner.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(6, 8, 6, 8)));

		JLabel text = new JLabel("<html><body style='width:170px'><b>" + from + "</b> invites you to "
			+ label + "</body></html>");
		text.setForeground(Color.WHITE);
		text.setFont(FontManager.getRunescapeSmallFont());
		banner.add(text, BorderLayout.NORTH);

		JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
		buttons.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JButton accept = new JButton("Accept");
		accept.setFocusPainted(false);
		accept.addActionListener(e ->
		{
			Consumer<net.osparty.api.PartyInvite> handler = onInviteAccept;
			if (handler != null)
			{
				handler.accept(invite);
			}
		});
		JButton decline = new JButton("Decline");
		decline.setFocusPainted(false);
		decline.addActionListener(e ->
		{
			Consumer<net.osparty.api.PartyInvite> handler = onInviteDecline;
			if (handler != null)
			{
				handler.accept(invite);
			}
		});
		buttons.add(accept);
		buttons.add(decline);
		banner.add(buttons, BorderLayout.SOUTH);
		return banner;
	}

	/** Re-render every view that draws Discord-role badges (called when the config toggle flips). */
	public void refreshDiscordBadgeViews()
	{
		searchPanel.reapplyFilters();
		favoritesPanel.render();
		partyPanel.refresh();
	}

	/** Restore a party the player was hosting before a restart. No-op if already in a party. */
	public void resumeHostedParty(Advertisement ad)
	{
		if (partyState.isInParty() || ad == null || ad.getPassphrase() == null)
		{
			return;
		}
		// The room lives on the owner node, so hosting again rejoins the existing room with its roster
		// intact — members who were already in it stay admitted rather than coming back as applicants.
		liveParty.hostParty(ad.getPassphrase(), ad.getHost(), ad.getActivity(), ad.getCapacity(), false,
			ad.getHostRole(), ad.isLearner(), ad.isTeacher());
		partyState.resumeHosting(ad);
	}

	/**
	 * Put us back into the party we were a member of before the client went away, as the host's own
	 * advertisement puts it back into its party. Only a membership this account held moments ago, and only
	 * a party that is still advertised — which is what says the party outlived us rather than ended with us.
	 *
	 * @return whether this answered the question of which party we were in, so the caller knows not to go
	 *     looking for a hosted one: a player is in one party at a time, and this was it.
	 */
	public boolean resumeJoinedParty(long accountHash)
	{
		if (partyState.isInParty())
		{
			return true;
		}
		PartyState.Membership saved = partyState.savedMembership(accountHash);
		if (saved == null)
		{
			return false;
		}
		boardService.fetchAdByCode(saved.getInviteCode(),
			ad -> SwingUtilities.invokeLater(() -> resumeJoinedParty(saved, ad)),
			error -> { /* the party ended while we were away, or we're offline - nothing to go back to */ });
		return true;
	}

	private void resumeJoinedParty(PartyState.Membership saved, Advertisement ad)
	{
		// An invite code outlives nothing: it goes with its party, so a code that now answers for a
		// different one is answering about a party we were never in.
		if (ad == null || partyState.isInParty() || !saved.getPartyId().equals(ad.getId())
			|| ad.getPassphrase() == null || ad.getPassphrase().isEmpty())
		{
			return;
		}
		liveParty.hintLiveNode(ad.getNode());
		// As an invited joiner, so the room seats us back as the member we were instead of queueing us
		// behind our own application. It claims nothing a reconnecting member does not already claim: the
		// room re-seats everyone from scratch on a handover, and each member asserts its own admission.
		liveParty.joinParty(ad.getPassphrase(), ad.getActivity(), ad.getCapacity(), saved.getRole(),
			saved.isLearner(), true);
		partyState.setMember(ad);
		net.osparty.model.Activity activity = net.osparty.model.Activity.fromId(ad.getActivity());
		String name = activity != null ? activity.getDisplayName() : ad.getActivity();
		gameMessage.accept("Rejoined " + ad.getHost() + "'s " + name
			+ " party - leave it from the OSParty panel if you're done.");
	}

	/** Route an inbound host-transfer handshake message (from the plugin's party-bus subscription). */
	public void onHostTransferEvent(HostTransferEvent message)
	{
		hostTransferHandler.onMessage(message);
	}

	private void onPartyStateChanged()
	{
		boolean inParty = partyState.isInParty();
		// Mirror the current backend party so the in-game invite menu (client thread) can read it safely.
		contextAd = partyState.getCurrentAd();

		// The party ended while editing — drop edit mode (and its tab layout) first.
		if (!inParty && editing)
		{
			editing = false;
			setCreateTabParty();
			createPanel.exitEditMode();
		}

		// Switch away from tabs that are about to be removed from the bar.
		if (!inParty && partyTab.isSelected())
		{
			tabGroup.select(searchTab);
		}
		else if (inParty && !editing && createTab.isSelected())
		{
			tabGroup.select(partyTab);
		}

		// Don't touch the tab bar mid-edit; finishEditParty restores it.
		if (!editing && inParty != inPartyTabLayout)
		{
			inPartyTabLayout = inParty;
			rebuildTabs(inParty);
		}

		if (inParty && !wasInParty)
		{
			// Entered a party. Only admitted players get a history row; a joiner's record is deferred
			// to syncHistoryRoster() until the host admits them.
			Advertisement ad = partyState.getCurrentAd();
			currentHistoryPartyId = ad == null ? null : ad.getId();
			historyRecorded = false;
			if (liveParty.isLocalAdmitted())
			{
				historyService.record(ad, partyState.isHost());
				historyRecorded = true;
				historyPanel.refresh();
			}
			tabGroup.select(partyTab);
		}
		else if (!inParty && wasInParty)
		{
			// Any in-flight host transfer is moot once we're out of the party.
			hostTransferHandler.reset();
			createPanel.onPartyEnded();
			// Left/disbanded: stamp still-present members as gone so the row shows nobody in the party.
			if (historyRecorded && historyService.closeParty(currentHistoryPartyId, System.currentTimeMillis()))
			{
				historyPanel.refresh();
			}
			currentHistoryPartyId = null;
			historyRecorded = false;
		}

		wasInParty = inParty;
		revalidate();
		repaint();
	}

	/** Keep the current party's history row in step with the live roster (join/leave after entry). */
	private void syncHistoryRoster()
	{
		if (!partyState.isInParty())
		{
			return;
		}
		Advertisement ad = partyState.getCurrentAd();
		if (ad == null)
		{
			return;
		}
		// Deferred record for joiners: the host just admitted us, so record now (see onPartyStateChanged).
		if (!historyRecorded && liveParty.isLocalAdmitted())
		{
			historyService.record(ad, partyState.isHost());
			historyRecorded = true;
			historyPanel.refresh();
		}
		if (historyService.updateRoster(ad.getId(), liveParty.currentMembers()))
		{
			historyPanel.refresh();
		}
	}

	/**
	 * Note that we are still in the party we joined, so a client that dies mid-party comes back into it
	 * (see {@link #resumeJoinedParty}). Members only, and only once admitted: a host has its advertisement
	 * to come back to, and an applicant has nothing yet to come back to.
	 */
	private void rememberMembership()
	{
		if (partyState.isInParty() && !partyState.isHost() && liveParty.isLocalAdmitted())
		{
			partyState.rememberMembership(partyState.getCurrentAd(), liveParty.getLocalRole(),
				liveParty.isLocalLearner(), accountHashSupplier.getAsLong());
		}
	}

	/** Restore the Create/Party tab to its default party icon + tooltip (leaving edit mode). */
	private void setCreateTabParty()
	{
		createTab.setIcon(TabIcons.PARTY);
		createTab.setToolTipText("Party");
	}

	/** Fetch an OSRS sprite and set it as a tab icon once loaded. No-op when SpriteManager is null. */
	private static void applyTabSprite(SpriteManager spriteManager, int spriteId, java.util.function.Consumer<ImageIcon> apply)
	{
		if (spriteManager == null)
		{
			return;
		}
		// The sprite callback runs on the client thread when it isn't cached yet.
		spriteManager.getSpriteAsync(spriteId, 0, img ->
		{
			if (img != null)
			{
				SwingUtilities.invokeLater(() -> apply.accept(TabIcons.boxed(img)));
			}
		});
	}

	/** Fetch an item sprite (async) and set it as a tab icon once loaded. No-op when ItemManager is null. */
	private static void applyTabItem(ItemManager itemManager, int itemId, java.util.function.Consumer<ImageIcon> apply)
	{
		if (itemManager == null)
		{
			return;
		}
		AsyncBufferedImage img = itemManager.getImage(itemId);
		if (img == null)
		{
			return;
		}
		img.onLoaded(() -> SwingUtilities.invokeLater(() -> apply.accept(TabIcons.boxedTrimmed(img))));
	}

	/** Host clicked "Edit party": open the create form in edit mode beside the Party (roster) tab. */
	private void openEditParty()
	{
		Advertisement ad = partyState.getCurrentAd();
		if (ad == null || !partyState.isHost())
		{
			return;
		}
		editing = true;
		createPanel.enterEditMode(ad);
		createTab.setIcon(TabIcons.EDIT);
		createTab.setToolTipText("Edit party");
		rebuildTabsForEdit();
		tabGroup.select(createTab);
	}

	/** Edit saved (or finished): restore the normal tab bar and return to the Party tab. */
	private void finishEditParty()
	{
		editing = false;
		setCreateTabParty();
		if (partyState.isInParty())
		{
			rebuildTabs(true);
			tabGroup.select(partyTab);
		}
		else
		{
			rebuildTabs(false);
			tabGroup.select(searchTab);
		}
	}

	/** Edit layout: Search | Party | Edit | Favorites (the create form stays available while hosting). */
	private void rebuildTabsForEdit()
	{
		layoutTabs(searchTab, partyTab, createTab, favoritesTab, blockedTab, historyTab);
	}

	/** Rebuild the tab bar for idle (Create shown) vs in-party (Party shown, Create hidden). */
	private void rebuildTabs(boolean inParty)
	{
		layoutTabs(searchTab, inParty ? partyTab : createTab, favoritesTab, blockedTab, historyTab);
	}

	/** Lay the bar out as exactly {@code order}; every tab stays registered with the group either way. */
	private void layoutTabs(MaterialTab... order)
	{
		tabGroup.remove(searchTab);
		tabGroup.remove(createTab);
		tabGroup.remove(favoritesTab);
		tabGroup.remove(blockedTab);
		tabGroup.remove(partyTab);
		tabGroup.remove(historyTab);

		for (MaterialTab tab : order)
		{
			tabGroup.add(tab);
		}

		tabGroup.revalidate();
		tabGroup.repaint();
	}
}
