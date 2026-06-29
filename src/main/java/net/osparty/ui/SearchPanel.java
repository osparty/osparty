package net.osparty.ui;

import net.osparty.FavoritesService;
import net.osparty.KillcountService;
import net.osparty.OSPartyConfig;
import net.osparty.WorldPinger;
import net.osparty.api.PartyService;
import net.osparty.api.PartySubscription;
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
import java.util.Comparator;
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
import net.runelite.client.game.SpriteManager;
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
class SearchPanel extends PartyCardPanel
{

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

	private static final class ActivityGroup
	{
		final String label;
		final Activity[] activities;

		ActivityGroup(String label, Activity... activities)
		{
			this.label = label;
			this.activities = activities;
		}
	}

	private static final ActivityGroup[] ACTIVITY_GROUPS = {
		new ActivityGroup("Raids",
			Activity.CHAMBERS_OF_XERIC, Activity.THEATRE_OF_BLOOD, Activity.TOMBS_OF_AMASCUT),
		new ActivityGroup("Godwars",
			Activity.KREEARRA, Activity.GENERAL_GRAARDOR, Activity.KRIL_TSUTSAROTH,
			Activity.COMMANDER_ZILYANA),
		new ActivityGroup("Other",
			Activity.NEX, Activity.NIGHTMARE, Activity.CORPOREAL_BEAST, Activity.BARBARIAN_ASSAULT,
			Activity.ZALCANO, Activity.HUEYCOATL, Activity.YAMA),
	};

	private static final String SORT_NEWEST = "Newest first";
	private static final String SORT_OLDEST = "Oldest first";
	private static final String SORT_PING   = "Lowest ping";
	private static final String SORT_SPOTS  = "Most spots open";
	private static final String SORT_FULL   = "Closest to full";

	private final Supplier<String> friendsChatOwnerSupplier;
	private final IntSupplier worldSupplier;
	private final Supplier<int[]> mapRegionsSupplier;
	private final ConfigManager configManager;

	private final JPanel activityListPanel = new JPanel();
	private final Set<Activity> selectedActivities = EnumSet.noneOf(Activity.class);
	private final Set<Role> selectedRoles = EnumSet.noneOf(Role.class);
	private boolean rolesExpanded;
	private final JButton roleToggle = new JButton();
	private final JTabbedPane roleTabs = new JTabbedPane();
	/** Collapsible content: the role tabs plus the separate learner mark. */
	private final JPanel roleContent = new JPanel();
	private final Set<WorldRegion> selectedRegions = EnumSet.allOf(WorldRegion.class);
	/** Keeps a handle to each region checkbox so Reset can sync their visual state. */
	private final Map<WorldRegion, JCheckBox> regionCheckboxes = new HashMap<>();
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
	private final JComboBox<String> learnerComboBox = new JComboBox<>(
		new String[]{"Any", "Learner only", "Hide learner raids"});
	private final JCheckBox hideIneligibleFilter = new JCheckBox("Hide parties I can't join");
	private final JComboBox<String> sortComboBox = new JComboBox<>(
		new String[]{SORT_NEWEST, SORT_OLDEST, SORT_PING, SORT_SPOTS, SORT_FULL});
	private final JLabel activeFiltersLabel = new JLabel();
	private final JButton resetButton = new JButton("Reset");
	private final JTextField codeField = new JTextField();
	private final JButton joinButton = new JButton("Join");
	private final JButton searchButton = new JButton("Refresh");
	private final JLabel statusLabel = new JLabel();
	private final JPanel resultsPanel = new JPanel();

	private List<Party> lastResults;
	private String renderedSignature;
	private Timer autoRefreshTimer;
	/** Live party-list socket; non-null only while the Search tab is visible. */
	private PartySubscription subscription;

