package net.osparty.ui;

import net.osparty.service.FavoritesService;
import net.osparty.service.KillcountService;
import net.osparty.OSPartyConfig;
import net.osparty.tools.WorldPinger;
import net.osparty.api.PartyService;
import net.osparty.api.PartySubscription;
import net.osparty.model.Activity;
import net.osparty.model.LootRule;
import net.osparty.model.Member;
import net.osparty.model.Party;
import net.osparty.model.Role;
import net.osparty.party.LiveParty;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
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
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;

import net.osparty.service.BlockListService;
import net.runelite.api.vars.AccountType;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.ui.FontManager;
import net.runelite.http.api.worlds.WorldRegion;

/** "Search" tab: browse open parties and apply to one (applying leaves your current party first). */
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
			Activity.COMMANDER_ZILYANA, Activity.NEX),
		new ActivityGroup("Other",
			Activity.NIGHTMARE, Activity.CORPOREAL_BEAST, Activity.BARBARIAN_ASSAULT,
			Activity.ZALCANO, Activity.HUEYCOATL, Activity.YAMA, Activity.ROYAL_TITANS),
	};

	private static final String SORT_NEWEST = "Newest first";
	private static final String SORT_OLDEST = "Oldest first";
	private static final String SORT_PING   = "Lowest ping";
	private static final String SORT_FULL   = "Closest to full";

	private final Supplier<String> friendsChatOwnerSupplier;
	private final IntSupplier worldSupplier;
	private final Supplier<int[]> mapRegionsSupplier;
	private final ConfigManager configManager;

	private final JPanel activityListPanel = new JPanel();
	private boolean activitiesExpanded;
	private final JButton activitiesToggle = new JButton();
	private final JPanel activityContent = new JPanel();
	private final Set<Activity> selectedActivities = EnumSet.noneOf(Activity.class);
	private final Set<Role> selectedRoles = EnumSet.noneOf(Role.class);
	private boolean rolesExpanded;
	private final JButton roleToggle = new JButton();
	private final JTabbedPane roleTabs = new JTabbedPane();
	/** Collapsible content: the role tabs. */
	private final JPanel roleContent = new JPanel();
	private final Set<WorldRegion> selectedRegions = EnumSet.allOf(WorldRegion.class);
	/** Keeps a handle to each region checkbox so Reset can sync their visual state. */
	private final Map<WorldRegion, JCheckBox> regionCheckboxes = new HashMap<>();
	private boolean regionsExpanded;
	private final JButton regionToggle = new JButton();
	private final JPanel regionContent = new JPanel();
	/** Outer "Filters" disclosure that wraps every filter section (point 8). */
	private boolean filtersExpanded;
	private final JButton filtersToggle = new JButton();
	private final JPanel filtersContent = new JPanel();
	private Activity recommended;
	private boolean searchExpanded;
	private final JButton searchToggle = new JButton();
	private final JPanel searchContent = new JPanel();
	/** Prompt text shown in the search bar while it's empty and unfocused. */
	private static final String SEARCH_PLACEHOLDER = "Search host, activity or description…";
	private final JTextField textField = new JTextField()
	{
		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			if (!getText().isEmpty() || isFocusOwner())
			{
				return;
			}
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
			g2.setFont(getFont().deriveFont(Font.ITALIC));
			Insets in = getInsets();
			int baseline = getBaseline(getWidth(), getHeight());
			g2.drawString(SEARCH_PLACEHOLDER, in.left + 2,
				baseline > 0 ? baseline : getHeight() - in.bottom - 3);
			g2.dispose();
		}
	};
	private final JTextField maxPingField = new JTextField();

	private final JComboBox<String> lootFilter = new JComboBox<>(new String[]{"Any loot", "FFA", "Split"});
	private final JCheckBox ironmanFilter = new JCheckBox("Ironman parties only");
	private final JComboBox<String> learnerComboBox = new JComboBox<>(
		new String[]{"Any", "Learner only", "Hide learner raids"});
	private final JCheckBox hideIneligibleFilter = new JCheckBox("Hide parties I can't join");
	private final JComboBox<String> sortComboBox = new JComboBox<>(
		new String[]{SORT_NEWEST, SORT_OLDEST, SORT_PING, SORT_FULL});
	private final JLabel activeFiltersLabel = new JLabel();
	private final JButton resetButton = new JButton("Reset");
	private final JLabel statusLabel = new JLabel();
	private final JPanel resultsPanel = new JPanel();
	/** Results scroll vs the offline message + Reconnect button (point 52). */
	private JScrollPane scroll;
	private final JButton reconnectButton = new JButton("Reconnect");
	private JPanel disconnectedPanel;
	private boolean showingDisconnected;

	private List<Party> lastResults;
	private String renderedSignature;
	/** Rendered card per party id, reused across refreshes so a push only touches changed cards. */
	private final Map<String, JComponent> cardsById = new HashMap<>();
	/** The content signature each card was rendered from; a mismatch rebuilds just that card. */
	private final Map<String, String> cardSignatures = new HashMap<>();
	private Timer autoRefreshTimer;
	/** Live party-list socket; non-null only while the Search tab is visible. */
	private PartySubscription subscription;

	SearchPanel(PartyService partyService, Supplier<String> playerNameSupplier,
                Supplier<String> friendsChatOwnerSupplier, IntSupplier worldSupplier, PartyState partyState,
                LiveParty liveParty, Supplier<AccountType> accountTypeSupplier, Supplier<int[]> mapRegionsSupplier,
                IntFunction<WorldRegion> worldRegionResolver, KillcountService killcountService, ConfigManager configManager,
                WorldPinger worldPinger, IntFunction<String> worldAddressResolver,
                Supplier<Set<String>> friendNamesSupplier, FavoritesService favoritesService,
                BlockListService blockListService, SpriteManager spriteManager,
                OSPartyConfig config)
	{
		super(partyService, playerNameSupplier, partyState, liveParty, accountTypeSupplier,
			killcountService, worldPinger, worldRegionResolver, worldAddressResolver,
			favoritesService, blockListService, friendNamesSupplier, spriteManager, config);
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
		north.add(buildFiltersSection());
		north.add(buildControlsBar());
		north.add(buildSearchBar());
		north.add(buildSortRow());

		resultsPanel.setLayout(new BoxLayout(resultsPanel, BoxLayout.Y_AXIS));
		resultsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// The whole tab scrolls as one; tracks viewport width so cards never overflow their Apply button.
		JPanel content = new ScrollableColumn(new BorderLayout(0, 8));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.add(north, BorderLayout.NORTH);
		content.add(resultsPanel, BorderLayout.CENTER);

		scroll = new JScrollPane(content,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);

		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		// Match the Favorites tab's status line placement (same 4/6 insets) so they line up.
		statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		add(statusLabel, BorderLayout.SOUTH);

		updateActiveFiltersLabel();

		// While visible: re-check the nearby activity and refresh the "searching Xm" age labels (no network).
		autoRefreshTimer = new Timer(10_000, e -> {
			if (isShowing())
			{
				applyRecommendation();
				renderCurrent();
			}
		});
		autoRefreshTimer.start();

		// Subscribe to the live list only while visible: a cheap subscribe/unsubscribe on the shared socket.
		addAncestorListener(new AncestorListener()
		{
			@Override
			public void ancestorAdded(AncestorEvent event)
			{
				startSubscription();
				applyRecommendation();
				renderCurrent();
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

		// React to party changes made elsewhere (e.g. leaving from the Party tab).
		partyState.addListener(() -> {
			updateAllButtons();
			maybeStartTimer();
		});

		// Keep the Apply/Join buttons in step with login state (cheap, no network).
		new Timer(1_000, e -> {
			if (isShowing())
			{
				updateAllButtons();
				updateConnectionView();
			}
		}).start();
	}

	/** Wraps every filter section in one collapsible "Filters" disclosure (collapsed by default). */
	private JPanel buildFiltersSection()
	{
		JPanel panel = cappedRow(new BorderLayout(0, 4));

		styleCollapsibleHeader(filtersToggle);
		filtersToggle.addActionListener(e -> setFiltersExpanded(!filtersExpanded));
		panel.add(filtersToggle, BorderLayout.NORTH);

		filtersContent.setLayout(new BoxLayout(filtersContent, BoxLayout.Y_AXIS));
		filtersContent.setBackground(ColorScheme.DARK_GRAY_COLOR);
		filtersContent.setAlignmentX(Component.LEFT_ALIGNMENT);
		filtersContent.add(buildActivityFilter());
		filtersContent.add(buildRoleFilter());
		filtersContent.add(buildRegionFilter());
		filtersContent.add(buildTextFilter());
		filtersContent.setVisible(filtersExpanded);
		panel.add(filtersContent, BorderLayout.CENTER);

		updateFiltersToggleText();
		return panel;
	}

	private void setFiltersExpanded(boolean expanded)
	{
		filtersExpanded = expanded;
		filtersContent.setVisible(expanded);
		updateFiltersToggleText();
		persistFilters();
		revalidate();
		repaint();
	}

	private void updateFiltersToggleText()
	{
		filtersToggle.setIcon(filtersExpanded ? CARET_EXPANDED : CARET_COLLAPSED);
		int active = countActiveFilters();
		filtersToggle.setText(active == 0 ? "Filters" : "Filters (" + active + ")");
	}

	private JPanel buildSortRow()
	{
		sortComboBox.addActionListener(e -> filtersChanged());
		JPanel sortRow = labeledRow("Sort", sortComboBox);
		sortRow.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		return sortRow;
	}

	/** Active-filters badge + Reset, kept outside the Filters disclosure so it stays visible when collapsed. */
	private JPanel buildControlsBar()
	{
		JPanel bottomRow = cappedRow(new BorderLayout(4, 0));
		bottomRow.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
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

		bottomRow.add(badgePanel, BorderLayout.WEST);
		return bottomRow;
	}

	/** The activity multiselect: a collapsible section with a checkbox per activity. */
	private JPanel buildActivityFilter()
	{
		JPanel panel = cappedRow(new BorderLayout(0, 4));
		panel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		styleCollapsibleHeader(activitiesToggle, true);
		updateActivitiesToggleText();
		activitiesToggle.addActionListener(e -> setActivitiesExpanded(!activitiesExpanded));
		panel.add(activitiesToggle, BorderLayout.NORTH);

		activityListPanel.setLayout(new BoxLayout(activityListPanel, BoxLayout.Y_AXIS));
		activityListPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		rebuildActivityList();

		JScrollPane scroll = new JScrollPane(activityListPanel,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		// Show ~6 rows; the rest scroll.
		scroll.setPreferredSize(new Dimension(10, 170));

		activityContent.setLayout(new BorderLayout());
		activityContent.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		activityContent.add(scroll);
		activityContent.setVisible(activitiesExpanded);
		panel.add(activityContent, BorderLayout.CENTER);
		return panel;
	}

	private void setActivitiesExpanded(boolean expanded)
	{
		activitiesExpanded = expanded;
		activityContent.setVisible(expanded);
		updateActivitiesToggleText();
		persistFilters();
		revalidate();
		repaint();
	}

	private void updateActivitiesToggleText()
	{
		activitiesToggle.setIcon(activitiesExpanded ? CARET_EXPANDED : CARET_COLLAPSED);
		int n = selectedActivities.size();
		activitiesToggle.setText(n == 0 ? "Activities" : "Activities (" + n + ")");
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

	/** Style a collapsible header like RuneLite's config sections (bold orange title, grey caret, separator). */
	private static void styleCollapsibleHeader(JButton toggle)
	{
		styleCollapsibleHeader(toggle, false);
	}

	/** @param sub when true, a nested section under "Filters", rendered a step smaller than the top-level header. */
	private static void styleCollapsibleHeader(JButton toggle, boolean sub)
	{
		toggle.setHorizontalAlignment(SwingConstants.LEFT);
		toggle.setFocusPainted(false);
		toggle.setContentAreaFilled(false);
		toggle.setForeground(ColorScheme.BRAND_ORANGE);
		Font base = new JLabel().getFont();
		toggle.setFont(sub
			? base.deriveFont(Font.BOLD, base.getSize2D() - 2f)
			: base.deriveFont(Font.BOLD));
		toggle.setIconTextGap(6);
		toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		toggle.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(3, 0, 4, 0)));
	}

	/** The role multiselect (ToB/CoX): tick roles you'll fill; none ticked means no constraint. */
	private JPanel buildRoleFilter()
	{
		JPanel panel = cappedRow(new BorderLayout(0, 4));
		panel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		// Collapsible header: clicking it shows/hides the (fairly tall) tabbed picker.
		styleCollapsibleHeader(roleToggle, true);
		roleToggle.addActionListener(e -> setRolesExpanded(!rolesExpanded));
		panel.add(roleToggle, BorderLayout.NORTH);

		// One tab per activity + difficulty so a CM pick isn't confused with a normal CoX one.
		roleTabs.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		roleTabs.addTab("ToB", buildRoleTab(Activity.THEATRE_OF_BLOOD, false));
		roleTabs.addTab("HMT", buildRoleTab(Activity.THEATRE_OF_BLOOD, true));
		roleTabs.addTab("CoX", buildRoleTab(Activity.CHAMBERS_OF_XERIC, false));
		roleTabs.addTab("CM", buildRoleTab(Activity.CHAMBERS_OF_XERIC, true));
		roleTabs.addTab("BA", buildRoleTab(Activity.BARBARIAN_ASSAULT, false));
		roleTabs.setAlignmentX(Component.LEFT_ALIGNMENT);

		// (The "I'm a learner" mark now lives in the apply picker, chosen per application.)
		roleContent.setLayout(new BoxLayout(roleContent, BoxLayout.Y_AXIS));
		roleContent.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		roleContent.add(roleTabs);
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

	/** Whether a party passes the role filter: it must still need one of this activity's ticked roles. */
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
		// The party's difficulty selects which wildcard/Fill applies (CM party uses the CM Fill, etc.).
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

	/** The primary search bar, pinned to the top of the tab (outside the collapsible filters). */
	private JPanel buildSearchBar()
	{
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
		// Repaint so the placeholder shows/hides as focus comes and goes.
		textField.addFocusListener(new java.awt.event.FocusAdapter()
		{
			@Override
			public void focusGained(java.awt.event.FocusEvent e)
			{
				textField.repaint();
			}

			@Override
			public void focusLost(java.awt.event.FocusEvent e)
			{
				textField.repaint();
			}
		});

		JPanel bar = cappedRow(new BorderLayout());
		bar.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		bar.add(textField, BorderLayout.CENTER);
		return bar;
	}

	private JPanel buildTextFilter()
	{
		JPanel panel = cappedRow(new BorderLayout(0, 4));
		panel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		// Collapsible header for the secondary filters (the search field itself is pinned up top).
		styleCollapsibleHeader(searchToggle, true);
		searchToggle.addActionListener(e -> setSearchExpanded(!searchExpanded));
		panel.add(searchToggle, BorderLayout.NORTH);

		// Loot, ironman, learner, hide-ineligible and max-ping filters live under "More filters".
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
		// Numeric only: it's a millisecond threshold, so reject any non-digit input outright.
		((javax.swing.text.AbstractDocument) maxPingField.getDocument()).setDocumentFilter(
			new javax.swing.text.DocumentFilter()
			{
				@Override
				public void insertString(FilterBypass fb, int offset, String string,
					javax.swing.text.AttributeSet attr) throws javax.swing.text.BadLocationException
				{
					if (string == null || string.chars().allMatch(Character::isDigit))
					{
						super.insertString(fb, offset, string, attr);
					}
				}

				@Override
				public void replace(FilterBypass fb, int offset, int length, String text,
					javax.swing.text.AttributeSet attr) throws javax.swing.text.BadLocationException
				{
					if (text == null || text.chars().allMatch(Character::isDigit))
					{
						super.replace(fb, offset, length, text, attr);
					}
				}
			});
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

		searchContent.setLayout(new BoxLayout(searchContent, BoxLayout.Y_AXIS));
		searchContent.setBackground(ColorScheme.DARK_GRAY_COLOR);
		searchContent.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		// Labelled dropdown/field rows first (fixed-width descriptors line up); checkboxes follow.
		searchContent.add(labeledRow("Loot", lootFilter));
		searchContent.add(labeledRow("Learner", learnerComboBox));
		searchContent.add(labeledRow("Max ping", maxPingField));
		searchContent.add(searchRow(ironmanFilter));
		searchContent.add(searchRow(hideIneligibleFilter));
		searchContent.setVisible(searchExpanded);
		panel.add(searchContent, BorderLayout.CENTER);

		updateSearchToggleText();
		return panel;
	}

	/** One full-width, height-capped row (used for self-labelled controls like checkboxes). */
	private static JPanel searchRow(Component control)
	{
		JPanel row = cappedRow(new BorderLayout());
		row.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		row.add(control, BorderLayout.CENTER);
		return row;
	}

	/** Width of every filter descriptor label, so left-aligned rows line up their controls. */
	private static final int FILTER_LABEL_WIDTH = 78;

	/** A row with a fixed-width descriptor on the left and its control filling the rest. */
	private static JPanel labeledRow(String labelText, Component control)
	{
		JPanel row = cappedRow(new BorderLayout(6, 0));
		row.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		JLabel label = new JLabel(labelText);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		Dimension size = new Dimension(FILTER_LABEL_WIDTH, label.getPreferredSize().height);
		label.setPreferredSize(size);
		label.setMinimumSize(size);
		row.add(label, BorderLayout.WEST);
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
		searchToggle.setText("More filters");
	}

	/** Collapsible region multi-select: a 2-column flag-checkbox grid; deselecting a region hides its parties. */
	private JPanel buildRegionFilter()
	{
		JPanel panel = cappedRow(new BorderLayout(0, 4));
		panel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		styleCollapsibleHeader(regionToggle, true);
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

			// Flag is a separate always-bright label; the checkbox carries the on/off state.
			ImageIcon flag = WorldFlags.forRegion(region);
			JPanel cell = new JPanel(new BorderLayout(4, 0));
			cell.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			if (flag != null)
			{
				cell.add(new JLabel(flag), BorderLayout.WEST);
			}
			cell.add(check, BorderLayout.CENTER);
			grid.add(cell);
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

	/** Float the nearby activity to the top and highlight it, but never auto-check it (point 13). */
	private void applyRecommendation()
	{
		Activity near = Activity.nearby(mapRegionsSupplier.get());
		if (near == recommended)
		{
			return;
		}
		recommended = near;
		rebuildActivityList();
		reapplyFilters();
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

	void reapplyFilters()
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
		updateFiltersToggleText();
		updateActivitiesToggleText();
		reapplyFilters();
		// Narrow/widen the server feed to match: scope to a single activity, else all.
		if (subscription != null)
		{
			subscription.setActivity(scopeActivityId());
		}
	}

	// ---- filter persistence (remembered across sessions) ---------------------

	private static final String KEY_ACTIVITIES        = "searchActivities";
	private static final String KEY_ROLES             = "searchRoles";
	private static final String KEY_LOOT              = "searchLoot";
	private static final String KEY_IRONMAN           = "searchIronman";
	private static final String KEY_ROLES_EXPANDED    = "searchRolesExpanded";
	private static final String KEY_SEARCH_EXPANDED   = "searchTextExpanded";
	private static final String KEY_REGIONS           = "searchRegions";
	private static final String KEY_REGIONS_EXPANDED  = "searchRegionsExpanded";
	private static final String KEY_MAX_PING          = "searchMaxPing";
	private static final String KEY_SORT              = "searchSort";
	private static final String KEY_LEARNER_FILTER    = "searchLearnerFilter";
	private static final String KEY_HIDE_INELIGIBLE   = "searchHideIneligible";
	private static final String KEY_FILTERS_EXPANDED  = "searchFiltersExpanded";
	private static final String KEY_ACTIVITIES_EXPANDED = "searchActivitiesExpanded";
	private static final String KEY_FIRST_RUN         = "searchInitialized";

	/** Save the current filter selection so it's restored next session. */
	private void persistFilters()
	{
		put(KEY_ACTIVITIES, idsOf(selectedActivities, Activity::getId));
		put(KEY_ROLES, idsOf(selectedRoles, Role::getId));
		put(KEY_LOOT, (String) lootFilter.getSelectedItem());
		put(KEY_IRONMAN, Boolean.toString(ironmanFilter.isSelected()));
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
		put(KEY_FILTERS_EXPANDED, Boolean.toString(filtersExpanded));
		put(KEY_ACTIVITIES_EXPANDED, Boolean.toString(activitiesExpanded));
	}

	/** Restore the saved filter selection into the in-memory state and the controls. */
	private void loadFilters()
	{
		// On the very first ever launch, start with every activity selected (show all parties).
		boolean firstRun = !Boolean.parseBoolean(get(KEY_FIRST_RUN));
		if (firstRun)
		{
			put(KEY_FIRST_RUN, "true");
			selectedActivities.addAll(EnumSet.allOf(Activity.class));
		}

		String activities = get(KEY_ACTIVITIES);
		if (!firstRun && activities != null)
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
		filtersExpanded = Boolean.parseBoolean(get(KEY_FILTERS_EXPANDED));
		activitiesExpanded = Boolean.parseBoolean(get(KEY_ACTIVITIES_EXPANDED));
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

	/** Look up an invite code and apply to that party; progress/validation goes to {@code status}. */
	public void joinByCode(String code, Consumer<String> status)
	{
		String trimmed = code == null ? "" : code.trim();
		if (trimmed.isEmpty())
		{
			status.accept("Enter an invite code.");
			return;
		}
		if (playerNameSupplier.get() == null)
		{
			status.accept("Log in before joining.");
			return;
		}
		status.accept("Looking up code " + trimmed + "...");
		partyService.getPartyByCode(trimmed,
			party -> SwingUtilities.invokeLater(() -> joinFetched(party, status)),
			error -> SwingUtilities.invokeLater(() -> status.accept("No party found for code " + trimmed + ".")));
	}

	private void joinFetched(Party party, Consumer<String> status)
	{
		if (party == null)
		{
			status.accept("No party found for that code.");
			return;
		}
		if (isOwnParty(party))
		{
			status.accept("That's your own party.");
			return;
		}
		if (!meetsIronmanRule(party))
		{
			status.accept("That party is for ironman accounts.");
			return;
		}
		if (kcStatus(party) == KcStatus.BELOW)
		{
			status.accept("You don't meet that party's minimum killcount.");
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
	void renderCurrent()
	{
		if (lastResults != null)
		{
			showResults(lastResults);
		}
	}

	/** Blocking a host from a card hides it (unless "Show blocked parties" is on): re-filter now. */
	@Override
	protected void onBlockToggled(Party party)
	{
		renderCurrent();
	}

	/** Subscribe to the live party list (the server pushes the full list on connect and after every change). */
	private void startSubscription()
	{
		if (subscription != null)
		{
			return;
		}
		subscription = partyService.subscribeParties(
			parties -> SwingUtilities.invokeLater(() -> acceptPushedParties(parties)),
			error -> SwingUtilities.invokeLater(this::updateConnectionView),
			scopeActivityId());
		updateConnectionView();
	}

	/** Swap the results area for an offline message + Reconnect button when the socket is down (point 52). */
	private void updateConnectionView()
	{
		boolean down = subscription != null && !subscription.isConnected();
		if (down == showingDisconnected)
		{
			return;
		}
		showingDisconnected = down;
		remove(down ? scroll : disconnectedPanel());
		add(down ? disconnectedPanel() : scroll, BorderLayout.CENTER);
		revalidate();
		repaint();
	}

	private JPanel disconnectedPanel()
	{
		if (disconnectedPanel == null)
		{
			disconnectedPanel = new JPanel(new GridBagLayout());
			disconnectedPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

			JPanel col = new JPanel();
			col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
			col.setBackground(ColorScheme.DARK_GRAY_COLOR);

			JLabel msg = new JLabel("Not connected to OSParty");
			msg.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			msg.setFont(FontManager.getRunescapeSmallFont());
			msg.setAlignmentX(Component.CENTER_ALIGNMENT);

			reconnectButton.setFocusPainted(false);
			reconnectButton.setFont(FontManager.getRunescapeSmallFont());
			reconnectButton.setAlignmentX(Component.CENTER_ALIGNMENT);
			reconnectButton.addActionListener(e -> attemptReconnect());

			col.add(msg);
			col.add(Box.createVerticalStrut(8));
			col.add(reconnectButton);
			disconnectedPanel.add(col);
		}
		return disconnectedPanel;
	}

	/** How long to wait for a reconnect to land before telling the user the server didn't answer. */
	private static final int RECONNECT_TIMEOUT_MS = 8000;

	/** Reconnect with definite feedback: report failure after {@link #RECONNECT_TIMEOUT_MS} rather than hang. */
	private void attemptReconnect()
	{
		if (subscription == null)
		{
			return;
		}
		subscription.reconnect();
		setStatus("Reconnecting…");
		reconnectButton.setEnabled(false);
		Timer timeout = new Timer(RECONNECT_TIMEOUT_MS, ev -> {
			reconnectButton.setEnabled(true);
			if (subscription != null && subscription.isConnected())
			{
				setStatus("Reconnected.");
			}
			else
			{
				setStatus("No response from the OSParty server — it may be offline. Try again shortly.");
			}
		});
		timeout.setRepeats(false);
		timeout.start();
	}

	/** Scope the live subscription to the single selected activity, or null (= all) when zero or several. */
	private String scopeActivityId()
	{
		return selectedActivities.size() == 1 ? selectedActivities.iterator().next().getId() : null;
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

	/** A list pushed by the socket: kept as the latest result even when hidden, repainted only when visible. */
	private void acceptPushedParties(List<Party> parties)
	{
		lastResults = parties;
		updateConnectionView();
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

		// Blocked parties are hidden entirely unless the player opts to see them greyed out.
		boolean showBlocked = Boolean.parseBoolean(
			configManager.getConfiguration(OSPartyConfig.GROUP, "showBlockedParties"));

		// Show only joinable parties matching every active filter (full ones hidden).
		List<Party> visible = new ArrayList<>();
		int totalOpen = 0; // all joinable parties, before filters (for the "X of total" count)
		if (parties != null)
		{
			for (Party party : parties)
			{
				// Record current names for favourited/blocked accounts we see (name-change detection + hash backfill).
				favoritesService.observeParty(party);
				blockListService.observeParty(party);

				if (party.isFull())
				{
					continue;
				}
				if (!showBlocked && blockListService.hasAnyBlocked(party))
				{
					continue;
				}
				totalOpen++;
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
				// Hide-ineligible filter (feature 5): a PENDING KC check is never treated as BELOW.
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

				// Max ping filter: skip parties over the threshold; parties with no known ping yet are always shown.
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
			+ "|f" + friendSignatureOf(visible)
			+ "|v" + favSignatureOf(visible)
			+ "|k" + blockSignatureOf(visible)
			+ "|t" + totalOpen;
		if (signature.equals(renderedSignature))
		{
			return;
		}
		renderedSignature = signature;

		// Anchor the viewport to the top-most visible card so changes above it don't shift the read position;
		// at the very top we stay pinned so new arrivals are immediately visible.
		JViewport viewport = scroll.getViewport();
		Point viewPos = viewport.getViewPosition();
		String anchorId = null;
		int anchorOffset = 0;
		if (viewPos.y > 0)
		{
			for (Component child : resultsPanel.getComponents())
			{
				int top = SwingUtilities.convertPoint(resultsPanel, 0, child.getY(), viewport.getView()).y;
				if (top + child.getHeight() > viewPos.y)
				{
					for (Map.Entry<String, JComponent> entry : cardsById.entrySet())
					{
						if (entry.getValue() == child)
						{
							anchorId = entry.getKey();
							anchorOffset = top - viewPos.y;
							break;
						}
					}
					break;
				}
			}
		}

		boolean filtersActive = countActiveFilters() > 0;
		if (visible.isEmpty())
		{
			setStatus(filtersActive && totalOpen > 0
				? "0 of " + totalOpen + " parties match your filters."
				: "No parties to show.");
		}
		else if (filtersActive && totalOpen != visible.size())
		{
			setStatus(visible.size() + " of " + totalOpen + " open "
				+ (totalOpen == 1 ? "party" : "parties") + ".");
		}
		else
		{
			setStatus(visible.size() + " open " + (visible.size() == 1 ? "party" : "parties") + ".");
		}

		// Reconcile the card list in place: drop departed cards, rebuild only changed ones, reorder the rest,
		// so a push doesn't flicker the panel or disturb the scroll position or an open role picker.
		Set<String> visibleIds = new HashSet<>();
		for (Party party : visible)
		{
			visibleIds.add(party.getId());
		}
		for (Iterator<Map.Entry<String, JComponent>> it = cardsById.entrySet().iterator(); it.hasNext(); )
		{
			Map.Entry<String, JComponent> entry = it.next();
			if (!visibleIds.contains(entry.getKey()))
			{
				resultsPanel.remove(entry.getValue());
				it.remove();
				cardSignatures.remove(entry.getKey());
				applyButtons.remove(entry.getKey());
				partiesById.remove(entry.getKey());
				reasonLabels.remove(entry.getKey());
				rolePickers.remove(entry.getKey());
			}
		}
		int index = 0;
		for (Party party : visible)
		{
			String id = party.getId();
			String cardSig = cardSignature(party);
			JComponent card = cardsById.get(id);
			if (card != null && !cardSig.equals(cardSignatures.get(id)))
			{
				resultsPanel.remove(card);
				card = null;
			}
			if (card == null)
			{
				card = wrapCard(buildPartyCard(Activity.fromId(party.getActivity()), party));
				cardsById.put(id, card);
				cardSignatures.put(id, cardSig);
			}
			// Keep actions on fresh data even when the card is reused (e.g. a rotated passphrase).
			partiesById.put(id, party);
			if (index >= resultsPanel.getComponentCount() || resultsPanel.getComponent(index) != card)
			{
				resultsPanel.add(card, index); // moves the card if it is already a child
			}
			index++;
		}
		if (!visible.isEmpty())
		{
			updateAllButtons();
		}

		resultsPanel.revalidate();
		resultsPanel.repaint();

		if (anchorId != null)
		{
			JComponent anchorCard = cardsById.get(anchorId);
			if (anchorCard != null && anchorCard.getParent() == resultsPanel)
			{
				// Lay out synchronously so the anchor's final position is known, then restore its on-screen offset.
				scroll.validate();
				int top = SwingUtilities.convertPoint(resultsPanel, 0, anchorCard.getY(), viewport.getView()).y;
				int maxY = Math.max(0, viewport.getView().getHeight() - viewport.getExtentSize().height);
				viewport.setViewPosition(new Point(0, Math.max(0, Math.min(maxY, top - anchorOffset))));
			}
		}
	}

	/** Wrap a card with its 6px list gap so each visible party is exactly one child of resultsPanel. */
	private static JComponent wrapCard(JComponent card)
	{
		JPanel wrap = new JPanel(new BorderLayout())
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		wrap.setOpaque(false);
		wrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		wrap.add(card, BorderLayout.CENTER);
		return wrap;
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

	/** A stable signature of the visible parties (incl. age in minutes) so unchanged refreshes can no-op. */
	private static String signatureOf(List<Party> parties)
	{
		long now = System.currentTimeMillis();
		StringBuilder sb = new StringBuilder();
		for (Party party : parties)
		{
			sb.append(party.getId()).append(':').append(partyContentSignature(party, now)).append(';');
		}
		return sb.toString();
	}

	/** The party-payload fields a card renders; shared by the list and per-card signatures. */
	private static String partyContentSignature(Party party, long now)
	{
		return new StringBuilder().append(party.getSize())
			.append('/').append(party.getCapacity())
			.append('w').append(party.getWorld() == null ? "" : party.getWorld())
			.append('L').append(party.getLayout() == null ? "" : party.getLayout())
			.append('R').append(neededRolesOf(party) == null ? "" : neededRolesOf(party))
			.append('d').append(party.isHardMode() ? "h" : "").append(party.getInvocation())
			// Host-editable card content, so an edit invalidates the cached render.
			.append('K').append(party.getMinKillCount()).append('/').append(party.getMinHardModeKillCount())
			.append('o').append(party.getLootRule() == null ? "" : party.getLootRule())
			.append('i').append(party.isIronmanOnly() ? '1' : '0')
			.append('l').append(party.isLearnerRaid() ? '1' : '0')
			.append('D').append(party.getDescription() == null ? "" : party.getDescription())
			.append('@').append(ageMinutes(now, party.getCreatedAt()))
			.toString();
	}

	/** Everything a card renders (payload + live decorations) so a refresh rebuilds it only when it changed. */
	private String cardSignature(Party party)
	{
		StringBuilder sb = new StringBuilder(partyContentSignature(party, System.currentTimeMillis()));
		sb.append('h').append(party.getHost() == null ? "" : party.getHost())
			.append('t').append(party.getHostAccountType() == null ? "" : party.getHostAccountType());
		// Host Discord-role badges — without this, a badge change in a members delta wouldn't rebuild the card.
		if (config == null || config.showDiscordBadges())
		{
			List<Member> sigMembers = party.getMembers();
			if (sigMembers != null && !sigMembers.isEmpty() && sigMembers.get(0).getBadges() != null)
			{
				sb.append('B').append(sigMembers.get(0).getBadges());
			}
		}
		else
		{
			sb.append("B-off");
		}
		Integer worldNum = parseWorldNum(party);
		if (worldNum != null && worldPinger != null)
		{
			Integer ping = worldPinger.getCachedPing(worldNum);
			sb.append('p').append(ping != null ? ping : "?");
		}
		Set<String> friends = friendNamesSupplier != null ? friendNamesSupplier.get() : null;
		sb.append('F').append(friends != null && party.getHost() != null
			&& friends.contains(normalize(party.getHost()).toLowerCase()) ? '1' : '0');
		if (favoritesService != null)
		{
			sb.append('v').append(favoritesService.hasAnyFavorite(party) ? '1' : '0')
				.append(favoritesService.isFavorite(party.getHostAccountHash(), party.getHost()) ? 'H' : '_');
		}
		if (blockListService != null)
		{
			sb.append('b').append(blockListService.isBlocked(party.getHostAccountHash(), party.getHost()) ? '1' : '0');
		}
		return sb.toString();
	}

	/** Signature of which visible-party hosts are blocked (rendered greyed when shown at all). */
	private String blockSignatureOf(List<Party> parties)
	{
		if (blockListService == null)
		{
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (Party p : parties)
		{
			if (blockListService.isBlocked(p.getHostAccountHash(), p.getHost()))
			{
				sb.append(p.getId()).append(',');
			}
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

	/** Signature of cached pings so a ping arriving via callback re-renders the world label. */
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

	/** Signature of which visible parties are favourited, so toggling a star rebuilds the icons. */
	private String favSignatureOf(List<Party> parties)
	{
		if (favoritesService == null)
		{
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (Party p : parties)
		{
			if (favoritesService.hasAnyFavorite(p))
			{
				sb.append(p.getId()).append(favoritesService.isFavorite(p.getHost()) ? "H" : "").append(',');
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
		// Every activity selected is the default state, so Reset re-selects them all.
		selectedActivities.clear();
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
	protected void setStatus(String text)
	{
		statusLabel.setText(text);
	}

	/** A scroll view that tracks the viewport width so cards' right-aligned buttons never clip off the edge. */
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
