package net.osparty.ui;

import net.osparty.BlockListService;
import net.osparty.FavoritesService;
import net.osparty.HostApplicationHandler;
import net.osparty.KillcountService;
import net.osparty.OSPartyConfig;
import net.osparty.api.DiscordLinkStatus;
import net.osparty.api.PartyService;
import net.osparty.history.PartyHistoryService;
import net.osparty.model.Party;
import net.osparty.party.LiveParty;
import com.google.gson.Gson;
import net.osparty.runewatch.RuneWatchService;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Set;
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
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import net.osparty.WorldPinger;
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

/**
 * Root side-panel. Hosts Search / Favorites always, plus Create when idle and Party
 * while in a party (hosting or joined). All tabs share a single {@link PartyState}
 * so the one-party-at-a-time rule and tab visibility stay in sync.
 */
public class OSPartyPanel extends PluginPanel
{
	// Hard-coded: PluginHub's build doesn't bundle runelite-plugin.properties, so
	// reading the version from the classpath there yields "?". Keep this in step
	// with runelite-plugin.properties on each release.
	private static final String VERSION = "1.0.19";
	private static final String GITHUB_URL = "https://github.com/iodrareg/osparty";
	private static final String DISCORD_URL = "https://discord.gg/3xrf7wkb5F";