	SearchPanel(PartyService partyService, Supplier<String> playerNameSupplier,
		Supplier<String> friendsChatOwnerSupplier, IntSupplier worldSupplier, PartyState partyState,
		LiveParty liveParty, Supplier<AccountType> accountTypeSupplier, Supplier<int[]> mapRegionsSupplier,
		IntFunction<WorldRegion> worldRegionResolver, KillcountService killcountService, ConfigManager configManager,
		WorldPinger worldPinger, IntFunction<String> worldAddressResolver,
		Supplier<Set<String>> friendNamesSupplier, FavoritesService favoritesService,
		SpriteManager spriteManager)
	{
		super(partyService, playerNameSupplier, partyState, liveParty, accountTypeSupplier,
			killcountService, worldPinger, worldRegionResolver, worldAddressResolver,
			favoritesService, friendNamesSupplier, spriteManager);
		this.friendsChatOwnerSupplier = friendsChatOwnerSupplier;
		this.worldSupplier = worldSupplier;
		this.mapRegionsSupplier = mapRegionsSupplier;
		this.configManager = configManager;

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

		updateActiveFiltersLabel();

		// While the tab is visible: re-check whether we've moved near a different
		// activity, and re-render to refresh the "searching Xm" age labels. The live
		// socket pushes list changes, so this does no network I/O.
		autoRefreshTimer = new Timer(10_000, e -> {
			if (isShowing())
			{
				applyRecommendation();
				renderCurrent();
			}
		});
		autoRefreshTimer.start();

		// Subscribe to the live list only while the Search tab is visible. The socket
		// itself is owned by the plugin (always open, also used for hosting), so this
		// just toggles the list firehose with a cheap subscribe/unsubscribe — no
		// connection churn — sparing the server from pushing to users not looking.
		addAncestorListener(new AncestorListener()
		{
			@Override
			public void ancestorAdded(AncestorEvent event)
			{
				startSubscription();
				applyRecommendation();
				renderCurrent();
				updateJoinButton();
			}

			@Override
			public void ancestorRemoved(AncestorEvent event)
			{
				stopSubscription();
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

		// Sort row
		JPanel sortRow = cappedRow(new BorderLayout(4, 0));
		JLabel sortLabel = new JLabel("Sort");
		sortLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sortLabel.setFont(FontManager.getRunescapeSmallFont());
		sortComboBox.addActionListener(e -> filtersChanged());
		sortRow.add(sortLabel, BorderLayout.WEST);
		sortRow.add(sortComboBox, BorderLayout.CENTER);

		// Bottom row: active-filters badge + reset + refresh button
		JPanel bottomRow = cappedRow(new BorderLayout(4, 0));
		activeFiltersLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		activeFiltersLabel.setFont(FontManager.getRunescapeSmallFont());
		resetButton.setFocusPainted(false);
		resetButton.setFont(FontManager.getRunescapeSmallFont());
		resetButton.setToolTipText("Clear all filters");
		resetButton.addActionListener(e -> resetAllFilters());

		JPanel badgePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		badgePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		badgePanel.add(activeFiltersLabel);
		badgePanel.add(resetButton);

		searchButton.setFocusPainted(false);
		searchButton.setToolTipText("Refresh the list of open parties");
		searchButton.addActionListener(e -> renderCurrent());

		bottomRow.add(badgePanel, BorderLayout.WEST);
		bottomRow.add(searchButton, BorderLayout.EAST);

		controls.add(sortRow, BorderLayout.NORTH);
		controls.add(bottomRow, BorderLayout.CENTER);
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
		scroll.setPreferredSize(new Dimension(10, 170));
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

		// The text field plus loot, ironman, learner and hide-ineligible filters live under "Search".
		lootFilter.addActionListener(e -> filtersChanged());
		ironmanFilter.setBackground(ColorScheme.DARK_GRAY_COLOR);
		ironmanFilter.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		ironmanFilter.setFocusPainted(false);
		ironmanFilter.addActionListener(e -> filtersChanged());

		learnerComboBox.setToolTipText("Filter by learner-raid status");
		learnerComboBox.addActionListener(e -> filtersChanged());

		hideIneligibleFilter.setBackground(ColorScheme.DARK_GRAY_COLOR);
		hideIneligibleFilter.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		hideIneligibleFilter.setFocusPainted(false);
		hideIneligibleFilter.setToolTipText("Hide parties you can't join due to ironman rule or KC requirement");
		hideIneligibleFilter.addActionListener(e -> filtersChanged());

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

		JLabel learnerLabel = new JLabel("Learner raids");
		learnerLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		learnerLabel.setFont(FontManager.getRunescapeSmallFont());
		JPanel learnerRow = new JPanel(new BorderLayout(4, 0));
		learnerRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		learnerRow.add(learnerLabel, BorderLayout.WEST);
		learnerRow.add(learnerComboBox, BorderLayout.CENTER);

		searchContent.setLayout(new BoxLayout(searchContent, BoxLayout.Y_AXIS));
		searchContent.setBackground(ColorScheme.DARK_GRAY_COLOR);
		searchContent.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		searchContent.add(searchRow(textField));
		searchContent.add(searchRow(lootFilter));
		searchContent.add(searchRow(ironmanFilter));
		searchContent.add(searchRow(learnerRow));
		searchContent.add(searchRow(hideIneligibleFilter));
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
			regionCheckboxes.put(region, check);
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

	/** Rebuild the grouped checkbox list. The nearby activity is highlighted in its group. */
	private void rebuildActivityList()
	{
		activityListPanel.removeAll();
		for (ActivityGroup group : ACTIVITY_GROUPS)
		{
			// Group header: label + per-group All / None toggles.
			JPanel header = cappedRow(new BorderLayout(4, 0));
			header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			header.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
				BorderFactory.createEmptyBorder(4, 4, 4, 4)));

			JLabel groupLabel = new JLabel(group.label);
			groupLabel.setForeground(ColorScheme.BRAND_ORANGE);
			groupLabel.setFont(new JLabel().getFont().deriveFont(Font.BOLD));
			header.add(groupLabel, BorderLayout.WEST);

			JPanel headerButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
			headerButtons.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			JButton allBtn = miniButton("All");
			allBtn.addActionListener(e -> setActivitiesInGroup(group, true));
			JButton noneBtn = miniButton("None");
			noneBtn.addActionListener(e -> setActivitiesInGroup(group, false));
			headerButtons.add(allBtn);
			headerButtons.add(noneBtn);
			header.add(headerButtons, BorderLayout.EAST);
			activityListPanel.add(header);

			// Activity checkboxes
			for (Activity activity : group.activities)
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
		}
		activityListPanel.revalidate();
		activityListPanel.repaint();
	}

	private void setActivitiesInGroup(ActivityGroup group, boolean selected)
	{
		for (Activity a : group.activities)
		{
			if (selected)
			{
				selectedActivities.add(a);
			}
			else
			{
				selectedActivities.remove(a);
			}
		}
		rebuildActivityList();
		filtersChanged();
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
		updateActiveFiltersLabel();
		reapplyFilters();
	}

	// ---- filter persistence (remembered across sessions) ---------------------

	private static final String KEY_ACTIVITIES        = "searchActivities";
	private static final String KEY_ROLES             = "searchRoles";
	private static final String KEY_LOOT              = "searchLoot";
	private static final String KEY_IRONMAN           = "searchIronman";
	private static final String KEY_LEARNER           = "searchLearner";
	private static final String KEY_ROLES_EXPANDED    = "searchRolesExpanded";
	private static final String KEY_SEARCH_EXPANDED   = "searchTextExpanded";
	private static final String KEY_REGIONS           = "searchRegions";
	private static final String KEY_REGIONS_EXPANDED  = "searchRegionsExpanded";
	private static final String KEY_MAX_PING          = "searchMaxPing";
	private static final String KEY_SORT              = "searchSort";
	private static final String KEY_LEARNER_FILTER    = "searchLearnerFilter";
	private static final String KEY_HIDE_INELIGIBLE   = "searchHideIneligible";

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
		put(KEY_SORT, (String) sortComboBox.getSelectedItem());
		put(KEY_LEARNER_FILTER, (String) learnerComboBox.getSelectedItem());
		put(KEY_HIDE_INELIGIBLE, Boolean.toString(hideIneligibleFilter.isSelected()));
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
		String sort = get(KEY_SORT);
		if (sort != null)
		{
			sortComboBox.setSelectedItem(sort);
		}
		String learnerFilter = get(KEY_LEARNER_FILTER);
		if (learnerFilter != null)
		{
			learnerComboBox.setSelectedItem(learnerFilter);
		}
		hideIneligibleFilter.setSelected(Boolean.parseBoolean(get(KEY_HIDE_INELIGIBLE)));
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

	/** Re-render the latest list pushed by the socket; no network (the feed is live). */
	private void renderCurrent()
	{
		if (lastResults != null)
		{
			showResults(lastResults);
		}
	}

	/**
	 * Subscribe to the live party list. The server pushes the full list on (re)connect and
	 * after every change; we render it on the EDT. This is the only source of list data.
	 */
	private void startSubscription()
	{
		if (subscription != null)
		{
			return;
		}
		subscription = partyService.subscribeParties(
			parties -> SwingUtilities.invokeLater(() -> acceptPushedParties(parties)),
			error -> { /* transient socket drop; a reconnect re-subscribes and re-snapshots */ });
	}

	/** Stop receiving the live list (the plugin's socket stays open for hosting). */
	private void stopSubscription()
	{
		if (subscription != null)
		{
			subscription.close();
			subscription = null;
		}
	}

	/**
	 * A list pushed by the socket. Keep it as the latest result even when the tab is
	 * hidden (so returning to it shows current data) but only repaint when visible.
	 */
	private void acceptPushedParties(List<Party> parties)
	{
		lastResults = parties;
		if (isShowing())
		{
			showResults(parties);
		}
	}

	/** Unsubscribe and stop timers; called when the plugin shuts down. */
	void dispose()
	{
		if (autoRefreshTimer != null)
		{
			autoRefreshTimer.stop();
		}
		stopSubscription();
	}

	private void showResults(List<Party> parties)
	{
		lastResults = parties;

		LootRule wantLoot = lootFilterValue();
		boolean ironOnly = ironmanFilter.isSelected();
		boolean hideIneligible = hideIneligibleFilter.isSelected();
		int learnerIdx = learnerComboBox.getSelectedIndex(); // 0=Any, 1=Learner only, 2=Hide learner
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
				// Learner-raid filter (feature 6).
				if (learnerIdx == 1 && !party.isLearnerRaid())
				{
					continue; // "Learner only" — hide non-learner parties
				}
				if (learnerIdx == 2 && party.isLearnerRaid())
				{
					continue; // "Hide learner raids" — hide learner parties
				}
				// Hide-ineligible filter (feature 5): skip parties the player can't join.
				// A PENDING KC check is never treated as BELOW — don't over-filter.
				if (hideIneligible)
				{
					if (!meetsIronmanRule(party))
					{
						continue;
					}
					if (kcStatus(party) == KcStatus.BELOW)
					{
						continue;
					}
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

		// Sort the visible list by the chosen order, then float friends to the top.
		Comparator<Party> comp = buildComparator();
		if (friendNamesSupplier != null)
		{
			Set<String> friends = friendNamesSupplier.get();
			if (friends != null && !friends.isEmpty())
			{
				Comparator<Party> friendFirst = Comparator.comparingInt(
					(Party p) -> {
						String key = p.getHost() == null ? "" : normalize(p.getHost()).toLowerCase();
						return friends.contains(key) ? 0 : 1;
					});
				comp = friendFirst.thenComparing(comp);
			}
		}
		visible.sort(comp);

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
			+ maxPingField.getText().trim() + "|" + text + "|" + pingSignatureOf(visible) + "|" + signatureOf(visible)
			+ "|s" + sortComboBox.getSelectedIndex()
			+ "|l" + learnerComboBox.getSelectedIndex()
			+ "|h" + hideIneligibleFilter.isSelected()
			+ "|f" + friendSignatureOf(visible);
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

	/** Build a sort comparator for the chosen sort order. */
	private Comparator<Party> buildComparator()
	{
		String selected = (String) sortComboBox.getSelectedItem();
		if (selected == null)
		{
			selected = SORT_NEWEST;
		}
		switch (selected)
		{
			case SORT_OLDEST:
				return Comparator.comparingLong(Party::getCreatedAt);
			case SORT_PING:
				return (a, b) -> Integer.compare(pingForSort(a), pingForSort(b));
			case SORT_SPOTS:
				return (a, b) -> {
					int slotsA = a.getCapacity() > 0 ? a.getCapacity() - a.getSize() : -1;
					int slotsB = b.getCapacity() > 0 ? b.getCapacity() - b.getSize() : -1;
					return Integer.compare(slotsB, slotsA); // descending
				};
			case SORT_FULL:
				return (a, b) -> {
					float fa = a.getCapacity() > 0 ? (float) a.getSize() / a.getCapacity() : 0f;
					float fb = b.getCapacity() > 0 ? (float) b.getSize() / b.getCapacity() : 0f;
					return Float.compare(fb, fa); // descending
				};
			default: // SORT_NEWEST
				return (a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt());
		}
	}

	/** Ping value used for sorting: unknown near-last, unreachable last. */
	private int pingForSort(Party party)
	{
		Integer wn = parseWorldNum(party);
		if (wn == null || worldPinger == null)
		{
			return Integer.MAX_VALUE - 1;
		}
		Integer ping = worldPinger.getCachedPing(wn);
		if (ping == null)
		{
			return Integer.MAX_VALUE - 1; // not yet measured
		}
		return ping < 0 ? Integer.MAX_VALUE : ping; // unreachable → absolute last
	}

	/** Signature of which visible-party hosts are friends, so sort changes trigger a re-render. */
	private String friendSignatureOf(List<Party> parties)
	{
		if (friendNamesSupplier == null)
		{
			return "";
		}
		Set<String> friends = friendNamesSupplier.get();
		if (friends == null || friends.isEmpty())
		{
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (Party p : parties)
		{
			String key = p.getHost() == null ? "" : normalize(p.getHost()).toLowerCase();
			if (friends.contains(key))
			{
				sb.append(p.getId()).append(',');
			}
		}
		return sb.toString();
	}

	/** Count the number of non-default filters currently active. */
	private int countActiveFilters()
	{
		int count = 0;
		if (selectedActivities.size() < Activity.values().length)
		{
			count++;
		}
		if (!selectedRoles.isEmpty())
		{
			count++;
		}
		if (sortComboBox.getSelectedIndex() > 0)
		{
			count++;
		}
		if (lootFilterValue() != null)
		{
			count++;
		}
		if (ironmanFilter.isSelected())
		{
			count++;
		}
		if (learnerComboBox.getSelectedIndex() > 0)
		{
			count++;
		}
		if (!maxPingField.getText().trim().isEmpty())
		{
			count++;
		}
		for (WorldRegion r : KNOWN_REGIONS)
		{
			if (!selectedRegions.contains(r))
			{
				count++;
				break;
			}
		}
		if (hideIneligibleFilter.isSelected())
		{
			count++;
		}
		return count;
	}

	/** Refresh the active-filters label and show/hide the reset button accordingly. */
	private void updateActiveFiltersLabel()
	{
		int count = countActiveFilters();
		if (count == 0)
		{
			activeFiltersLabel.setText("");
			resetButton.setVisible(false);
		}
		else
		{
			activeFiltersLabel.setText(count + (count == 1 ? " active filter" : " active filters"));
			resetButton.setVisible(true);
		}
	}

	/** Reset every filter to its default state. */
	private void resetAllFilters()
	{
		selectedActivities.addAll(EnumSet.allOf(Activity.class));
		selectedRoles.clear();
		for (WorldRegion r : KNOWN_REGIONS)
		{
			selectedRegions.add(r);
			JCheckBox cb = regionCheckboxes.get(r);
			if (cb != null)
			{
				cb.setSelected(true);
			}
		}
		lootFilter.setSelectedIndex(0);        // "Any loot"
		ironmanFilter.setSelected(false);
		learnerComboBox.setSelectedIndex(0);   // "Any"
		hideIneligibleFilter.setSelected(false);
		maxPingField.setText("");
		sortComboBox.setSelectedIndex(0);      // "Newest first"
		updateRoleToggleText();
		updateRegionToggleText();
		rebuildActivityList();
		filtersChanged();
	}

	@Override
	protected void updateAllButtons()
	{
		super.updateAllButtons();
		updateJoinButton();
	}

	/** Joining (by code) needs a logged in account, so disable it while logged out. */
	private void updateJoinButton()
	{
		boolean loggedIn = playerNameSupplier.get() != null;
		joinButton.setEnabled(loggedIn);
		joinButton.setToolTipText(loggedIn ? null : "Log in to join a party");
	}

	@Override
	protected void setStatus(String text)
	{
		statusLabel.setText(text);
	}

	@Override
	protected boolean isLocalLearner()
	{
		return imLearnerCheck.isSelected();
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
