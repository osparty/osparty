package net.osparty.ui;

import net.osparty.OSPartyConfig;
import net.osparty.HostApplicationHandler;
import net.osparty.KillcountService;
import net.osparty.api.PartyService;
import net.osparty.model.Party;
import net.osparty.party.LiveParty;
import net.osparty.runewatch.RuneWatchService;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Properties;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.runelite.api.vars.AccountType;
import net.runelite.http.api.worlds.WorldRegion;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

/**
 * Root side-panel. Hosts Search / Create tabs, plus a "Current" tab that only
 * appears while the player is in a party (hosting or joined). All three tabs
 * share a single {@link PartyState} so the membership rule (one party at a
 * time) and the Current tab's visibility stay in sync.
 */
public class OSPartyPanel extends PluginPanel
{
	/** Plugin version shown in the footer, read from runelite-plugin.properties. */
	private static final String VERSION = readPluginVersion();
	private static final String GITHUB_URL = "https://github.com/iodrareg/osparty";

	private final PartyState partyState = new PartyState();
	private final LiveParty liveParty;
	private final MaterialTabGroup tabGroup;
	private final MaterialTab searchTab;
	private final MaterialTab currentTab;
	private boolean wasInParty;

	public OSPartyPanel(PartyService partyService, OSPartyConfig config, Supplier<String> playerNameSupplier,
		HostApplicationHandler hostApplicationHandler, Supplier<String> friendsChatOwnerSupplier,
		IntSupplier worldSupplier, ItemManager itemManager, LiveParty liveParty,
		RuneWatchService runeWatchService, Supplier<AccountType> accountTypeSupplier,
		KillcountService killcountService, SkillIconManager skillIconManager, IntConsumer worldHopper,
		Supplier<int[]> mapRegionsSupplier, IntFunction<WorldRegion> worldRegionResolver,
		Supplier<String> coxLayoutSupplier, ConfigManager configManager)
	{
		super(false);

		this.liveParty = liveParty;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		// super(false) skips PluginPanel's default border, so add our own breathing
		// room — otherwise the tabs and content sit flush against the sidebar edges.
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		SearchPanel searchPanel = new SearchPanel(partyService, playerNameSupplier,
			friendsChatOwnerSupplier, worldSupplier, partyState, liveParty, accountTypeSupplier,
			mapRegionsSupplier, worldRegionResolver);
		CreatePanel createPanel = new CreatePanel(partyService, config, playerNameSupplier, partyState, liveParty,
			accountTypeSupplier, mapRegionsSupplier, coxLayoutSupplier, configManager);
		CurrentPanel currentPanel = new CurrentPanel(partyService, playerNameSupplier,
			hostApplicationHandler, partyState, itemManager, liveParty, runeWatchService, killcountService,
			skillIconManager, worldSupplier, worldHopper, friendsChatOwnerSupplier, coxLayoutSupplier);

		JPanel display = new JPanel(new BorderLayout());
		display.setBackground(ColorScheme.DARK_GRAY_COLOR);

		tabGroup = new MaterialTabGroup(display);
		searchTab = new MaterialTab("Search", tabGroup, searchPanel);
		MaterialTab createTab = new MaterialTab("Create", tabGroup, createPanel);
		currentTab = new MaterialTab("Current", tabGroup, currentPanel);

		tabGroup.addTab(searchTab);
		tabGroup.addTab(createTab);
		tabGroup.addTab(currentTab);

		// The Current tab only shows while in a party.
		currentTab.setVisible(false);
		tabGroup.select(searchTab);

		add(tabGroup, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);
		add(buildFooter(), BorderLayout.SOUTH);

		partyState.addListener(this::onPartyStateChanged);
	}

	/**
	 * Read the {@code version} from runelite-plugin.properties (placed on the
	 * classpath by Gradle), so the footer always reflects the declared version.
	 */
	private static String readPluginVersion()
	{
		try (InputStream in = OSPartyPanel.class.getResourceAsStream("/runelite-plugin.properties"))
		{
			if (in != null)
			{
				Properties props = new Properties();
				props.load(in);
				String v = props.getProperty("version");
				if (v != null && !v.trim().isEmpty())
				{
					return v.trim();
				}
			}
		}
		catch (Exception ignored)
		{
			// Fall through to the placeholder below.
		}
		return "?";
	}

	/** Footer: a GitHub link button on the left and the plugin version on the right. */
	private JPanel buildFooter()
	{
		JPanel footer = new JPanel(new BorderLayout());
		footer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		footer.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		JButton github = new JButton();
		github.setToolTipText("Open the OSParty GitHub page");
		github.setFocusPainted(false);
		github.setBorder(BorderFactory.createEmptyBorder());
		github.setContentAreaFilled(false);
		github.setCursor(new Cursor(Cursor.HAND_CURSOR));
		github.setHorizontalAlignment(SwingConstants.LEFT);
		BufferedImage logo = ImageUtil.loadImageResource(getClass(), "/net/osparty/icons/github.png");
		if (logo != null)
		{
			BufferedImage scaled = ImageUtil.resizeImage(logo, 16, 16);
			github.setIcon(new ImageIcon(scaled));
			github.setText(" GitHub");
		}
		else
		{
			github.setText("GitHub");
		}
		github.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		github.setFont(FontManager.getRunescapeSmallFont());
		github.setPreferredSize(new Dimension(80, 20));
		github.addActionListener(e -> LinkBrowser.browse(GITHUB_URL));
		footer.add(github, BorderLayout.WEST);

		JLabel version = new JLabel("v" + VERSION);
		version.setHorizontalAlignment(SwingConstants.RIGHT);
		version.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		version.setFont(FontManager.getRunescapeSmallFont());
		footer.add(version, BorderLayout.EAST);

		return footer;
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
		liveParty.hostParty(party.getPassphrase(), party.getHost(), party.getActivity(), party.getCapacity(), false);
		partyState.setHosting(party);
	}

	private void onPartyStateChanged()
	{
		boolean inParty = partyState.isInParty();
		currentTab.setVisible(inParty);

		if (inParty && !wasInParty)
		{
			// Just entered a party — jump to the Current tab.
			tabGroup.select(currentTab);
		}
		else if (!inParty && wasInParty && currentTab.isSelected())
		{
			// Left the party while viewing it — fall back to Search.
			tabGroup.select(searchTab);
		}

		wasInParty = inParty;
		tabGroup.revalidate();
		tabGroup.repaint();
	}
}
