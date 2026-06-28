package net.osparty.ui;

import net.osparty.KillcountService;
import net.osparty.OSPartyConfig;
import net.osparty.WorldPinger;
import net.osparty.api.PartyService;
import net.osparty.model.AccountTypes;
import net.osparty.model.Activity;
import net.osparty.model.LootRule;
import net.osparty.model.Party;
import net.osparty.model.Role;
import net.osparty.party.LiveParty;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import net.runelite.api.vars.AccountType;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.ui.FontManager;
import net.runelite.http.api.worlds.WorldRegion;

/**
 * "Search" tab: pick an activity, query the queue API for open parties, and
 * apply to one. A player can be in only one party at a time - applying leaves
 * the current party first (disbanding it if you were the host). Full parties
 * and your own party aren't appliable.
 */
class SearchPanel extends JPanel
{
	private static final long COOLDOWN_MS = 30_000;

	/** World regions we know flags for, in display order. */
	private static final WorldRegion[] KNOWN_REGIONS = {
		WorldRegion.UNITED_STATES_OF_AMERICA,
		WorldRegion.UNITED_KINGDOM,
		WorldRegion.AUSTRALIA,
		WorldRegion.GERMANY,
		WorldRegion.BRAZIL,
		WorldRegion.JAPAN,
		WorldRegion.SINGAPORE,
		WorldRegion.SOUTH_AFRICA,
	};

	private final PartyService partyService;
	private final Supplier<String> playerNameSupplier;
	private final Supplier<String> friendsChatOwnerSupplier;
	private final IntSupplier worldSupplier;
	private final PartyState partyState;
	private final LiveParty liveParty;
	private final Supplier<AccountType> accountTypeSupplier;
	private final Supplier<int[]> mapRegionsSupplier;
	private final IntFunction<WorldRegion> worldRegionResolver;
	private final KillcountService killcountService;
	private final ConfigManager configManager;
	private final WorldPinger worldPinger;
	private final IntFunction<String> worldAddressResolver;

	private final JPanel activityListPanel = new JPanel();
	private final Set<Activity> selectedActivities = EnumSet.allOf(Activity.class);
	private final Set<Role> selectedRoles = EnumSet.noneOf(Role.class);
	private boolean rolesExpanded;
	private final JButton roleToggle = new JButton();
	private final JTabbedPane roleTabs = new JTabbedPane();
	/** Collapsible content: the role tabs plus the separate learner mark. */
	private final JPanel roleContent = new JPanel();
	private final Set<WorldRegion> selectedRegions = EnumSet.allOf(WorldRegion.class);
	private boolean regionsExpanded;
	private final JButton regionToggle = new JButton();
	private final JPanel regionContent = new JPanel();
	/** Self-mark (not a role): tags us as a learner to the host when we apply. */
	private final JCheckBox imLearnerCheck = new JCheckBox("I'm a learner");
	private Activity recommended;
	private boolean searchExpanded;
	private final JButton searchToggle = new JButton();
	private final JPanel searchContent = new JPanel();
	private final JTextField textField = new JTextField();
	private final JTextField maxPingField = new JTextField();

	private final JComboBox<String> lootFilter = new JComboBox<>(new String[]{"Any loot", "FFA", "Split"});
	private final JCheckBox ironmanFilter = new JCheckBox("Ironman parties only");
	private final JTextField codeField = new JTextField();
	private final JButton joinButton = new JButton("Join");
	private final JButton searchButton = new JButton("Refresh");
	private final JLabel statusLabel = new JLabel();
	private final JPanel resultsPanel = new JPanel();

	private List<Party> lastResults;
	private String renderedSignature;
	private Timer autoRefreshTimer;

	private final Map<String, Long> cooldownExpiry = new HashMap<>();
	private final Map<String, JButton> applyButtons = new HashMap<>();
	private final Map<String, Party> partiesById = new HashMap<>();

	private Timer uiTimer;

	SearchPanel(PartyService partyService, Supplier<String> playerNameSupplier,
		Supplier<String> friendsChatOwnerSupplier, IntSupplier worldSupplier, PartyState partyState,
		LiveParty liveParty, Supplier<AccountType> accountTypeSupplier, Supplier<int[]> mapRegionsSupplier,
		IntFunction<WorldRegion> worldRegionResolver, KillcountService killcountService, ConfigManager configManager,
		WorldPinger worldPinger, IntFunction<String> worldAddressResolver)
	{
		this.partyService = partyService;
		this.playerNameSupplier = playerNameSupplier;
		this.friendsChatOwnerSupplier = friendsChatOwnerSupplier;
		this.worldSupplier = worldSupplier;
		this.partyState = partyState;
		this.liveParty = liveParty;
		this.accountTypeSupplier = accountTypeSupplier;
		this.mapRegionsSupplier = mapRegionsSupplier;
		this.worldRegionResolver = worldRegionResolver;
		this.killcountService = killcountService;
		this.configManager = configManager;
		this.worldPinger = worldPinger;
		this.worldAddressResolver = worldAddressResolver;

		// Restore the filters the player last used, before the UI is built from them.
		loadFilters();

		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel north = new JPanel();
		north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
		north.setBackground(ColorScheme.DARK_GRAY_COLOR);
		north.add(buildActivityFilter());
		north.add(buildRoleFilter());
		north.add(buildTextFilter());
		north.add(buildRegionFilter());
		north.add(buildControls());
		north.add(buildJoinByCode());
		add(north, BorderLayout.NORTH);

		resultsPanel.setLayout(new BoxLayout(resultsPanel, BoxLayout.Y_AXIS));
		resultsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Wrap results so a long party list scrolls within the tab rather than
		// growing the whole side-panel. Tracks the viewport width so cards never
		// exceed it (otherwise a wide card pushes its Apply button off the edge).
		JPanel resultsWrap = new ScrollableColumn(new BorderLayout());
		resultsWrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		resultsWrap.add(resultsPanel, BorderLayout.NORTH);

		JScrollPane scroll = new JScrollPane(resultsWrap,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);

		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		add(statusLabel, BorderLayout.SOUTH);

		searchButton.setToolTipText("Refresh the list of open parties");
		searchButton.addActionListener(e -> search());

		// Auto-refresh every 10s while the tab is visible, and re-check whether we've
		// moved near a different activity.
		autoRefreshTimer = new Timer(10_000, e -> {
			if (isShowing())
			{
				applyRecommendation();
				search();
			}
		});
		autoRefreshTimer.start();

		// Populate as soon as the Search tab is opened.
		addAncestorListener(new AncestorListener()
		{
			@Override
			public void ancestorAdded(AncestorEvent event)
			{
				applyRecommendation();
				search();
				updateJoinButton();
			}

			@Override
			public void ancestorRemoved(AncestorEvent event)
			{
			}

			@Override
			public void ancestorMoved(AncestorEvent event)
			{
			}
		});

		// React to party changes made elsewhere (e.g. leaving from the Current tab).
		partyState.addListener(() -> {
			updateAllButtons();
			maybeStartTimer();
		});

		// Keep the Apply/Join buttons in step with login state (cheap, no network),
		// so logging in/out promptly enables or disables them.
		new Timer(1_000, e -> {
			if (isShowing())
			{
				updateAllButtons();
			}
		}).start();

		updateJoinButton();
	}

