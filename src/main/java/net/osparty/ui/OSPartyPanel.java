package net.osparty.ui;

import net.osparty.service.BlockListService;
import net.osparty.service.FavoritesService;
import net.osparty.tools.HostApplicationHandler;
import net.osparty.service.KillcountService;
import net.osparty.OSPartyConfig;
import net.osparty.api.DiscordLinkStatus;
import net.osparty.api.PartyService;
import net.osparty.service.PartyHistoryService;
import net.osparty.model.Party;
import net.osparty.party.HostTransferMessage;
import net.osparty.party.LiveParty;
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
import java.util.function.IntConsumer;
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
	private static final String VERSION = "1.0.34";
	private static final String GITHUB_URL = "https://github.com/osparty/osparty";
	private static final String DISCORD_URL = "https://discord.gg/EtMRxTHXWJ";

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
	private final LiveParty liveParty;
	private final PartyService partyService;
	private final HostTransferHandler hostTransferHandler;
	private final LongSupplier accountHashSupplier;
	private final JLabel activeUsersLabel = new JLabel();
	private final JButton discordLinkButton = new JButton();
	private Timer presenceTimer;
	private Timer linkPollTimer;
	/** Last accountHash we queried link status for, so we only re-query when the logged-in account changes. */
	private long lastLinkQueryHash = Long.MIN_VALUE;
	/** Whether the local account is currently Discord-linked; gates the Party tab's voice buttons. */
	private volatile boolean discordLinked;
	/** The account's server-side badge-privacy preference, mirrored from the last link status. */
	private volatile boolean badgesVisible = true;
	private final SearchPanel searchPanel;
	private final FriendsPanel favoritesPanel;
	private final BlockedPanel blockedPanel;
	private final CreatePanel createPanel;
	private final PartyPanel partyPanel;
	private final HistoryPanel historyPanel;
	private final PartyHistoryService historyService;
	private final MaterialTabGroup tabGroup;
	private final MaterialTab searchTab;
	private final MaterialTab createTab;
	private final MaterialTab favesTab;
	private final MaterialTab blockedTab;
	private final MaterialTab partyTab;
	private final MaterialTab historyTab;
	/** Resolved icon for the Party/Create tab (the OSRS sprite once loaded, else the drawn fallback). */
	private ImageIcon partyTabIcon = TabIcons.PARTY;
	private boolean wasInParty;
	/** Id of the party currently logged in history, so we can stamp it ended once we leave it. */
	private String currentHistoryPartyId;
	/** Whether the current party has a history row yet — false while our application is still pending. */
	private boolean historyRecorded;
	/** Whether the tab bar is in the in-party layout (Party shown, Create hidden). */
	private boolean inPartyTabLayout;
	/** Whether the host is editing their party (the create form is shown alongside the roster). */
	private boolean editing;

	public OSPartyPanel(PartyService partyService, OSPartyConfig config, Supplier<String> playerNameSupplier,
		HostApplicationHandler hostApplicationHandler, Supplier<String> friendsChatOwnerSupplier,
		IntSupplier worldSupplier, ItemManager itemManager, LiveParty liveParty,
		RuneWatchService runeWatchService, Supplier<AccountType> accountTypeSupplier,
		KillcountService killcountService, SkillIconManager skillIconManager, IntConsumer worldHopper,
		Supplier<int[]> mapRegionsSupplier, IntFunction<WorldRegion> worldRegionResolver,
		Supplier<String> coxLayoutSupplier, ConfigManager configManager, Gson gson,
		WorldPinger worldPinger, IntFunction<String> worldAddressResolver,
		Supplier<Set<String>> friendNamesSupplier, FavoritesService favoritesService,
		BlockListService blockListService, LongSupplier accountHashSupplier,
		SpriteManager spriteManager, PartyHistoryService historyService, Consumer<String> gameMessage)
	{
		super(false);

		this.liveParty = liveParty;
		this.partyService = partyService;
		this.accountHashSupplier = accountHashSupplier;
		this.historyService = historyService;
		this.partyState = new PartyState(configManager);
		this.hostTransferHandler = new HostTransferHandler(liveParty, partyService, partyState,
			playerNameSupplier, gameMessage);

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		// super(false) skips PluginPanel's default border, so add our own padding.
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		searchPanel = new SearchPanel(partyService, playerNameSupplier,
			friendsChatOwnerSupplier, worldSupplier, partyState, liveParty, accountTypeSupplier,
			mapRegionsSupplier, worldRegionResolver, killcountService, configManager,
			worldPinger, worldAddressResolver, friendNamesSupplier, favoritesService, blockListService,
			spriteManager, config);
		favoritesPanel = new FriendsPanel(partyService, playerNameSupplier, partyState,
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
		createPanel = new CreatePanel(partyService, config, playerNameSupplier, partyState, liveParty,
			accountTypeSupplier, accountHashSupplier, mapRegionsSupplier, coxLayoutSupplier, configManager, gson,
			killcountService, worldSupplier);
		createPanel.setJoinByCodeHandler(searchPanel::joinByCode);
		partyPanel = new PartyPanel(partyService, playerNameSupplier,
			hostApplicationHandler, partyState, itemManager, liveParty, runeWatchService, killcountService,
			skillIconManager, worldSupplier, worldHopper, friendsChatOwnerSupplier, coxLayoutSupplier,
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
		favesTab = new MaterialTab(TabIcons.FAVORITES, tabGroup, favoritesPanel);
		favesTab.setToolTipText("Favorites");
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
		applyTabSprite(spriteManager, SpriteID.WORLD_SWITCHER_STAR_MEMBERS, favesTab::setIcon);
		applyTabSprite(spriteManager, SpriteID.TAB_IGNORES, blockedTab::setIcon);
		applyTabItem(itemManager, ItemID.HOURGLASS, historyTab::setIcon);

		// Register all tabs (needed for select()); rebuildTabs lays out the idle bar.
		tabGroup.addTab(searchTab);
		tabGroup.addTab(createTab);
		tabGroup.addTab(favesTab);
		tabGroup.addTab(blockedTab);
		tabGroup.addTab(partyTab);
		tabGroup.addTab(historyTab);
		rebuildTabs(false);
		tabGroup.select(searchTab);

		add(tabGroup, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);
		add(buildFooter(), BorderLayout.SOUTH);

		partyState.addListener(this::onPartyStateChanged);
		// Live joins/leaves arrive off-EDT; marshal back before touching history/Swing.
		liveParty.addListener(() -> SwingUtilities.invokeLater(this::syncHistoryRoster));
	}


	private JPanel buildFooter()
	{
		// Row 1: community links. Row 2: online (left) | link state (centre) | version (right).
		JPanel footer = new JPanel(new GridLayout(2, 1, 0, 2));
		footer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		footer.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		// ---- Row 1: GitHub + Discord community buttons, centred ----
		JButton github = new JButton();
		github.setToolTipText("Open the OSParty GitHub page");
		github.setFocusPainted(false);
		github.setBorder(BorderFactory.createEmptyBorder());
		github.setContentAreaFilled(false);
		github.setCursor(new Cursor(Cursor.HAND_CURSOR));
		BufferedImage logo = ImageUtil.loadImageResource(getClass(), "/net/osparty/icons/github.png");
		if (logo != null)
		{
			github.setIcon(new ImageIcon(ImageUtil.resizeImage(logo, 16, 16)));
		}
		else
		{
			github.setText("GitHub");
			github.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			github.setFont(FontManager.getRunescapeSmallFont());
		}
		github.addActionListener(e -> LinkBrowser.browse(GITHUB_URL));

		JButton discord = new JButton();
		discord.setToolTipText("Open the OSParty Discord");
		discord.setFocusPainted(false);
		discord.setBorder(BorderFactory.createEmptyBorder());
		discord.setContentAreaFilled(false);
		discord.setCursor(new Cursor(Cursor.HAND_CURSOR));
		BufferedImage discordLogo = ImageUtil.loadImageResource(getClass(), "/net/osparty/icons/discord.png");
		if (discordLogo != null)
		{
			discord.setIcon(new ImageIcon(ImageUtil.resizeImage(discordLogo, 16, 16)));
		}
		else
		{
			discord.setText("Discord");
			discord.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			discord.setFont(FontManager.getRunescapeSmallFont());
		}
		discord.addActionListener(e -> LinkBrowser.browse(DISCORD_URL));

		JPanel row1 = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
		row1.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row1.add(github);
		row1.add(discord);

		// ---- Row 2: online (left) | link state (centre) | version (right) ----
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

	private void updateActiveUsers()
	{
		int online = partyService.onlineUsers();
		activeUsersLabel.setText(online < 0 ? "" : online + " online");

		// Only re-query link status when the logged-in account changes, not every tick.
		long hash = accountHashSupplier.getAsLong();
		if (hash != lastLinkQueryHash)
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
		partyService.setBadgeVisibility(hash, visible,
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
		partyService.unlinkDiscord(hash);
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
		partyService.getDiscordLink(hash, status -> SwingUtilities.invokeLater(() -> applyLinkStatus(status)));
	}

	private void applyLinkStatus(DiscordLinkStatus status)
	{
		// Not logged in: hide the button entirely — no username, no link prompt.
		long hash = accountHashSupplier.getAsLong();
		boolean loggedIn = hash != 0 && hash != -1;
		discordLinkButton.setVisible(loggedIn);

		boolean linked = loggedIn && status != null && status.isLinked();
		badgesVisible = status == null || status.isBadgesVisible();
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
		partyService.startDiscordLink(hash,
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
			partyService.getDiscordLink(hash, status -> SwingUtilities.invokeLater(() ->
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

	/** Release the live party-list socket (and timers). Call when the plugin unloads. */
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
		searchPanel.dispose();
	}

	/** Re-render every view that draws Discord-role badges (called when the config toggle flips). */
	public void refreshDiscordBadgeViews()
	{
		searchPanel.reapplyFilters();
		favoritesPanel.render();
		partyPanel.refresh();
	}

	/** Restore a party the player was hosting before a restart. No-op if already in a party. */
	public void resumeHostedParty(Party party)
	{
		if (partyState.isInParty() || party == null || party.getPassphrase() == null)
		{
			return;
		}
		liveParty.hostParty(party.getPassphrase(), party.getHost(), party.getActivity(), party.getCapacity(), false,
			party.getHostRole(), party.isLearner(), party.isTeacher());
		partyState.resumeHosting(party);
	}

	/** Route an inbound host-transfer handshake message (from the plugin's party-bus subscription). */
	public void onHostTransferMessage(HostTransferMessage message)
	{
		hostTransferHandler.onMessage(message);
	}

	private void onPartyStateChanged()
	{
		boolean inParty = partyState.isInParty();

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
			Party party = partyState.getCurrentParty();
			currentHistoryPartyId = party == null ? null : party.getId();
			historyRecorded = false;
			if (liveParty.isLocalAdmitted())
			{
				historyService.record(party, partyState.isHost());
				historyRecorded = true;
				historyPanel.refresh();
			}
			tabGroup.select(partyTab);
		}
		else if (!inParty && wasInParty)
		{
			// Any in-flight host transfer is moot once we're out of the party.
			hostTransferHandler.reset();
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
		Party party = partyState.getCurrentParty();
		if (party == null)
		{
			return;
		}
		// Deferred record for joiners: the host just admitted us, so record now (see onPartyStateChanged).
		if (!historyRecorded && liveParty.isLocalAdmitted())
		{
			historyService.record(party, partyState.isHost());
			historyRecorded = true;
			historyPanel.refresh();
		}
		if (historyService.updateRoster(party.getId(), liveParty.currentMembers()))
		{
			historyPanel.refresh();
		}
	}

	/** Restore the Create/Party tab to its default party icon + tooltip (leaving edit mode). */
	private void setCreateTabParty()
	{
		createTab.setIcon(partyTabIcon);
		createTab.setToolTipText("Party");
	}

	/** Fetch an OSRS sprite and set it as a tab icon once loaded. No-op when SpriteManager is null. */
	private static void applyTabSprite(SpriteManager spriteManager, int spriteId, java.util.function.Consumer<ImageIcon> apply)
	{
		if (spriteManager == null)
		{
			return;
		}
		spriteManager.getSpriteAsync(spriteId, 0, img ->
		{
			if (img != null)
			{
				apply.accept(toTabIcon(img));
			}
		});
	}

	/** Centre a sprite into the shared tab-icon box so every tab button stays the same size. */
	private static ImageIcon toTabIcon(BufferedImage img)
	{
		return TabIcons.boxed(img);
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
		Party party = partyState.getCurrentParty();
		if (party == null || !partyState.isHost())
		{
			return;
		}
		editing = true;
		createPanel.enterEditMode(party);
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
		tabGroup.remove(searchTab);
		tabGroup.remove(createTab);
		tabGroup.remove(favesTab);
		tabGroup.remove(blockedTab);
		tabGroup.remove(partyTab);
		tabGroup.remove(historyTab);

		tabGroup.add(searchTab);
		tabGroup.add(partyTab);
		tabGroup.add(createTab);
		tabGroup.add(favesTab);
		tabGroup.add(blockedTab);
		tabGroup.add(historyTab);

		tabGroup.revalidate();
		tabGroup.repaint();
	}

	/** Rebuild the tab bar for idle (Create shown) vs in-party (Party shown, Create hidden). */
	private void rebuildTabs(boolean inParty)
	{
		tabGroup.remove(searchTab);
		tabGroup.remove(createTab);
		tabGroup.remove(favesTab);
		tabGroup.remove(blockedTab);
		tabGroup.remove(partyTab);
		tabGroup.remove(historyTab);

		tabGroup.add(searchTab);
		if (inParty)
		{
			tabGroup.add(partyTab);
		}
		else
		{
			tabGroup.add(createTab);
		}
		tabGroup.add(favesTab);
		tabGroup.add(blockedTab);
		tabGroup.add(historyTab);

		tabGroup.revalidate();
		tabGroup.repaint();
	}
}
