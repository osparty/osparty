package net.osparty.ui;

import net.osparty.BlockListService;
import net.osparty.FavoritesService;
import net.osparty.HostApplicationHandler;
import net.osparty.KillcountService;
import net.osparty.OSPartyConfig;
import net.osparty.api.DiscordLinkStatus;
import net.osparty.api.PartyService;
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
	private static final String VERSION = "1.0.7";
	private static final String GITHUB_URL = "https://github.com/iodrareg/osparty";
	private static final String DISCORD_URL = "https://discord.gg/3xrf7wkb5F";

	/** Discord glyph for the link button: full colour when linked, greyed out when not. */
	private static final ImageIcon DISCORD_LINK_ICON = loadDiscordIcon(false);
	private static final ImageIcon DISCORD_LINK_ICON_GREY = loadDiscordIcon(true);

	private static ImageIcon loadDiscordIcon(boolean grey)
	{
		BufferedImage img = ImageUtil.loadImageResource(OSPartyPanel.class, "/net/osparty/icons/discord.png");
		if (img == null)
		{
			return null;
		}
		BufferedImage sized = ImageUtil.resizeImage(img, 14, 14);
		return new ImageIcon(grey ? ImageUtil.grayscaleImage(sized) : sized);
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
	private final CreatePanel createPanel;
	private final PartyPanel partyPanel;
	private final MaterialTabGroup tabGroup;
	private final MaterialTab searchTab;
	private final MaterialTab createTab;
	private final MaterialTab favesTab;
	private final MaterialTab partyTab;
	private boolean wasInParty;
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
		SpriteManager spriteManager)
	{
		super(false);

		this.liveParty = liveParty;
		this.partyService = partyService;
		this.accountHashSupplier = accountHashSupplier;
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

		// Cross-notify: toggling a favorite/block in Search refreshes the Favorites tab and vice versa.
		searchPanel.setOnFavoriteChanged(favoritesPanel::render);
		favoritesPanel.setOnFavoriteChanged(searchPanel::renderCurrent);
		searchPanel.setOnBlockChanged(favoritesPanel::render);
		favoritesPanel.setOnBlockChanged(searchPanel::renderCurrent);
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
		searchTab = new MaterialTab("Search", tabGroup, searchPanel);
		createTab = new MaterialTab("Party", tabGroup, createScroll);
		favesTab = new MaterialTab("Favorites", tabGroup, favoritesPanel);
		partyTab = new MaterialTab("Party", tabGroup, partyPanel);

		// Register every tab with the group (needed for select()), then lay out the
		// idle bar. In-party swaps Create for Party (see rebuildTabs).
		tabGroup.addTab(searchTab);
		tabGroup.addTab(createTab);
		tabGroup.addTab(favesTab);
		tabGroup.addTab(partyTab);
		rebuildTabs(false);
		tabGroup.select(searchTab);

		add(tabGroup, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);
		add(buildFooter(), BorderLayout.SOUTH);

		partyState.addListener(this::onPartyStateChanged);
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
		boolean linked = status != null && status.isLinked();
		discordLinkButton.setIconTextGap(4);
		if (linked)
		{
			// Verified: full-colour Discord glyph + the linked username.
			discordLinkButton.setText(status.getUsername());
			discordLinkButton.setIcon(DISCORD_LINK_ICON);
			discordLinkButton.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			discordLinkButton.setToolTipText("Discord linked as " + status.getUsername()
				+ " - right-click for more options");
		}
		else
		{
			// Not verified: greyed-out Discord glyph + "Link Discord".
			discordLinkButton.setText("Link Discord");
			discordLinkButton.setIcon(DISCORD_LINK_ICON_GREY);
			discordLinkButton.setForeground(new Color(0x80, 0x80, 0x80));
			discordLinkButton.setToolTipText("Link your Discord account (for private party voice channels)");
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
			createTab.setText("Party");
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
			tabGroup.select(partyTab);
		}

		wasInParty = inParty;
		revalidate();
		repaint();
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
		createTab.setText("Edit");
		rebuildTabsForEdit();
		tabGroup.select(createTab);
	}

	/** Edit saved (or finished): restore the normal tab bar and return to the Party tab. */
	private void finishEditParty()
	{
		editing = false;
		createTab.setText("Party");
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
		tabGroup.remove(partyTab);

		tabGroup.add(searchTab);
		tabGroup.add(partyTab);
		tabGroup.add(createTab);
		tabGroup.add(favesTab);

		tabGroup.revalidate();
		tabGroup.repaint();
	}

	/**
	 * Rebuild the tab bar for idle vs in-party. Idle: Search | Create | Favorites.
	 * In-party: Search | Party | Favorites (Create hidden — you can only be in one party).
	 */
	private void rebuildTabs(boolean inParty)
	{
		tabGroup.remove(searchTab);
		tabGroup.remove(createTab);
		tabGroup.remove(favesTab);
		tabGroup.remove(partyTab);

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

		tabGroup.revalidate();
		tabGroup.repaint();
	}
}