	private JPanel buildControls()
	{
		JPanel controls = cappedRow(new BorderLayout(0, 6));
		controls.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		searchButton.setFocusPainted(false);
		controls.add(searchButton, BorderLayout.CENTER);
		return controls;
	}

	/** The activity multiselect: a checkbox per activity (all checked by default). */
	private JPanel buildActivityFilter()
	{
		JPanel panel = cappedRow(new BorderLayout(0, 4));

		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel label = new JLabel("Activities");
		label.setForeground(ColorScheme.BRAND_ORANGE);
		label.setFont(label.getFont().deriveFont(Font.BOLD));
		header.add(label, BorderLayout.WEST);

		JPanel toggles = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		toggles.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JButton all = miniButton("All");
		all.addActionListener(e -> setAllActivities(true));
		JButton none = miniButton("None");
		none.addActionListener(e -> setAllActivities(false));
		toggles.add(all);
		toggles.add(none);
		header.add(toggles, BorderLayout.EAST);

		panel.add(header, BorderLayout.NORTH);

		activityListPanel.setLayout(new BoxLayout(activityListPanel, BoxLayout.Y_AXIS));
		activityListPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		rebuildActivityList();

		JScrollPane scroll = new JScrollPane(activityListPanel,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		// Show ~6 rows; the rest scroll.
		scroll.setPreferredSize(new Dimension(10, 120));
		panel.add(scroll, BorderLayout.CENTER);
		return panel;
	}

	/** RuneLite's config-section caret (grey), pointing right when collapsed / down when expanded. */
	private static final ImageIcon CARET_COLLAPSED = caret(0);
	private static final ImageIcon CARET_EXPANDED = caret(Math.PI / 2);

	private static ImageIcon caret(double rotation)
	{
		BufferedImage arrow = ImageUtil.loadImageResource(SearchPanel.class, "/util/arrow_right.png");
		if (arrow == null)
		{
			return null;
		}
		BufferedImage grey = ImageUtil.luminanceOffset(arrow, -121);
		if (rotation != 0)
		{
			grey = ImageUtil.rotateImage(grey, rotation);
		}
		return new ImageIcon(grey);
	}

	/**
	 * Style a collapsible section header like RuneLite's config sections: bold orange
	 * title, a grey caret (set per state), and a separator line beneath. Hand cursor.
	 */
	private static void styleCollapsibleHeader(JButton toggle)
	{
		toggle.setHorizontalAlignment(SwingConstants.LEFT);
		toggle.setFocusPainted(false);
		toggle.setContentAreaFilled(false);
		toggle.setForeground(ColorScheme.BRAND_ORANGE);
		toggle.setFont(new JLabel().getFont().deriveFont(Font.BOLD));
		toggle.setIconTextGap(6);
		toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		toggle.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(3, 0, 4, 0)));
	}

	/**
	 * The role multiselect (ToB/CoX): tick the roles you're willing to fill; none
	 * ticked means no role constraint. Collapses to a single header row to save space.
	 */
	private JPanel buildRoleFilter()
	{
		JPanel panel = cappedRow(new BorderLayout(0, 4));
		panel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		// Collapsible header: clicking it shows/hides the (fairly tall) tabbed picker.
		styleCollapsibleHeader(roleToggle);
		roleToggle.addActionListener(e -> setRolesExpanded(!rolesExpanded));
		panel.add(roleToggle, BorderLayout.NORTH);

		// One tab per activity + difficulty so a CM pick can't be confused with a
		// normal CoX one (or an HMT pick with a normal ToB one).
		roleTabs.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		roleTabs.addTab("ToB", buildRoleTab(Activity.THEATRE_OF_BLOOD, false));
		roleTabs.addTab("HMT", buildRoleTab(Activity.THEATRE_OF_BLOOD, true));
		roleTabs.addTab("CoX", buildRoleTab(Activity.CHAMBERS_OF_XERIC, false));
		roleTabs.addTab("CM", buildRoleTab(Activity.CHAMBERS_OF_XERIC, true));
		roleTabs.setAlignmentX(Component.LEFT_ALIGNMENT);

		// A learner mark separate from the roles: re-broadcasts live if toggled while
		// already applied; otherwise it's read when we apply (see doApply).
		imLearnerCheck.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		imLearnerCheck.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		imLearnerCheck.setFont(FontManager.getRunescapeSmallFont());
		imLearnerCheck.setFocusPainted(false);
		imLearnerCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
		imLearnerCheck.setBorder(BorderFactory.createEmptyBorder(6, 4, 2, 4));
		imLearnerCheck.addActionListener(e -> {
			if (isMemberInParty())
			{
				liveParty.setLocalLearner(imLearnerCheck.isSelected());
			}
			persistFilters();
		});

		roleContent.setLayout(new BoxLayout(roleContent, BoxLayout.Y_AXIS));
		roleContent.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		roleContent.add(roleTabs);
		roleContent.add(imLearnerCheck);
		roleContent.setVisible(rolesExpanded);
		panel.add(roleContent, BorderLayout.CENTER);

		updateRoleToggleText();
		return panel;
	}

	private void setRolesExpanded(boolean expanded)
	{
		rolesExpanded = expanded;
		roleContent.setVisible(expanded);
		updateRoleToggleText();
		persistFilters();
		revalidate();
		repaint();
	}

	private void updateRoleToggleText()
	{
		roleToggle.setIcon(rolesExpanded ? CARET_EXPANDED : CARET_COLLAPSED);
		roleToggle.setText("Roles");
	}

	private JComponent buildRoleTab(Activity activity, boolean hardMode)
	{
		JPanel box = new JPanel();
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

		for (Role role : activity.filterRoles(hardMode))
		{
			JCheckBox check = new JCheckBox(role.getDisplayName());
			check.setSelected(selectedRoles.contains(role));
			check.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			check.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			check.setFont(FontManager.getRunescapeSmallFont());
			check.setFocusPainted(false);
			check.setMargin(new Insets(0, 0, 0, 0));
			check.setIconTextGap(4);
			check.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
			check.setAlignmentX(Component.LEFT_ALIGNMENT);
			check.addActionListener(e -> {
				if (check.isSelected())
				{
					selectedRoles.add(role);
				}
				else
				{
					selectedRoles.remove(role);
				}
				updateRoleToggleText();
				filtersChanged();
			});
			box.add(check);
		}
		return box;
	}

	/**
	 * Whether a party passes the role filter. Non-role parties always pass. Each
	 * activity's box is independent: with nothing ticked in that box, parties of
	 * that activity pass. Otherwise the party must still need one of the ticked
	 * roles - with "Fill / Any" meaning "I'll do any role", and a party's own Fill
	 * slot accepting anyone.
	 */
	private boolean matchesRoleFilter(Party party, Activity activity)
	{
		if (activity == null || !activity.hasRoles())
		{
			return true;
		}
		// Only consider ticks that belong to this activity's box (normal + CM roles).
		Set<Role> picked = EnumSet.noneOf(Role.class);
		for (Role role : activity.allFilterRoles())
		{
			if (selectedRoles.contains(role))
			{
				picked.add(role);
			}
		}
		if (picked.isEmpty())
		{
			return true; // this activity's box is unconstrained
		}
		List<String> needed = neededRolesOf(party);
		if (needed == null || needed.isEmpty())
		{
			return true; // no role info on the ad - don't over-filter
		}
		// The party's difficulty selects which wildcard/Fill applies (a CM party uses
		// the CM Fill, an HMT searcher's "any" is the HMT one, etc.).
		Role any = activity.anyRole(party.isHardMode());
		if (any != null && picked.contains(any))
		{
			return true; // "I'll do any role"
		}
		Role fill = activity.fillRole(party.isHardMode());
		if (fill != null && needed.contains(fill.getId()))
		{
			return true; // an advertised Fill slot accepts anyone
		}
		for (Role role : picked)
		{
			if (needed.contains(role.getId()))
			{
				return true;
			}
		}
		return false;
	}

	/** The roles still open on an ad: the live {@code neededRoles}, else the full comp. */
	private static List<String> neededRolesOf(Party party)
	{
		if (party.getNeededRoles() != null && !party.getNeededRoles().isEmpty())
		{
			return party.getNeededRoles();
		}
		return party.getRequiredRoles();
	}

	private JPanel buildTextFilter()
	{
		JPanel panel = cappedRow(new BorderLayout(0, 4));
		panel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		// Collapsible header, mirroring the roles section.
		styleCollapsibleHeader(searchToggle);
		searchToggle.addActionListener(e -> setSearchExpanded(!searchExpanded));
		panel.add(searchToggle, BorderLayout.NORTH);

		textField.setToolTipText("Filter by host, description or activity");
		textField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				reapplyFilters();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				reapplyFilters();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				reapplyFilters();
			}
		});

		// The text field plus the loot and ironman filters all live under "Search".
		lootFilter.addActionListener(e -> filtersChanged());
		ironmanFilter.setBackground(ColorScheme.DARK_GRAY_COLOR);
		ironmanFilter.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		ironmanFilter.setFocusPainted(false);
		ironmanFilter.addActionListener(e -> filtersChanged());

		maxPingField.setToolTipText("Hide parties whose world ping exceeds this threshold (ms). Leave blank for no limit.");
		maxPingField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				filtersChanged();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				filtersChanged();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				filtersChanged();
			}
		});

		JLabel maxPingLabel = new JLabel("Max ping (ms)");
		maxPingLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		maxPingLabel.setFont(FontManager.getRunescapeSmallFont());
		JPanel maxPingRow = new JPanel(new BorderLayout(4, 0));
		maxPingRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		maxPingRow.add(maxPingLabel, BorderLayout.WEST);
		maxPingRow.add(maxPingField, BorderLayout.CENTER);

		searchContent.setLayout(new BoxLayout(searchContent, BoxLayout.Y_AXIS));
		searchContent.setBackground(ColorScheme.DARK_GRAY_COLOR);
		searchContent.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		searchContent.add(searchRow(textField));
		searchContent.add(searchRow(lootFilter));
		searchContent.add(searchRow(ironmanFilter));
		searchContent.add(searchRow(maxPingRow));
		searchContent.setVisible(searchExpanded);
		panel.add(searchContent, BorderLayout.CENTER);

		updateSearchToggleText();
		return panel;
	}

	/** One full-width, height-capped row in the Search content column. */
	private static JPanel searchRow(Component control)
	{
		JPanel row = cappedRow(new BorderLayout());
		row.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		row.add(control, BorderLayout.CENTER);
		return row;
	}

	private void setSearchExpanded(boolean expanded)
	{
		searchExpanded = expanded;
		searchContent.setVisible(expanded);
		updateSearchToggleText();
		persistFilters();
		revalidate();
		repaint();
	}

	private void updateSearchToggleText()
	{
		searchToggle.setIcon(searchExpanded ? CARET_EXPANDED : CARET_COLLAPSED);
		searchToggle.setText("Search");
	}

	/**
	 * Collapsible region multi-select: a 2-column grid of flag checkboxes for each
	 * known world region. All ticked by default (no filter). Deselecting a region
	 * hides parties whose host world belongs to that region. Unchecked regions show
	 * a dimmed flag so the on/off state is visually distinct.
	 */
	private JPanel buildRegionFilter()
	{
		JPanel panel = cappedRow(new BorderLayout(0, 4));
		panel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		styleCollapsibleHeader(regionToggle);
		updateRegionToggleText();
		regionToggle.addActionListener(e -> setRegionsExpanded(!regionsExpanded));
		panel.add(regionToggle, BorderLayout.NORTH);

		JPanel grid = new JPanel(new GridLayout(0, 2, 2, 2));
		grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		grid.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));

		for (WorldRegion region : KNOWN_REGIONS)
		{
			JCheckBox check = new JCheckBox(shortNameOf(region));
			check.setSelected(selectedRegions.contains(region));
			check.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			check.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			check.setFont(FontManager.getRunescapeSmallFont());
			check.setFocusPainted(false);
			check.setMargin(new Insets(0, 0, 0, 0));
			check.setIconTextGap(2);
			ImageIcon flag = WorldFlags.forRegion(region);
			if (flag != null)
			{
				// Bright flag = region included; dimmed flag = region filtered out.
				check.setSelectedIcon(flag);
				check.setIcon(dimIcon(flag));
			}
			check.addActionListener(e -> {
				if (check.isSelected())
				{
					selectedRegions.add(region);
				}
				else
				{
					selectedRegions.remove(region);
				}
				updateRegionToggleText();
				filtersChanged();
			});
			grid.add(check);
		}

		regionContent.setLayout(new BorderLayout());
		regionContent.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		regionContent.add(grid);
		regionContent.setVisible(regionsExpanded);
		panel.add(regionContent, BorderLayout.CENTER);

		return panel;
	}

	private void setRegionsExpanded(boolean expanded)
	{
		regionsExpanded = expanded;
		regionContent.setVisible(expanded);
		updateRegionToggleText();
		persistFilters();
		revalidate();
		repaint();
	}

	private void updateRegionToggleText()
	{
		regionToggle.setIcon(regionsExpanded ? CARET_EXPANDED : CARET_COLLAPSED);
		int deselected = 0;
		for (WorldRegion r : KNOWN_REGIONS)
		{
			if (!selectedRegions.contains(r))
			{
				deselected++;
			}
		}
		regionToggle.setText(deselected == 0 ? "Regions"
			: "Regions (" + (KNOWN_REGIONS.length - deselected) + "/" + KNOWN_REGIONS.length + ")");
	}

	/**
	 * Produce a semi-transparent (dimmed) copy of {@code source} to indicate a
	 * deselected/inactive state on region checkboxes.
	 */
	private static ImageIcon dimIcon(ImageIcon source)
	{
		if (source == null)
		{
			return null;
		}
		java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
			source.getIconWidth(), source.getIconHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = out.createGraphics();
		g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.30f));
		g.drawImage(source.getImage(), 0, 0, null);
		g.dispose();
		return new ImageIcon(out);
	}

	private static String shortNameOf(WorldRegion region)
	{
		switch (region)
		{
			case UNITED_STATES_OF_AMERICA: return "US";
			case UNITED_KINGDOM: return "UK";
			case AUSTRALIA: return "AU";
			case GERMANY: return "DE";
			case BRAZIL: return "BR";
			case JAPAN: return "JP";
			case SINGAPORE: return "SG";
			case SOUTH_AFRICA: return "ZA";
			default: return region.name().substring(0, Math.min(2, region.name().length()));
		}
	}

	private void setAllActivities(boolean selected)
	{
		if (selected)
		{
			selectedActivities.addAll(EnumSet.allOf(Activity.class));
		}
		else
		{
			selectedActivities.clear();
		}
		rebuildActivityList();
		filtersChanged();
	}

	private JButton miniButton(String text)
	{
		JButton button = new JButton(text);
		button.setFocusPainted(false);
		button.setMargin(new Insets(0, 6, 0, 6));
		button.setFont(FontManager.getRunescapeSmallFont());
		return button;
	}

	/** Rebuild the checkbox list, recommended activity first and pre-checked. */
	private void rebuildActivityList()
	{
		activityListPanel.removeAll();
		for (Activity activity : orderedActivities())
		{
			boolean nearby = activity == recommended;
			JCheckBox box = new JCheckBox(activity.getDisplayName() + (nearby ? "  (nearby)" : ""));
			box.setSelected(selectedActivities.contains(activity));
			box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			box.setForeground(nearby ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
			box.setFont(FontManager.getRunescapeSmallFont());
			box.setFocusPainted(false);
			box.setAlignmentX(Component.LEFT_ALIGNMENT);
			box.addActionListener(e -> {
				if (box.isSelected())
				{
					selectedActivities.add(activity);
				}
				else
				{
					selectedActivities.remove(activity);
				}
				filtersChanged();
			});
			activityListPanel.add(box);
		}
		activityListPanel.revalidate();
		activityListPanel.repaint();
	}

	/** Activities with the nearby one (if any) floated to the top. */
	private List<Activity> orderedActivities()
	{
		List<Activity> ordered = new ArrayList<>();
		if (recommended != null)
		{
			ordered.add(recommended);
		}
		for (Activity activity : Activity.values())
		{
			if (activity != recommended)
			{
				ordered.add(activity);
			}
		}
		return ordered;
	}

	/**
	 * If the player is standing near an activity, float it to the top of the list
	 * and make sure it's checked. No-op when the nearby activity hasn't changed.
	 */
	private void applyRecommendation()
	{
		Activity near = Activity.nearby(mapRegionsSupplier.get());
		if (near == recommended)
		{
			return;
		}
		recommended = near;
		if (near != null)
		{
			selectedActivities.add(near);
		}
		rebuildActivityList();
		reapplyFilters();
	}

	private JPanel buildJoinByCode()
	{
		JPanel panel = cappedRow(new BorderLayout(6, 4));
		panel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		JLabel label = new JLabel("Join private party by code");
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());

		joinButton.setFocusPainted(false);
		joinButton.addActionListener(e -> joinByCode());
		codeField.addActionListener(e -> joinByCode());

		panel.add(label, BorderLayout.NORTH);
		panel.add(codeField, BorderLayout.CENTER);
		panel.add(joinButton, BorderLayout.EAST);
		return panel;
	}

	private static JPanel cappedRow(LayoutManager layout)
	{
		JPanel panel = new JPanel(layout)
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		return panel;
	}

	private void reapplyFilters()
	{
		if (lastResults != null)
		{
			showResults(lastResults);
		}
	}

	/** A filter changed: save the new selection and re-render the results. */
	private void filtersChanged()
	{
		persistFilters();
		reapplyFilters();
	}

	// ---- filter persistence (remembered across sessions) ---------------------

	private static final String KEY_ACTIVITIES = "searchActivities";
	private static final String KEY_ROLES = "searchRoles";
	private static final String KEY_LOOT = "searchLoot";
	private static final String KEY_IRONMAN = "searchIronman";
	private static final String KEY_LEARNER = "searchLearner";
	private static final String KEY_ROLES_EXPANDED = "searchRolesExpanded";
	private static final String KEY_SEARCH_EXPANDED = "searchTextExpanded";
	private static final String KEY_REGIONS = "searchRegions";
	private static final String KEY_REGIONS_EXPANDED = "searchRegionsExpanded";
	private static final String KEY_MAX_PING = "searchMaxPing";

	/** Save the current filter selection so it's restored next session. */
	private void persistFilters()
	{
		put(KEY_ACTIVITIES, idsOf(selectedActivities, Activity::getId));
		put(KEY_ROLES, idsOf(selectedRoles, Role::getId));
		put(KEY_LOOT, (String) lootFilter.getSelectedItem());
		put(KEY_IRONMAN, Boolean.toString(ironmanFilter.isSelected()));
		put(KEY_LEARNER, Boolean.toString(imLearnerCheck.isSelected()));
		put(KEY_ROLES_EXPANDED, Boolean.toString(rolesExpanded));
		put(KEY_SEARCH_EXPANDED, Boolean.toString(searchExpanded));
		// Save which known regions are selected (non-KNOWN regions are always on).
		StringBuilder regionsStr = new StringBuilder();
		for (WorldRegion r : KNOWN_REGIONS)
		{
			if (selectedRegions.contains(r))
			{
				if (regionsStr.length() > 0)
				{
					regionsStr.append(',');
				}
				regionsStr.append(r.name());
			}
		}
		put(KEY_REGIONS, regionsStr.toString());
		put(KEY_REGIONS_EXPANDED, Boolean.toString(regionsExpanded));
		put(KEY_MAX_PING, maxPingField.getText().trim());
	}

	/** Restore the saved filter selection into the in-memory state and the controls. */
	private void loadFilters()
	{
		String activities = get(KEY_ACTIVITIES);
		if (activities != null)
		{
			selectedActivities.clear();
			for (String id : activities.split(","))
			{
				Activity activity = Activity.fromId(id);
				if (activity != null)
				{
					selectedActivities.add(activity);
				}
			}
		}

		String roles = get(KEY_ROLES);
		if (roles != null)
		{
			selectedRoles.clear();
			for (String id : roles.split(","))
			{
				Role role = Role.fromId(id);
				if (role != null)
				{
					selectedRoles.add(role);
				}
			}
		}

		String loot = get(KEY_LOOT);
		if (loot != null)
		{
			lootFilter.setSelectedItem(loot);
		}
		ironmanFilter.setSelected(Boolean.parseBoolean(get(KEY_IRONMAN)));
		imLearnerCheck.setSelected(Boolean.parseBoolean(get(KEY_LEARNER)));
		rolesExpanded = Boolean.parseBoolean(get(KEY_ROLES_EXPANDED));
		searchExpanded = Boolean.parseBoolean(get(KEY_SEARCH_EXPANDED));

		// Regions: start from allOf, then remove any KNOWN region the user deselected.
		String regionsStr = get(KEY_REGIONS);
		if (regionsStr != null && !regionsStr.isEmpty())
		{
			java.util.Set<String> savedNames = new java.util.HashSet<>();
			for (String name : regionsStr.split(","))
			{
				savedNames.add(name.trim());
			}
			for (WorldRegion r : KNOWN_REGIONS)
			{
				if (!savedNames.contains(r.name()))
				{
					selectedRegions.remove(r);
				}
			}
		}
		regionsExpanded = Boolean.parseBoolean(get(KEY_REGIONS_EXPANDED));

		String maxPing = get(KEY_MAX_PING);
		if (maxPing != null && !maxPing.isEmpty())
		{
			maxPingField.setText(maxPing);
		}
	}

	private static <T> String idsOf(Set<T> values, java.util.function.Function<T, String> id)
	{
		StringBuilder sb = new StringBuilder();
		for (T value : values)
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(id.apply(value));
		}
		return sb.toString();
	}

	private void put(String key, String value)
	{
		configManager.setConfiguration(OSPartyConfig.GROUP, key, value == null ? "" : value);
	}

	private String get(String key)
	{
		return configManager.getConfiguration(OSPartyConfig.GROUP, key);
	}

	private void joinByCode()
	{
		String code = codeField.getText().trim();
		if (code.isEmpty())
		{
			setStatus("Enter an invite code.");
			return;
		}
		if (playerNameSupplier.get() == null)
		{
			setStatus("Log in before joining.");
			return;
		}
		setStatus("Looking up code " + code + "...");
		partyService.getPartyByCode(code,
			party -> SwingUtilities.invokeLater(() -> joinFetched(party)),
			error -> SwingUtilities.invokeLater(() -> setStatus("No party found for code " + code + ".")));
	}

	private void joinFetched(Party party)
	{
		if (party == null)
		{
			setStatus("No party found for that code.");
			return;
		}
		if (isOwnParty(party))
		{
			setStatus("That's your own party.");
			return;
		}
		if (!meetsIronmanRule(party))
		{
			setStatus("That party is for ironman accounts.");
			return;
		}
		if (kcStatus(party) == KcStatus.BELOW)
		{
			setStatus("You don't meet that party's minimum killcount.");
			return;
		}

		Activity activity = Activity.fromId(party.getActivity());
		String role = null;
		if (activity != null && activity.hasRoles())
		{
			role = promptForRole(party, activity);
			if (role == null)
			{
				return; // cancelled the role dialog
			}
		}

		codeField.setText("");
		final String chosenRole = role;
		leaveCurrentThen(() -> doApply(party, chosenRole));
	}

	private boolean meetsIronmanRule(Party party)
	{
		return !party.isIronmanOnly() || AccountTypes.isIronman(accountTypeSupplier.get());
	}

	private enum KcStatus
	{
		/** Meets the requirement (or there is none / it can't be checked). */
		OK,
		/** Hiscore lookup in progress; not yet known. */
		PENDING,
		/** Known to be below the required killcount. */
		BELOW
	}

	/**
	 * Whether the local player meets a party's minimum-killcount requirement, looked
	 * up from the hiscores. Only blocks when we positively know they're below; an
	 * unranked/unknown killcount (-1) doesn't block (the host still sees it and can
	 * decide). Triggers an async lookup the first time, refreshing buttons when done.
	 */
	private KcStatus kcStatus(Party party)
	{
		Activity activity = Activity.fromId(party.getActivity());
		int minKc = party.getMinKillCount();
		int minHard = activity != null && activity.hasHardMode() ? party.getMinHardModeKillCount() : 0;
		if ((minKc <= 0 && minHard <= 0) || activity == null)
		{
			return KcStatus.OK;
		}
		String me = playerNameSupplier.get();
		if (me == null)
		{
			return KcStatus.OK; // login gating handles this
		}
		KillcountService.Killcount kc = killcountService.cached(me, activity);
		if (kc == null)
		{
			killcountService.lookup(me, activity, this::updateAllButtons);
			return KcStatus.PENDING;
		}
		boolean below = (minKc > 0 && kc.killCount >= 0 && kc.killCount < minKc)
			|| (minHard > 0 && kc.hardModeKillCount >= 0 && kc.hardModeKillCount < minHard);
		return below ? KcStatus.BELOW : KcStatus.OK;
	}

	private LootRule lootFilterValue()
	{
		String selected = (String) lootFilter.getSelectedItem();
		if ("FFA".equals(selected))
		{
			return LootRule.FFA;
		}
		if ("Split".equals(selected))
		{
			return LootRule.SPLIT;
		}
		return null; // "Any loot"
	}

	/** A combined "Split , Ironman only , Host HCIM" tag line for a card, or null. */
	private String tagLine(Party party)
	{
		List<String> tags = new ArrayList<>();
		if (party.isLearnerRaid())
		{
			tags.add(party.learnerLabel());
		}
		LootRule loot = LootRule.fromName(party.getLootRule());
		if (loot != LootRule.UNSPECIFIED)
		{
			tags.add(loot.getDisplayName());
		}
		if (party.isIronmanOnly())
		{
			tags.add("Ironman only");
		}
		// The host's account type is shown as a badge next to their name, not text.
		return tags.isEmpty() ? null : String.join(", ", tags);
	}

	private void search()
	{
		String player = playerNameSupplier.get();
		// Fetch every open party; we filter client-side by the checked activities,
		// loot, ironman and free text. No pre-clear, so refreshes don't flicker.
		partyService.searchParties(null, player,
			parties -> SwingUtilities.invokeLater(() -> showResults(parties)),
			error -> SwingUtilities.invokeLater(() -> setStatus("Refresh failed: " + error.getMessage())));
	}

	private void showResults(List<Party> parties)
	{
		lastResults = parties;

		LootRule wantLoot = lootFilterValue();
		boolean ironOnly = ironmanFilter.isSelected();
		String text = textField.getText().trim().toLowerCase();
		int maxPing = parseMaxPing();

		// Region filter is active when at least one known region is deselected.
		boolean regionFilterActive = false;
		for (WorldRegion r : KNOWN_REGIONS)
		{
			if (!selectedRegions.contains(r))
			{
				regionFilterActive = true;
				break;
			}
		}

		// Show only joinable parties matching every active filter (full ones hidden).
		List<Party> visible = new ArrayList<>();
		if (parties != null)
		{
			for (Party party : parties)
			{
				if (party.isFull())
				{
					continue;
				}
				Activity act = Activity.fromId(party.getActivity());
				if (act == null || !selectedActivities.contains(act))
				{
					continue;
				}
				if (wantLoot != null && LootRule.fromName(party.getLootRule()) != wantLoot)
				{
					continue;
				}
				if (ironOnly && !party.isIronmanOnly())
				{
					continue;
				}
				if (!matchesRoleFilter(party, act))
				{
					continue;
				}
				if (!text.isEmpty() && !matchesText(party, act, text))
				{
					continue;
				}

				Integer worldNum = parseWorldNum(party);

				// Region filter: skip parties whose host world has a deselected region.
				if (regionFilterActive && worldNum != null)
				{
					WorldRegion region = worldRegionResolver.apply(worldNum);
					if (region != null && !selectedRegions.contains(region))
					{
						continue;
					}
				}

				// Max ping filter: skip parties whose world ping exceeds the threshold.
				// Parties with no known ping yet are always shown (never over-filtered on
				// unknown data); they will be re-evaluated when the ping result arrives.
				if (maxPing > 0 && worldNum != null && worldPinger != null)
				{
					Integer ping = worldPinger.getCachedPing(worldNum);
					if (ping != null && ping >= 0 && ping > maxPing)
					{
						continue;
					}
				}

				visible.add(party);
			}
		}

		// Request pings for all visible parties. No-op if already cached or in flight.
		if (worldPinger != null)
		{
			for (Party party : visible)
			{
				Integer worldNum = parseWorldNum(party);
				if (worldNum != null)
				{
					String address = worldAddressResolver != null ? worldAddressResolver.apply(worldNum) : null;
					worldPinger.requestPing(worldNum, address,
						() -> javax.swing.SwingUtilities.invokeLater(this::reapplyFilters));
				}
			}
		}

		// Skip the rebuild (and its flicker) when nothing rendered would change.
		String signature = selectedSignature() + "|" + roleSignature() + "|" + regionSignature() + "|"
			+ maxPingField.getText().trim() + "|" + text + "|" + pingSignatureOf(visible) + "|" + signatureOf(visible);
		if (signature.equals(renderedSignature))
		{
			return;
		}
		renderedSignature = signature;

		resultsPanel.removeAll();
		applyButtons.clear();
		partiesById.clear();

		if (visible.isEmpty())
		{
			setStatus("No open parties match your filters.");
		}
		else
		{
			setStatus(visible.size() + " open " + (visible.size() == 1 ? "party" : "parties") + ".");
			for (Party party : visible)
			{
				partiesById.put(party.getId(), party);
				resultsPanel.add(buildPartyCard(Activity.fromId(party.getActivity()), party));
				resultsPanel.add(Box.createVerticalStrut(6));
			}
			updateAllButtons();
		}

		resultsPanel.revalidate();
		resultsPanel.repaint();
	}

	private static boolean matchesText(Party party, Activity activity, String lowerQuery)
	{
		if (contains(party.getHost(), lowerQuery) || contains(party.getDescription(), lowerQuery))
		{
			return true;
		}
		return activity != null && contains(activity.getDisplayName(), lowerQuery);
	}

	private static boolean contains(String haystack, String lowerNeedle)
	{
		return haystack != null && haystack.toLowerCase().contains(lowerNeedle);
	}

	/** A signature of the checked-activity set so toggling it triggers a rebuild. */
	private String selectedSignature()
	{
		StringBuilder sb = new StringBuilder();
		for (Activity activity : Activity.values())
		{
			if (selectedActivities.contains(activity))
			{
				sb.append(activity.getId()).append(',');
			}
		}
		return sb.toString();
	}

	/** A signature of the ticked-role set so toggling it triggers a rebuild. */
	private String roleSignature()
	{
		StringBuilder sb = new StringBuilder();
		for (Role role : Role.values())
		{
			if (selectedRoles.contains(role))
			{
				sb.append(role.getId()).append(',');
			}
		}
		return sb.toString();
	}

	/**
	 * A stable signature of the visible parties so unchanged refreshes can no-op.
	 * Includes each party's age in minutes so the "searching Xm" labels stay current.
	 */
	private static String signatureOf(List<Party> parties)
	{
		long now = System.currentTimeMillis();
		StringBuilder sb = new StringBuilder();
		for (Party party : parties)
		{
			sb.append(party.getId()).append(':').append(party.getSize())
				.append('/').append(party.getCapacity())
				.append('w').append(party.getWorld() == null ? "" : party.getWorld())
				.append('L').append(party.getLayout() == null ? "" : party.getLayout())
				.append('R').append(neededRolesOf(party) == null ? "" : neededRolesOf(party))
				.append('d').append(party.isHardMode() ? "h" : "").append(party.getInvocation())
				.append('@').append(ageMinutes(now, party.getCreatedAt())).append(';');
		}
		return sb.toString();
	}

	private static long ageMinutes(long now, long createdAt)
	{
		return createdAt <= 0 ? -1 : Math.max(0, (now - createdAt) / 60_000);
	}

	/** Human-readable age, e.g. "just now", "12m", "1h 5m"; null when unknown. */
	private static String formatAge(long createdAt)
	{
		if (createdAt <= 0)
		{
			return null;
		}
		long mins = ageMinutes(System.currentTimeMillis(), createdAt);
		if (mins < 1)
		{
			return "just now";
		}
		if (mins < 60)
		{
			return mins + "m";
		}
		return (mins / 60) + "h " + (mins % 60) + "m";
	}

	/**
	 * Greedily pack a comma-separated string into lines no longer than {@code max}
	 * characters, so a long value wraps across several single-line label rows.
	 * Continued lines keep a trailing comma to show they run on.
	 */
	private static List<String> wrapByComma(String text, int max)
	{
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String part : text.split(", "))
		{
			if (line.length() == 0)
			{
				line.append(part);
			}
			else if (line.length() + 2 + part.length() <= max)
			{
				line.append(", ").append(part);
			}
			else
			{
				lines.add(line + ",");
				line = new StringBuilder(part);
			}
		}
		if (line.length() > 0)
		{
			lines.add(line.toString());
		}
		return lines;
	}

	private JPanel buildPartyCard(Activity activity, Party party)
	{
		// Cap height dynamically: a fixed maximum computed before the children
		// are added collapses the card under BoxLayout and hides its text.
		JPanel card = new JPanel(new BorderLayout(0, 4))
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel info = new JPanel(new GridLayout(0, 1));
		info.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Activity name first, since the list now mixes activities. The title reflects
		// the raid difficulty (CoX CM / HMT / ToA invocation) when set.
		JLabel activityLabel = new JLabel(activity != null
			? activity.displayName(party.isHardMode(), party.getInvocation())
			: party.getActivity());
		activityLabel.setForeground(Color.WHITE);

		JLabel host = new JLabel(party.getHost() == null ? "Unknown host" : party.getHost());
		host.setForeground(ColorScheme.BRAND_ORANGE);
		host.setFont(FontManager.getRunescapeSmallFont());
		ImageIcon hostIcon = AccountIcons.forType(AccountTypes.fromName(party.getHostAccountType()));
		if (hostIcon != null)
		{
			host.setIcon(hostIcon);
			host.setIconTextGap(4);
		}

		String capacity = party.getCapacity() > 0
			? party.getSize() + "/" + party.getCapacity()
			: String.valueOf(party.getSize());
		StringBuilder sub = new StringBuilder(capacity).append(" players");
		String age = formatAge(party.getCreatedAt());
		if (age != null)
		{
			sub.append(", searching ").append(age);
		}
		JLabel meta = new JLabel(sub.toString());
		meta.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		meta.setFont(FontManager.getRunescapeSmallFont());

		info.add(host);
		JLabel worldLabel = buildWorldLabel(party);
		if (worldLabel != null)
		{
			info.add(worldLabel);
		}
		info.add(meta);

		String tagLine = tagLine(party);
		if (tagLine != null)
		{
			JLabel tags = new JLabel(tagLine);
			tags.setForeground(ColorScheme.BRAND_ORANGE);
			tags.setFont(FontManager.getRunescapeSmallFont());
			info.add(tags);
		}

		String requirement = requirementText(activity, party);
		if (requirement != null)
		{
			JLabel req = new JLabel(requirement);
			req.setForeground(ColorScheme.PROGRESS_INPROGRESS_COLOR);
			req.setFont(FontManager.getRunescapeSmallFont());
			info.add(req);
		}

		// Roles still needed (ToB/CoX), wrapped across rows so the full list shows.
		String needs = neededRolesText(activity, party);
		if (needs != null)
		{
			for (String line : wrapByComma(needs, 30))
			{
				JLabel roles = new JLabel(line);
				roles.setForeground(ColorScheme.BRAND_ORANGE);
				roles.setFont(FontManager.getRunescapeSmallFont());
				info.add(roles);
			}
		}

		if (party.getDescription() != null && !party.getDescription().isEmpty())
		{
			JLabel desc = new JLabel(party.getDescription());
			desc.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			desc.setFont(FontManager.getRunescapeSmallFont());
			info.add(desc);
		}

		// CoX raid layout the host is advertising (kept live via heartbeat). Wrapped
		// across rows by comma so the full rotation is visible, not truncated.
		if (party.getLayout() != null && !party.getLayout().isEmpty())
		{
			for (String line : wrapByComma("Layout: " + party.getLayout(), 30))
			{
				JLabel layout = new JLabel(line);
				layout.setForeground(ColorScheme.PROGRESS_INPROGRESS_COLOR);
				layout.setFont(FontManager.getRunescapeSmallFont());
				info.add(layout);
			}
		}

		JButton applyButton = new JButton("Apply");
		applyButton.setFocusPainted(false);
		// One listener that dispatches by state: cancel the active application,
		// or apply (leaving the current party first if any).
		applyButton.addActionListener(e -> {
			if (isActive(party))
			{
				cancel(party);
			}
			else
			{
				apply(party);
			}
		});
		applyButtons.put(party.getId(), applyButton);

		JPanel buttonWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		buttonWrap.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		buttonWrap.add(applyButton);

		// Header row: activity title takes the available width (the action button is
		// pinned to the right). Keeping the button out of the info column means the
		// "x/y players, searching ..." line gets the full card width and isn't truncated.
		JPanel header = new JPanel(new BorderLayout(6, 0));
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.add(activityLabel, BorderLayout.CENTER);
		header.add(buttonWrap, BorderLayout.EAST);

		card.add(header, BorderLayout.NORTH);
		card.add(info, BorderLayout.CENTER);

		return card;
	}

	/**
	 * A "World 302  ~42ms" label with the host world's country flag, or null when
	 * the ad has no world. The flag is omitted when the region can't be resolved.
	 * The ping is omitted until a cached result is available.
	 */
	private JLabel buildWorldLabel(Party party)
	{
		String raw = party.getWorld();
		if (raw == null || raw.trim().isEmpty())
		{
			return null;
		}
		String digits = raw.replaceAll("\\D", "");
		int worldNum = (!digits.isEmpty() && digits.length() <= 5) ? Integer.parseInt(digits) : -1;

		StringBuilder labelText = new StringBuilder("World ").append(digits.isEmpty() ? raw.trim() : digits);
		if (worldNum > 0 && worldPinger != null)
		{
			Integer ping = worldPinger.getCachedPing(worldNum);
			if (ping != null)
			{
				labelText.append(ping >= 0 ? "  ~" + ping + "ms" : "  (timeout)");
			}
		}

		JLabel label = new JLabel(labelText.toString());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());

		if (worldNum > 0 && worldRegionResolver != null)
		{
			WorldRegion region = worldRegionResolver.apply(worldNum);
			ImageIcon flag = WorldFlags.forRegion(region);
			if (flag != null)
			{
				label.setIcon(flag);
				label.setIconTextGap(4);
			}
		}
		return label;
	}

	/** Parse the world number from a party's world string, or null if not parseable. */
	private static Integer parseWorldNum(Party party)
	{
		String raw = party.getWorld();
		if (raw == null)
		{
			return null;
		}
		String digits = raw.replaceAll("\\D", "");
		if (digits.isEmpty() || digits.length() > 5)
		{
			return null;
		}
		return Integer.parseInt(digits);
	}

	/** Parse the max-ping field value, or -1 if blank or non-numeric. */
	private int parseMaxPing()
	{
		String text = maxPingField.getText().trim();
		if (text.isEmpty())
		{
			return -1;
		}
		try
		{
			return Integer.parseInt(text);
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	/** Signature of the selected regions so deselecting one triggers a re-render. */
	private String regionSignature()
	{
		StringBuilder sb = new StringBuilder();
		for (WorldRegion r : KNOWN_REGIONS)
		{
			if (selectedRegions.contains(r))
			{
				sb.append(r.name()).append(',');
			}
		}
		return sb.toString();
	}

	/**
	 * Signature of cached pings for the visible parties so that a ping arriving
	 * via callback causes a re-render and updates the world label.
	 */
	private String pingSignatureOf(List<Party> parties)
	{
		if (worldPinger == null)
		{
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (Party p : parties)
		{
			Integer wn = parseWorldNum(p);
			if (wn != null)
			{
				Integer ping = worldPinger.getCachedPing(wn);
				sb.append(wn).append(':').append(ping != null ? ping : '?').append(',');
			}
		}
		return sb.toString();
	}

	private String requirementText(Activity activity, Party party)
	{
		if (party.getMinKillCount() <= 0 && party.getMinHardModeKillCount() <= 0)
		{
			return null;
		}

		StringBuilder req = new StringBuilder("Req: ");
		boolean any = false;
		if (party.getMinKillCount() > 0)
		{
			req.append(party.getMinKillCount()).append(" KC");
			any = true;
		}
		if (activity != null && activity.hasHardMode() && party.getMinHardModeKillCount() > 0)
		{
			if (any)
			{
				req.append(", ");
			}
			req.append(party.getMinHardModeKillCount()).append(' ').append(activity.getHardModeLabel()).append(" KC");
		}
		return req.toString();
	}

	/**
	 * A "Needs: North freeze, Range x2" summary of a role party's still-open roles,
	 * or null when the activity has no roles / nothing's open.
	 */
	private static String neededRolesText(Activity activity, Party party)
	{
		if (activity == null || !activity.hasRoles())
		{
			return null;
		}
		List<String> needed = neededRolesOf(party);
		if (needed == null || needed.isEmpty())
		{
			return null;
		}
		// Count occurrences keeping first-seen order.
		Map<String, Integer> counts = new java.util.LinkedHashMap<>();
		for (String id : needed)
		{
			counts.merge(id, 1, Integer::sum);
		}
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : counts.entrySet())
		{
			String name = Role.displayNameOf(entry.getKey());
			parts.add(entry.getValue() > 1 ? name + " x" + entry.getValue() : name);
		}
		return "Needs: " + String.join(", ", parts);
	}

	/**
	 * Force the applicant to pick one of a role party's still-open roles. Returns
	 * the chosen role id, or null if they cancelled (or there's nothing to choose).
	 */
	private String promptForRole(Party party, Activity activity)
	{
		List<Role> options = new ArrayList<>();
		List<String> needed = neededRolesOf(party);
		if (needed != null)
		{
			for (String id : needed)
			{
				Role role = Role.fromId(id);
				if (role != null && !options.contains(role))
				{
					options.add(role);
				}
			}
		}
		if (options.isEmpty())
		{
			// No advertised roles to choose from - fall back to the full role set
			// for the party's difficulty (CoX normal vs Challenge Mode).
			options.addAll(activity.roles(party.isHardMode()));
		}
		if (options.isEmpty())
		{
			return null;
		}
		Role[] choices = options.toArray(new Role[0]);
		Role pick = (Role) JOptionPane.showInputDialog(this, "Which role will you fill?",
			"Choose a role", JOptionPane.QUESTION_MESSAGE, null, choices, choices[0]);
		return pick != null ? pick.getId() : null;
	}

	private boolean isActive(Party party)
	{
		return partyState.isInParty() && !partyState.isHost()
			&& partyState.getCurrentParty().getId().equals(party.getId());
	}

	private boolean isOwnParty(Party party)
	{
		String me = playerNameSupplier.get();
		return me != null && party.getHost() != null
			&& normalize(me).equalsIgnoreCase(normalize(party.getHost()));
	}

	private void apply(Party party)
	{
		String player = playerNameSupplier.get();
		if (player == null)
		{
			setStatus("Log in before applying to a party.");
			return;
		}
		if (isOwnParty(party))
		{
			setStatus("You can't apply to your own party.");
			return;
		}
		if (!meetsIronmanRule(party))
		{
			setStatus("This party is for ironman accounts.");
			return;
		}
		if (kcStatus(party) == KcStatus.BELOW)
		{
			setStatus("You don't meet this party's minimum killcount.");
			updateAllButtons();
			return;
		}
		if (cooldownRemainingSeconds(party.getId()) > 0)
		{
			setStatus("On cooldown for this party.");
			return;
		}

		// ToB/CoX: the applicant must commit to one of the party's open roles.
		Activity activity = Activity.fromId(party.getActivity());
		String role = null;
		if (activity != null && activity.hasRoles())
		{
			role = promptForRole(party, activity);
			if (role == null)
			{
				return; // cancelled the role dialog
			}
		}

		JButton button = applyButtons.get(party.getId());
		if (button != null)
		{
			button.setEnabled(false);
			button.setText("Applying...");
		}

		// Leave whatever party we're currently in first (one party at a time).
		final String chosenRole = role;
		leaveCurrentThen(() -> doApply(party, chosenRole));
	}

	private void leaveCurrentThen(Runnable next)
	{
		if (!partyState.isInParty())
		{
			next.run();
			return;
		}

		// Tear down the party we're currently in: remove our ad if we were hosting,
		// and close the live room. Membership is P2P now, so this is synchronous.
		Party current = partyState.getCurrentParty();
		if (partyState.isHost())
		{
			partyService.disbandParty(current.getId(), playerNameSupplier.get(), partyState.getHostKey(),
				p -> { }, e -> { });
		}
		liveParty.leave();
		partyState.clear();
		next.run();
	}

	private void doApply(Party party, String role)
	{
		String passphrase = party.getPassphrase();
		if (passphrase == null || passphrase.isEmpty())
		{
			setStatus("This party has no live room to join.");
			updateAllButtons();
			return;
		}

		boolean learner = imLearnerCheck.isSelected();
		liveParty.joinParty(passphrase, party.getActivity(), party.getCapacity(), role, learner);
		partyState.setMember(party);
		String roleSuffix = role != null ? " as " + Role.displayNameOf(role) : "";
		String learnerSuffix = learner ? " (learner)" : "";
		setStatus("Joined " + party.getHost() + "'s room" + roleSuffix + learnerSuffix + " - awaiting host approval.");
		updateAllButtons();
	}

	private void cancel(Party party)
	{
		JButton button = applyButtons.get(party.getId());
		if (button != null)
		{
			button.setEnabled(false);
			button.setText("Leaving...");
		}

		liveParty.leave();
		partyState.clear();
		cooldownExpiry.put(party.getId(), System.currentTimeMillis() + COOLDOWN_MS);
		setStatus("Left. You can re-apply to this party in " + (COOLDOWN_MS / 1000) + "s.");
		maybeStartTimer();
		updateAllButtons();
	}

	private void updateAllButtons()
	{
		for (Map.Entry<String, JButton> entry : applyButtons.entrySet())
		{
			Party party = partiesById.get(entry.getKey());
			if (party != null)
			{
				updateApplyButton(entry.getValue(), party);
			}
		}
		updateJoinButton();
	}

	/** Joining (by code) needs a logged in account, so disable it while logged out. */
	private void updateJoinButton()
	{
		boolean loggedIn = playerNameSupplier.get() != null;
		joinButton.setEnabled(loggedIn);
		joinButton.setToolTipText(loggedIn ? null : "Log in to join a party");
	}

	private void updateApplyButton(JButton button, Party party)
	{
		// You can browse parties while logged out, but applying needs an account.
		if (playerNameSupplier.get() == null)
		{
			button.setText("Log in");
			button.setEnabled(false);
			button.setToolTipText("Log in to apply to a party");
			return;
		}
		if (isOwnParty(party))
		{
			button.setText("Your party");
			button.setEnabled(false);
			button.setToolTipText("You host this party - manage it on the Current tab");
			return;
		}
		if (isActive(party))
		{
			button.setText("Cancel");
			button.setEnabled(true);
			button.setToolTipText("Withdraw your application");
			return;
		}
		if (!meetsIronmanRule(party))
		{
			button.setText("Iron only");
			button.setEnabled(false);
			button.setToolTipText("This party is for ironman accounts");
			return;
		}
		if (party.isFull())
		{
			button.setText("Full");
			button.setEnabled(false);
			button.setToolTipText(null);
			return;
		}
		long remaining = cooldownRemainingSeconds(party.getId());
		if (remaining > 0)
		{
			button.setText("Wait " + remaining + "s");
			button.setEnabled(false);
			button.setToolTipText("Recently applied to this party");
			return;
		}
		KcStatus kc = kcStatus(party);
		if (kc == KcStatus.BELOW)
		{
			button.setText("Need KC");
			button.setEnabled(false);
			button.setToolTipText("You don't meet this party's minimum killcount ("
				+ requirementText(Activity.fromId(party.getActivity()), party) + ")");
			return;
		}
		if (kc == KcStatus.PENDING)
		{
			button.setText("Checking KC...");
			button.setEnabled(false);
			button.setToolTipText("Looking up your killcount on the hiscores");
			return;
		}
		button.setText("Apply");
		button.setEnabled(true);
		button.setToolTipText(partyState.isInParty() ? "Applying will leave your current party" : null);
	}

	private boolean isMemberInParty()
	{
		return partyState.isInParty() && !partyState.isHost();
	}

	/** Normalise a player name for comparison (RuneLite uses nbsp in names). */
	private static String normalize(String name)
	{
		return name == null ? "" : name.replace('\u00A0', ' ').trim();
	}

	private long cooldownRemainingSeconds(String partyId)
	{
		Long expiry = cooldownExpiry.get(partyId);
		if (expiry == null)
		{
			return 0;
		}
		long remainingMs = expiry - System.currentTimeMillis();
		if (remainingMs <= 0)
		{
			cooldownExpiry.remove(partyId);
			return 0;
		}
		return (remainingMs + 999) / 1000; // round up
	}

	/** Start the 1s ticker if there's anything live to refresh. */
	private void maybeStartTimer()
	{
		if (hasActiveCooldowns() || isMemberInParty())
		{
			ensureTimer();
		}
	}

	private void ensureTimer()
	{
		if (uiTimer == null)
		{
			uiTimer = new Timer(1000, e -> {
				updateAllButtons();
				if (!hasActiveCooldowns() && !isMemberInParty())
				{
					uiTimer.stop();
				}
			});
		}
		if (!uiTimer.isRunning())
		{
			uiTimer.start();
		}
	}

	private boolean hasActiveCooldowns()
	{
		long now = System.currentTimeMillis();
		for (Long expiry : cooldownExpiry.values())
		{
			if (expiry > now)
			{
				return true;
			}
		}
		return false;
	}

	private void setStatus(String text)
	{
		statusLabel.setText(text);
	}

	/**
	 * A scroll view that tracks the viewport width, so cards are constrained to the
	 * panel width and their right-aligned buttons never get clipped off the edge.
	 */
	private static final class ScrollableColumn extends JPanel implements Scrollable
	{
		ScrollableColumn(LayoutManager layout)
		{
			super(layout);
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction)
		{
			return 64;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}
}