	/** Distinct green used for the Party tab's "a party is running" underline, so it reads apart
	 *  from the orange {@link MaterialTab} selection underline. */
	private static final Color PARTY_ACTIVE_COLOR = new Color(0x4C, 0xAF, 0x50);
	/** Bottom-underline border in {@link #PARTY_ACTIVE_COLOR}, matching the selected border's insets
	 *  (5,10,4,10 + a 1px underline) so swapping it in never changes the tab's size. */
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
	private final LongSupplier accountHashSupplier;
	private final JLabel activeUsersLabel = new JLabel();
	private final JButton discordLinkButton = new JButton();
	private Timer presenceTimer;
	private Timer linkPollTimer;
	/** Last accountHash we queried link status for, so we only re-query when the logged-in account changes. */
	private long lastLinkQueryHash = Long.MIN_VALUE;
	/** Whether the local account is currently Discord-linked; gates the Party tab's voice buttons. */
	private volatile boolean discordLinked;
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
		SpriteManager spriteManager, PartyHistoryService historyService)
	{
		super(false);

		this.liveParty = liveParty;
		this.partyService = partyService;
		this.accountHashSupplier = accountHashSupplier;
		this.historyService = historyService;
		this.partyState = new PartyState(configManager);

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		// super(false) skips PluginPanel's default border, so add our own breathing
		// room — otherwise the tabs and content sit flush against the sidebar edges.
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

		// Cross-notify: toggling a favourite in Search refreshes the Favorites tab and vice versa;
		// toggling a block anywhere refreshes the Blocked tab (and the card lists that hide blocks).
		searchPanel.setOnFavoriteChanged(favoritesPanel::render);
		favoritesPanel.setOnFavoriteChanged(searchPanel::renderCurrent);
		searchPanel.setOnBlockChanged(() -> { favoritesPanel.render(); blockedPanel.render(); });
		favoritesPanel.setOnBlockChanged(() -> { searchPanel.renderCurrent(); blockedPanel.render(); });
		blockedPanel.setOnBlockChanged(() -> { searchPanel.renderCurrent(); favoritesPanel.render(); });
		createPanel = new CreatePanel(partyService, config, playerNameSupplier, partyState, liveParty,
			accountTypeSupplier, accountHashSupplier, mapRegionsSupplier, coxLayoutSupplier, configManager, gson,
			killcountService, worldSupplier);
		// The Create tab's "Join existing" section reuses the Search tab's join-by-code apply flow.
		createPanel.setJoinByCodeHandler(searchPanel::joinByCode);
		partyPanel = new PartyPanel(partyService, playerNameSupplier,
			hostApplicationHandler, partyState, itemManager, liveParty, runeWatchService, killcountService,
			skillIconManager, worldSupplier, worldHopper, friendsChatOwnerSupplier, coxLayoutSupplier,
			config, configManager, favoritesService, blockListService, spriteManager,
			() -> discordLinked, this::startDiscordLink, accountHashSupplier);
		historyPanel = new HistoryPanel(historyService, favoritesService, blockListService);
		// Favouriting/blocking a player from a history roster refreshes the affected tabs.
		historyPanel.setOnFlagChanged(() ->
		{
			searchPanel.renderCurrent();
			favoritesPanel.render();
			blockedPanel.render();
		});

		// Host edit flow: the Party tab's "Edit party" button opens the create form in edit
		// mode; saving returns to the Party (roster) tab.
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
		// Icon tabs (not text): four/five text labels overflow the narrow sidebar and drop the
		// last tab. Each tab starts with a drawn bitmap fallback (TabIcons — always available,
		// renders identically on every platform), then upgrades to the authentic OSRS interface
		// sprite once SpriteManager loads it. Tooltips name each tab.
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
				// While a party is running, keep a distinct green underline on the Party tab even when
				// another tab is selected — a persistent "you're in a party" marker that doesn't get
				// confused with the orange underline of the currently-selected tab.
				if (partyState.isInParty())
				{
					setBorder(PARTY_ACTIVE_BORDER);
				}
				// MaterialTabGroup only swaps the content display on select; repaint the whole tab bar so
				// the underline can't lag a frame behind the selection (leaving a stale/missing underline).
				if (tabGroup != null)
				{
					tabGroup.repaint();
				}
			}
		};
		partyTab.setToolTipText("Party");
		historyTab = new MaterialTab(TabIcons.HISTORY, tabGroup, historyPanel);
		historyTab.setToolTipText("History");

		// Search / Favorites / Blocked upgrade to authentic OSRS interface sprites, and History to the
		// in-game Recruitment Drive Hourglass item sprite. Party uses the bundled chat-channel PNG
		// (TabIcons.PARTY) — OSRS has no square interface sprite that reads cleanly for it at tab size.
		applyTabSprite(spriteManager, SpriteID.GE_SEARCH, searchTab::setIcon);
		applyTabSprite(spriteManager, SpriteID.WORLD_SWITCHER_STAR_MEMBERS, favesTab::setIcon);
		applyTabSprite(spriteManager, SpriteID.TAB_IGNORES, blockedTab::setIcon);
		applyTabItem(itemManager, ItemID.HOURGLASS, historyTab::setIcon);

		// Register every tab with the group (needed for select()), then lay out the
		// idle bar. In-party swaps Create for Party (see rebuildTabs).
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
		// Live joins/leaves arrive off the EDT via LiveParty; marshal back before touching the
		// history snapshot and Swing (mirrors PartyPanel's own liveParty listener).
		liveParty.addListener(() -> SwingUtilities.invokeLater(this::syncHistoryRoster));
	}


	private JPanel buildFooter()
	{
		// Two rows: the community links (GitHub + Discord) centered on top, then a status row of
		// online-count (left) | Discord link state (centre) | version (right).
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

		// The server pushes the live count over the socket; poll the cached value onto the
		// footer (cheap, no network). Timer fires on the EDT so touching Swing is safe.
		presenceTimer = new Timer(3000, e -> updateActiveUsers());
		presenceTimer.start();

		return footer;
	}

	private void updateActiveUsers()
	{
		int online = partyService.onlineUsers();
		activeUsersLabel.setText(online < 0 ? "" : online + " online");

		// Refresh the Discord link button only when the logged-in account changes (e.g. after login),
		// so we're not firing a lookup every timer tick.
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
		menu.add(relink);
		menu.add(unlink);
		menu.show(discordLinkButton, e.getX(), e.getY());
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

	/**
	 * Restore a party the player was hosting before a restart (looked up by RSN).
	 * No-op if already in a party or the ad has no live-room passphrase.
	 */
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
			// Entered a party (hosted or joined): log it to history, then re-render the tab.
			Party party = partyState.getCurrentParty();
			historyService.record(party, partyState.isHost());
			currentHistoryPartyId = party == null ? null : party.getId();
			historyPanel.refresh();
			tabGroup.select(partyTab);
		}
		else if (!inParty && wasInParty)
		{
			// Left / disbanded / party ended: stamp the still-present members (host included) as gone,
			// so the concluded row no longer shows anyone as in the party. currentParty is already null.
			if (historyService.closeParty(currentHistoryPartyId, System.currentTimeMillis()))
			{
				historyPanel.refresh();
			}
			currentHistoryPartyId = null;
		}

		wasInParty = inParty;
		revalidate();
		repaint();
	}

	/**
	 * Keep the current party's history row in step with the live roster so members who join or leave
	 * after we entered are reflected (the row is otherwise a frozen entry-time snapshot). Fires on
	 * every live-party change; {@link PartyHistoryService#updateRoster} only rewrites the file — and
	 * we only refresh the panel — when the membership actually changed, so presence pings are cheap.
	 */
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

	/**
	 * Fetch an OSRS interface sprite and hand it to {@code apply} (on the EDT-adjacent sprite
	 * callback, matching the rest of the panel) once loaded, scaled to fit the tab. No-op when
	 * SpriteManager is unavailable — the drawn fallback icon already set on the tab stays.
	 */
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

	/**
	 * Centre a sprite into the shared tab-icon box (shrinking to fit, never upscaling) so every tab
	 * button stays the same size once the async sprite upgrade lands.
	 */
	private static ImageIcon toTabIcon(BufferedImage img)
	{
		return TabIcons.boxed(img);
	}

	/**
	 * Fetch an item's inventory sprite (async) and set it as a tab icon once loaded, trimmed and
	 * boxed to the uniform tab size. Marshals onto the EDT since {@link AsyncBufferedImage#onLoaded}
	 * fires on the client thread. No-op when ItemManager is unavailable — the drawn fallback stays.
	 */
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

	/**
	 * Rebuild the tab bar for idle vs in-party. Idle: Search | Create | Favorites | Blocked | History.
	 * In-party: Search | Party | Favorites | Blocked | History (Create hidden — one party at a time).
	 */
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
