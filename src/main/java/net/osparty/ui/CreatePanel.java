package net.osparty.ui;

import net.osparty.OSPartyConfig;
import net.osparty.api.PartyService;
import net.osparty.model.AccountTypes;
import net.osparty.model.Activity;
import net.osparty.model.LootRule;
import net.osparty.model.Party;
import net.osparty.model.PartyPreset;
import net.osparty.model.PartyRequest;
import net.osparty.model.Role;
import net.osparty.party.LiveParty;
import com.google.gson.Gson;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JOptionPane;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import net.runelite.api.vars.AccountType;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * "Create" tab: a form to host a new party. On submit the logged in player is
 * recorded as the host and the party is pushed to the queue API; the player
 * then enters that party (managed on the "Current" tab). A player can only host
 * while not already in a party.
 */
class CreatePanel extends JPanel
{
	private static final String KEY_LAST_PRESET = "lastPreset";
	private static final String KEY_FAVOURITES = "favourites";

	private final PartyService partyService;
	private final OSPartyConfig config;
	private final Supplier<String> playerNameSupplier;
	private final PartyState partyState;
	private final LiveParty liveParty;
	private final Supplier<AccountType> accountTypeSupplier;
	private final Supplier<int[]> mapRegionsSupplier;
	private final Supplier<String> coxLayoutSupplier;
	private final ConfigManager configManager;
	private final Gson gson;

	private final JComboBox<Activity> activityDropdown = new JComboBox<>(Activity.values());
	/** The activity we're currently standing near (suggested at the top of the list). */
	private Activity recommended;
	private boolean rebuildingDropdown;
	private final JComboBox<LootRule> lootDropdown = new JComboBox<>(LootRule.values());
	private final JSpinner capacitySpinner;
	private final JTextField worldField = new JTextField();
	private final JTextArea descriptionArea = new JTextArea(3, 0);
	private final JCheckBox privateCheck = new JCheckBox("Private (join by code only)");
	private final JCheckBox ironmanCheck = new JCheckBox("Ironman only");
	private final JCheckBox learnerCheck = new JCheckBox("Learner");
	private final JCheckBox teacherCheck = new JCheckBox("Teacher");
	private JPanel learnerRow;
	private final JCheckBox includeLayoutCheck = new JCheckBox("Advertise raid layout (in raid)");
	private JPanel includeLayoutRow;
	private final JCheckBox hardModeCheck = new JCheckBox();
	private JPanel hardModeRow;
	private final JSpinner invocationSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 600, 5));
	private JPanel invocationRow;
	private final JButton createButton = new JButton("Create party");
	private final JLabel statusLabel = new JLabel();

	private final JComboBox<String> favouriteDropdown = new JComboBox<>();
	private boolean rebuildingFavourites;

	private final JSpinner minKcSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 100_000, 10));
	private final JLabel hardKcLabel = new JLabel("Minimum CM KC");
	private final JSpinner hardKcSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 100_000, 10));
	private JPanel hardKcField;

	private final JComboBox<Role> myRoleDropdown = new JComboBox<>();
	private JPanel rolesSection;
	private JPanel roleCountsPanel;
	private final JLabel roleTotalLabel = new JLabel();
	private final LinkedHashMap<String, JSpinner> roleCountSpinners = new LinkedHashMap<>();
	private boolean rebuildingRoles;

	private static final String LOGIN_HINT = "Log in to create a party.";
	private boolean creating;

	CreatePanel(PartyService partyService, OSPartyConfig config, Supplier<String> playerNameSupplier,
		PartyState partyState, LiveParty liveParty, Supplier<AccountType> accountTypeSupplier,
		Supplier<int[]> mapRegionsSupplier, Supplier<String> coxLayoutSupplier, ConfigManager configManager,
		Gson gson)
	{
		this.gson = gson;
		this.partyService = partyService;
		this.config = config;
		this.playerNameSupplier = playerNameSupplier;
		this.partyState = partyState;
		this.liveParty = liveParty;
		this.accountTypeSupplier = accountTypeSupplier;
		this.mapRegionsSupplier = mapRegionsSupplier;
		this.coxLayoutSupplier = coxLayoutSupplier;
		this.configManager = configManager;

		int defaultCapacity = Math.max(1, config.defaultCapacity());
		this.capacitySpinner = new JSpinner(new SpinnerNumberModel(defaultCapacity, 1, 100, 1));

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(buildFavourites());
		add(field("Activity", activityDropdown));
		add(field("Party size", capacitySpinner));
		add(field("Loot rule", lootDropdown));
		add(field("Minimum KC", minKcSpinner));

		hardKcField = field(hardKcLabel, hardKcSpinner);
		add(hardKcField);

		add(field("World (optional)", worldField));

		descriptionArea.setLineWrap(true);
		descriptionArea.setWrapStyleWord(true);
		descriptionArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		descriptionArea.setForeground(Color.WHITE);
		add(field("Description (optional)", new JScrollPane(descriptionArea)));

		add(checkBoxRow(privateCheck));
		add(checkBoxRow(ironmanCheck));

		// Chambers of Xeric only: shown/hidden by applyActivityBounds.
		includeLayoutRow = checkBoxRow(includeLayoutCheck);
		includeLayoutRow.setVisible(false);
		add(includeLayoutRow);

		// Difficulty: a CM/HMT toggle (CoX/ToB) or an invocation level (ToA). Only the
		// relevant control is shown, driven by applyActivityBounds.
		hardModeRow = checkBoxRow(hardModeCheck);
		hardModeRow.setVisible(false);
		add(hardModeRow);

		invocationRow = field("Invocation level", invocationSpinner);
		invocationRow.setVisible(false);
		add(invocationRow);

		// Learner-raid tagging (raids only): ticking either Learner or Teacher marks
		// the ad as a learner raid; neither leaves it a normal raid. Shown/hidden by
		// applyActivityBounds.
		learnerRow = buildLearnerRow();
		learnerRow.setVisible(false);
		add(learnerRow);

		// Roles (ToB/CoX only): the host's own role plus a count per role for the
		// team composition. Shown/hidden and rebuilt by applyActivityBounds.
		rolesSection = buildRolesSection();
		rolesSection.setVisible(false);
		add(rolesSection);

		// ToB's composition is fixed by party size, and CoX's Fill count should track
		// capacity, so rebuild the role controls whenever the party size changes.
		capacitySpinner.addChangeListener(e -> {
			Activity activity = (Activity) activityDropdown.getSelectedItem();
			if (!rebuildingRoles && activity != null && activity.hasRoles())
			{
				rebuildRoles(activity);
				absorbRemainderIntoFill();
			}
		});

		// Chambers of Xeric's role split (normal vs CM) changes with the hard-mode
		// toggle, so rebuild the role controls when it flips.
		hardModeCheck.addActionListener(e -> {
			Activity activity = (Activity) activityDropdown.getSelectedItem();
			if (!rebuildingRoles && activity != null && activity.hasRoles())
			{
				rebuildRoles(activity);
				absorbRemainderIntoFill();
			}
		});

		// Only ironman accounts may host an ironman-only party - bounce the toggle
		// (and explain) if a main tries to tick it.
		ironmanCheck.addActionListener(e -> {
			if (ironmanCheck.isSelected() && !AccountTypes.isIronman(accountTypeSupplier.get()))
			{
				ironmanCheck.setSelected(false);
				setStatus("Only ironman accounts can host an ironman-only party.");
			}
		});

		createButton.setFocusPainted(false);
		createButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		createButton.addActionListener(e -> create());
		add(createButton);

		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		statusLabel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		add(statusLabel);

		// Push the form to the top so fields keep their natural height.
		add(Box.createVerticalGlue());

		activityDropdown.setRenderer(new ActivityRenderer());
		activityDropdown.addActionListener(e -> {
			if (!rebuildingDropdown)
			{
				applyActivityBounds();
			}
		});
		applyActivityBounds();

		// Pre-fill the form with the last settings the player used (remembered across sessions).
		applyPreset(loadLastPreset());

		// Suggest the activity we're standing near when the tab opens, and re-check
		// every 10s while it's visible.
		addAncestorListener(new AncestorListener()
		{
			@Override
			public void ancestorAdded(AncestorEvent event)
			{
				applyRecommendation();
				// Re-read the configured default each time the tab is shown, so a
				// changed "default party size" takes effect without a client restart.
				applyDefaultCapacity();
				updateLoginState();
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
		new Timer(10_000, e -> {
			if (isShowing())
			{
				applyRecommendation();
			}
		}).start();

		// Re-check login state often so the form enables/disables promptly when the
		// player logs in or out while this tab is open.
		new Timer(1_000, e -> {
			if (isShowing())
			{
				updateLoginState();
			}
		}).start();

		updateLoginState();
	}

	/**
	 * The form stays usable while logged out (so presets/favourites can be built);
	 * only hosting needs a logged-in account, so just the Create button is gated.
	 */
	private void updateLoginState()
	{
		boolean loggedIn = playerNameSupplier.get() != null;
		createButton.setEnabled(loggedIn && !creating);
		if (!loggedIn)
		{
			setStatus(LOGIN_HINT);
		}
		else if (LOGIN_HINT.equals(statusLabel.getText()))
		{
			setStatus("");
		}
	}

	/**
	 * If the player is standing near an activity, float it to the top of the
	 * dropdown and select it. No-op when the nearby activity hasn't changed, so it
	 * doesn't fight a manual selection.
	 */
	private void applyRecommendation()
	{
		Activity near = Activity.nearby(mapRegionsSupplier.get());
		if (near == recommended)
		{
			return;
		}
		recommended = near;

		Activity current = (Activity) activityDropdown.getSelectedItem();
		rebuildingDropdown = true;
		activityDropdown.removeAllItems();
		if (near != null)
		{
			activityDropdown.addItem(near);
		}
		for (Activity activity : Activity.values())
		{
			if (activity != near)
			{
				activityDropdown.addItem(activity);
			}
		}
		Activity select = near != null ? near : current;
		if (select != null)
		{
			activityDropdown.setSelectedItem(select);
		}
		rebuildingDropdown = false;
		applyActivityBounds();
	}

	private JPanel field(String labelText, Component input)
	{
		return field(new JLabel(labelText), input);
	}

	private JPanel field(JLabel label, Component input)
	{
		// Cap the height to the preferred size *dynamically* (computed after the
		// children are laid out); a fixed maximum set at construction time
		// collapses the field under BoxLayout and overlaps its contents.
		JPanel panel = new JPanel(new BorderLayout(0, 4))
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);

		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		panel.add(label, BorderLayout.NORTH);
		panel.add(input, BorderLayout.CENTER);
		return panel;
	}

	private JPanel checkBoxRow(JCheckBox box)
	{
		box.setBackground(ColorScheme.DARK_GRAY_COLOR);
		box.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		box.setFocusPainted(false);

		JPanel panel = new JPanel(new BorderLayout())
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(box, BorderLayout.WEST);
		return panel;
	}

	private JPanel buildLearnerRow()
	{
		JPanel boxes = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		boxes.setBackground(ColorScheme.DARK_GRAY_COLOR);
		for (JCheckBox box : new JCheckBox[]{learnerCheck, teacherCheck})
		{
			box.setBackground(ColorScheme.DARK_GRAY_COLOR);
			box.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			box.setFocusPainted(false);
		}
		boxes.add(learnerCheck);
		boxes.add(Box.createHorizontalStrut(12));
		boxes.add(teacherCheck);

		// Learner and Teacher are mutually exclusive; ticking one clears the other.
		// Either can still be unticked to leave it a normal raid.
		learnerCheck.addActionListener(e -> {
			if (learnerCheck.isSelected())
			{
				teacherCheck.setSelected(false);
			}
		});
		teacherCheck.addActionListener(e -> {
			if (teacherCheck.isSelected())
			{
				learnerCheck.setSelected(false);
			}
		});
		return field("Learner raid", boxes);
	}

	private void applyDefaultCapacity()
	{
		// A remembered preset takes precedence over the configured default size.
		if (loadLastPreset() != null)
		{
			return;
		}
		SpinnerNumberModel model = (SpinnerNumberModel) capacitySpinner.getModel();
		int min = ((Number) model.getMinimum()).intValue();
		int max = ((Number) model.getMaximum()).intValue();
		int wanted = Math.max(1, config.defaultCapacity());
		model.setValue(Math.min(max, Math.max(min, wanted)));
	}

	private void applyActivityBounds()
	{
		Activity activity = (Activity) activityDropdown.getSelectedItem();
		if (activity == null)
		{
			return;
		}

		SpinnerNumberModel model = (SpinnerNumberModel) capacitySpinner.getModel();
		model.setMinimum(activity.getMinPartySize());
		model.setMaximum(activity.getMaxPartySize());

		int current = (Integer) model.getValue();
		if (current < activity.getMinPartySize())
		{
			model.setValue(activity.getMinPartySize());
		}
		else if (current > activity.getMaxPartySize())
		{
			model.setValue(activity.getMaxPartySize());
		}

		// The hard-mode KC requirement only applies to activities that have one
		// (e.g. Chambers of Xeric CM, Theatre of Blood HM, Tombs of Amascut Expert).
		boolean hardMode = activity.hasHardMode();
		hardKcField.setVisible(hardMode);
		if (hardMode)
		{
			hardKcLabel.setText("Minimum " + activity.getHardModeLabel() + " KC");
		}
		else
		{
			hardKcSpinner.setValue(0);
		}

		// The "include raid layout" option only makes sense for Chambers of Xeric.
		boolean isCox = "cox".equals(activity.getId());
		includeLayoutRow.setVisible(isCox);
		if (!isCox)
		{
			includeLayoutCheck.setSelected(false);
		}

		// Difficulty: a CM/HMT checkbox for CoX/ToB, an invocation spinner for ToA.
		boolean usesInvocation = activity.usesInvocation();
		boolean usesHardMode = activity.hasHardMode() && !usesInvocation;
		hardModeRow.setVisible(usesHardMode);
		if (usesHardMode)
		{
			hardModeCheck.setText("Advertise as " + activity.getHardModeName());
		}
		else
		{
			hardModeCheck.setSelected(false);
		}
		invocationRow.setVisible(usesInvocation);
		if (!usesInvocation)
		{
			invocationSpinner.setValue(0);
		}

		// Learner-raid tagging only applies to the three raids.
		boolean isRaid = activity.isRaid();
		learnerRow.setVisible(isRaid);
		if (!isRaid)
		{
			learnerCheck.setSelected(false);
			teacherCheck.setSelected(false);
		}

		// Roles: a "my role" dropdown + per-role count spinners for ToB/CoX.
		boolean hasRoles = activity.hasRoles();
		rolesSection.setVisible(hasRoles);
		if (hasRoles)
		{
			rebuildRoles(activity);
		}

		revalidate();
		repaint();
	}

	private JPanel buildRolesSection()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);

		myRoleDropdown.addActionListener(e -> {
			if (!rebuildingRoles)
			{
				updateRoleTotal();
			}
		});
		panel.add(field("My role", myRoleDropdown));

		roleCountsPanel = new JPanel();
		roleCountsPanel.setLayout(new BoxLayout(roleCountsPanel, BoxLayout.Y_AXIS));
		roleCountsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		roleCountsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(roleCountsPanel);

		roleTotalLabel.setFont(FontManager.getRunescapeSmallFont());
		roleTotalLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		roleTotalLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		panel.add(roleTotalLabel);
		return panel;
	}

	/**
	 * Rebuild the role controls for {@code activity} at the current party size. The
	 * "my role" dropdown lists the activity's roles. Theatre of Blood's composition
	 * is fixed by size (shown as a read-only summary); Chambers of Xeric uses a
	 * count spinner per role, with Fill auto-absorbing any remainder.
	 */
	private void rebuildRoles(Activity activity)
	{
		int capacity = (Integer) capacitySpinner.getValue();
		// The role split depends on the difficulty (CoX normal vs CM, ToB vs HMT).
		boolean hardMode = hardModeCheck.isSelected();
		List<Role> roles = activity.roles(hardMode);
		Role fillRole = activity.fillRole(hardMode);

		// "My role" dropdown - the roles the host can be.
		Role previousMine = (Role) myRoleDropdown.getSelectedItem();
		rebuildingRoles = true;
		myRoleDropdown.removeAllItems();
		for (Role role : roles)
		{
			myRoleDropdown.addItem(role);
		}
		if (previousMine != null && roles.contains(previousMine))
		{
			myRoleDropdown.setSelectedItem(previousMine);
		}
		else if (!roles.isEmpty())
		{
			myRoleDropdown.setSelectedIndex(0);
		}
		rebuildingRoles = false;

		if (activity.hasFixedComposition(hardMode))
		{
			// Normal ToB: no spinners - the team make-up is determined by party size.
			rebuildingRoles = true;
			roleCountSpinners.clear();
			roleCountsPanel.removeAll();
			rebuildingRoles = false;
			roleTotalLabel.setText("Team: " + compositionSummary(captureRequiredRoles(activity, capacity)));
			roleTotalLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}
		else
		{
			// CoX (and HMT): a count per role, summing to the party size.
			Map<String, Integer> previous = new HashMap<>();
			for (Map.Entry<String, JSpinner> entry : roleCountSpinners.entrySet())
			{
				previous.put(entry.getKey(), (Integer) entry.getValue().getValue());
			}
			boolean firstBuild = previous.isEmpty();

			rebuildingRoles = true;
			roleCountSpinners.clear();
			roleCountsPanel.removeAll();
			for (Role role : roles)
			{
				// Seed the Fill slot (CoX only) with the whole party on the first build
				// so the total matches capacity; otherwise keep what the host entered.
				int seed = firstBuild && role == fillRole ? capacity : 0;
				int value = previous.getOrDefault(role.getId(), seed);
				JSpinner spinner = new JSpinner(new SpinnerNumberModel(Math.max(0, value), 0, 100, 1));
				spinner.addChangeListener(e -> {
					if (!rebuildingRoles)
					{
						updateRoleTotal();
					}
				});
				roleCountSpinners.put(role.getId(), spinner);
				roleCountsPanel.add(field(role.getDisplayName(), spinner));
			}
			rebuildingRoles = false;
			updateRoleTotal();
		}

		roleCountsPanel.revalidate();
		roleCountsPanel.repaint();
	}

	/** CoX only: set the Fill count to absorb whatever's left of the party size. */
	private void absorbRemainderIntoFill()
	{
		String fillId = currentFillRoleId();
		JSpinner fill = fillId == null ? null : roleCountSpinners.get(fillId);
		if (fill == null)
		{
			return; // no Fill slot for this activity/mode (e.g. HMT) - nothing to absorb
		}
		int others = 0;
		for (Map.Entry<String, JSpinner> entry : roleCountSpinners.entrySet())
		{
			if (!entry.getKey().equals(fillId))
			{
				others += (Integer) entry.getValue().getValue();
			}
		}
		int capacity = (Integer) capacitySpinner.getValue();
		rebuildingRoles = true;
		fill.setValue(Math.max(0, capacity - others));
		rebuildingRoles = false;
		updateRoleTotal();
	}

	private String currentFillRoleId()
	{
		Activity activity = (Activity) activityDropdown.getSelectedItem();
		if (activity == null)
		{
			return null;
		}
		Role fill = activity.fillRole(hardModeCheck.isSelected());
		return fill == null ? null : fill.getId();
	}

	private int assignedRoleTotal()
	{
		int total = 0;
		for (JSpinner spinner : roleCountSpinners.values())
		{
			total += (Integer) spinner.getValue();
		}
		return total;
	}

	/** Only meaningful for the spinner (CoX) case; ToB sets its own summary label. */
	private void updateRoleTotal()
	{
		if (roleCountSpinners.isEmpty())
		{
			return;
		}
		int total = assignedRoleTotal();
		int capacity = (Integer) capacitySpinner.getValue();
		roleTotalLabel.setText("Roles assigned: " + total + " / " + capacity);
		roleTotalLabel.setForeground(total == capacity
			? ColorScheme.LIGHT_GRAY_COLOR : ColorScheme.PROGRESS_ERROR_COLOR);
	}

	/** "1 Melee, 1 Ranged, 2 Mage" from a role multiset (first-seen order). */
	private static String compositionSummary(List<String> roleIds)
	{
		if (roleIds == null || roleIds.isEmpty())
		{
			return "-";
		}
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String id : roleIds)
		{
			counts.merge(id, 1, Integer::sum);
		}
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : counts.entrySet())
		{
			String name = Role.displayNameOf(entry.getKey());
			parts.add(entry.getValue() > 1 ? entry.getValue() + " " + name : "1 " + name);
		}
		return String.join(", ", parts);
	}

	/**
	 * The required-role multiset (role ids) for the activity: ToB's fixed
	 * composition, or the CoX count spinners. Null when the activity has no roles.
	 */
	private List<String> captureRequiredRoles(Activity activity, int capacity)
	{
		if (activity == null || !activity.hasRoles())
		{
			return null;
		}
		if (activity.hasFixedComposition(hardModeCheck.isSelected()))
		{
			List<String> roles = new ArrayList<>();
			for (Role role : activity.fixedComposition(capacity))
			{
				roles.add(role.getId());
			}
			return roles;
		}
		List<String> roles = new ArrayList<>();
		for (Map.Entry<String, JSpinner> entry : roleCountSpinners.entrySet())
		{
			int count = (Integer) entry.getValue().getValue();
			for (int i = 0; i < count; i++)
			{
				roles.add(entry.getKey());
			}
		}
		return roles;
	}

	private void create()
	{
		if (partyState.isInParty())
		{
			setStatus("Leave your current party before creating one.");
			return;
		}

		String player = playerNameSupplier.get();
		if (player == null)
		{
			setStatus("Log in before creating a party.");
			return;
		}

		Activity activity = (Activity) activityDropdown.getSelectedItem();
		if (activity == null)
		{
			return;
		}

		int minHardKc = activity.hasHardMode() ? (Integer) hardKcSpinner.getValue() : 0;
		int capacity = (Integer) capacitySpinner.getValue();
		String activityId = activity.getId();
		String description = descriptionArea.getText().trim();
		String world = worldField.getText().trim();
		int minKc = (Integer) minKcSpinner.getValue();

		LootRule loot = (LootRule) lootDropdown.getSelectedItem();
		String lootRule = (loot == null ? LootRule.UNSPECIFIED : loot).name();
		boolean privateParty = privateCheck.isSelected();
		boolean ironmanOnly = ironmanCheck.isSelected();
		AccountType accountType = accountTypeSupplier.get();
		String hostAccountType = accountType != null ? accountType.name() : null;

		if (ironmanOnly && !AccountTypes.isIronman(accountType))
		{
			setStatus("Only ironman accounts can host an ironman-only party.");
			return;
		}

		// Chambers of Xeric: advertise the live raid layout (sent via heartbeat once
		// the host is inside the raid), rather than baking it into the description.
		boolean advertiseLayout = includeLayoutCheck.isSelected() && "cox".equals(activityId);

		// Raid difficulty: CM/HMT toggle (CoX/ToB) or invocation level (ToA).
		boolean hardMode = activity.hasHardMode() && !activity.usesInvocation() && hardModeCheck.isSelected();
		int invocation = activity.usesInvocation() ? (Integer) invocationSpinner.getValue() : 0;

		// Learner-raid tagging (raids only): either flag marks the ad as a learner raid.
		boolean learner = activity.isRaid() && learnerCheck.isSelected();
		boolean teacher = activity.isRaid() && teacherCheck.isSelected();

		// Roles (ToB/CoX): the composition must fill exactly the party size, and the
		// host's chosen role must be one of those slots (the host fills it). ToB's
		// composition is fixed by size, so it always validates; CoX is host-defined.
		final List<String> requiredRoles;
		final String hostRole;
		if (activity.hasRoles())
		{
			requiredRoles = captureRequiredRoles(activity, capacity);
			int assigned = requiredRoles == null ? 0 : requiredRoles.size();
			if (assigned != capacity)
			{
				setStatus("Assign exactly " + capacity + " role slots (currently " + assigned + ").");
				return;
			}
			Role mine = (Role) myRoleDropdown.getSelectedItem();
			hostRole = mine != null ? mine.getId() : null;
			if (hostRole == null || !requiredRoles.contains(hostRole))
			{
				setStatus("Add at least one " + (mine != null ? mine.getDisplayName() : "of your role")
					+ " slot - that's the role you'll fill.");
				return;
			}
		}
		else
		{
			requiredRoles = null;
			hostRole = null;
		}

		// Remember these settings so the form is pre-filled next time.
		saveLastPreset(captureForm(null));

		creating = true;
		createButton.setEnabled(false);
		setStatus("Creating party...");

		final String advertisedDescription = description;
		// A secret that authorises host-only changes to this ad: sent on create (the
		// server binds it to the party's session) and on later heartbeat/disband.
		final String hostKey = java.util.UUID.randomUUID().toString();
		// The passphrase must be generated on the client thread (RuneLite reads item
		// names to build it), so this is async; advertise once we have it.
		liveParty.generatePassphrase(passphrase -> {
			PartyRequest request = new PartyRequest(
				activityId, player, advertisedDescription, capacity, world, minKc, minHardKc, passphrase,
				privateParty, lootRule, ironmanOnly, hostAccountType, hardMode, invocation, requiredRoles, hostRole,
				learner, teacher);

			partyService.createParty(request, hostKey,
				party -> SwingUtilities.invokeLater(
					() -> onCreated(party, passphrase, player, capacity, advertiseLayout, hostRole, learner, teacher,
						hostKey)),
				error -> SwingUtilities.invokeLater(() -> {
					creating = false;
					createButton.setEnabled(true);
					setStatus("Create failed: " + error.getMessage());
				}));
		});
	}

	private void onCreated(Party party, String passphrase, String host, int capacity, boolean advertiseLayout,
		String hostRole, boolean hostLearner, boolean hostTeacher, String hostKey)
	{
		creating = false;
		createButton.setEnabled(true);
		descriptionArea.setText("");
		// Remember whether to advertise the live CoX layout, so the Current tab's
		// heartbeat knows to include it once the host is inside the raid.
		partyState.setAdvertiseLayout(advertiseLayout);
		// Host the live room now that the ad is up; applicants who join are pending
		// until admitted from the Current tab.
		liveParty.hostParty(passphrase, host, party.getActivity(), capacity, false, hostRole, hostLearner, hostTeacher);
		if (party.isPrivateParty() && party.getInviteCode() != null)
		{
			setStatus("Private party created - invite code " + party.getInviteCode() + " (also on Current tab).");
		}
		else
		{
			setStatus("Party created - manage it on the Current tab.");
		}
		partyState.setHosting(party, hostKey);
	}

	private void setStatus(String text)
	{
		statusLabel.setText(text);
	}

	// ---- favourites / presets ------------------------------------------------

	private static final String FAV_PLACEHOLDER = "Favourites...";

	private JPanel buildFavourites()
	{
		JPanel panel = new JPanel(new BorderLayout(4, 0))
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);

		favouriteDropdown.addActionListener(e -> {
			if (rebuildingFavourites)
			{
				return;
			}
			int idx = favouriteDropdown.getSelectedIndex();
			if (idx <= 0)
			{
				return; // placeholder row
			}
			List<PartyPreset> favourites = loadFavourites();
			if (idx - 1 < favourites.size())
			{
				applyPreset(favourites.get(idx - 1));
				setStatus("Loaded favourite \"" + favourites.get(idx - 1).getName() + "\".");
			}
		});

		JButton save = miniButton("+");
		save.setToolTipText("Save the current settings as a favourite");
		save.addActionListener(e -> saveCurrentAsFavourite());

		JButton remove = miniButton("X");
		remove.setToolTipText("Remove the selected favourite");
		remove.addActionListener(e -> removeSelectedFavourite());

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttons.add(save);
		buttons.add(remove);

		panel.add(favouriteDropdown, BorderLayout.CENTER);
		panel.add(buttons, BorderLayout.EAST);
		rebuildFavourites();
		return panel;
	}

	private JButton miniButton(String text)
	{
		JButton button = new JButton(text);
		button.setFocusPainted(false);
		button.setMargin(new Insets(0, 6, 0, 6));
		return button;
	}

	private void rebuildFavourites()
	{
		rebuildingFavourites = true;
		favouriteDropdown.removeAllItems();
		favouriteDropdown.addItem(FAV_PLACEHOLDER);
		for (PartyPreset preset : loadFavourites())
		{
			favouriteDropdown.addItem(preset.getName());
		}
		favouriteDropdown.setSelectedIndex(0);
		rebuildingFavourites = false;
	}

	private void saveCurrentAsFavourite()
	{
		String name = JOptionPane.showInputDialog(this, "Favourite name:", "Save favourite",
			JOptionPane.PLAIN_MESSAGE);
		if (name == null)
		{
			return;
		}
		name = name.trim();
		if (name.isEmpty() || FAV_PLACEHOLDER.equals(name))
		{
			setStatus("Enter a name for the favourite.");
			return;
		}
		List<PartyPreset> favourites = loadFavourites();
		String chosen = name;
		favourites.removeIf(f -> chosen.equalsIgnoreCase(f.getName())); // overwrite a same-named one
		favourites.add(captureForm(chosen));
		saveFavourites(favourites);
		rebuildFavourites();
		favouriteDropdown.setSelectedItem(chosen);
		setStatus("Saved favourite \"" + chosen + "\".");
	}

	private void removeSelectedFavourite()
	{
		int idx = favouriteDropdown.getSelectedIndex();
		if (idx <= 0)
		{
			setStatus("Select a favourite to remove.");
			return;
		}
		List<PartyPreset> favourites = loadFavourites();
		if (idx - 1 < favourites.size())
		{
			String removed = favourites.remove(idx - 1).getName();
			saveFavourites(favourites);
			rebuildFavourites();
			setStatus("Removed favourite \"" + removed + "\".");
		}
	}

	/** Snapshot the current form into a preset (raw description, no appended layout). */
	private PartyPreset captureForm(String name)
	{
		PartyPreset preset = new PartyPreset();
		preset.setName(name);
		Activity activity = (Activity) activityDropdown.getSelectedItem();
		preset.setActivityId(activity != null ? activity.getId() : null);
		preset.setCapacity((Integer) capacitySpinner.getValue());
		LootRule loot = (LootRule) lootDropdown.getSelectedItem();
		preset.setLootRule((loot == null ? LootRule.UNSPECIFIED : loot).name());
		preset.setMinKc((Integer) minKcSpinner.getValue());
		preset.setHardKc((Integer) hardKcSpinner.getValue());
		preset.setWorld(worldField.getText().trim());
		preset.setDescription(descriptionArea.getText());
		preset.setPrivateParty(privateCheck.isSelected());
		preset.setIronmanOnly(ironmanCheck.isSelected());
		preset.setIncludeLayout(includeLayoutCheck.isSelected());
		preset.setHardMode(hardModeCheck.isSelected());
		preset.setInvocation((Integer) invocationSpinner.getValue());
		preset.setLearner(learnerCheck.isSelected());
		preset.setTeacher(teacherCheck.isSelected());
		if (activity != null && activity.hasRoles())
		{
			preset.setRequiredRoles(captureRequiredRoles(activity, (Integer) capacitySpinner.getValue()));
			Role mine = (Role) myRoleDropdown.getSelectedItem();
			preset.setHostRole(mine != null ? mine.getId() : null);
		}
		return preset;
	}

	private void applyPreset(PartyPreset preset)
	{
		if (preset == null)
		{
			return;
		}
		if (preset.getActivityId() != null)
		{
			Activity activity = Activity.fromId(preset.getActivityId());
			if (activity != null)
			{
				activityDropdown.setSelectedItem(activity);
			}
		}
		applyActivityBounds();

		SpinnerNumberModel model = (SpinnerNumberModel) capacitySpinner.getModel();
		int min = ((Number) model.getMinimum()).intValue();
		int max = ((Number) model.getMaximum()).intValue();
		int wanted = preset.getCapacity() <= 0 ? min : preset.getCapacity();
		model.setValue(Math.min(max, Math.max(min, wanted)));

		lootDropdown.setSelectedItem(LootRule.fromName(preset.getLootRule()));
		minKcSpinner.setValue(Math.max(0, preset.getMinKc()));
		hardKcSpinner.setValue(Math.max(0, preset.getHardKc()));
		worldField.setText(preset.getWorld() != null ? preset.getWorld() : "");
		descriptionArea.setText(preset.getDescription() != null ? preset.getDescription() : "");
		privateCheck.setSelected(preset.isPrivateParty());
		// Honour the ironman-only rule even if the saved preset had it ticked.
		ironmanCheck.setSelected(preset.isIronmanOnly() && AccountTypes.isIronman(accountTypeSupplier.get()));
		includeLayoutCheck.setSelected(preset.isIncludeLayout());
		hardModeCheck.setSelected(preset.isHardMode());
		invocationSpinner.setValue(Math.max(0, Math.min(600, preset.getInvocation())));
		learnerCheck.setSelected(preset.isLearner());
		teacherCheck.setSelected(preset.isTeacher());
		applyActivityBounds(); // refresh row visibility (and rebuild role controls)
		applyRolePreset(preset);
	}

	/** Restore the saved role composition + host role into the (already-rebuilt) controls. */
	private void applyRolePreset(PartyPreset preset)
	{
		// Restore CoX count spinners (ToB has none - its composition is fixed by size).
		if (!roleCountSpinners.isEmpty() && preset.getRequiredRoles() != null)
		{
			Map<String, Integer> counts = new HashMap<>();
			for (String roleId : preset.getRequiredRoles())
			{
				counts.merge(roleId, 1, Integer::sum);
			}
			rebuildingRoles = true;
			for (Map.Entry<String, JSpinner> entry : roleCountSpinners.entrySet())
			{
				entry.getValue().setValue(counts.getOrDefault(entry.getKey(), 0));
			}
			rebuildingRoles = false;
			updateRoleTotal();
		}
		Role hostRole = Role.fromId(preset.getHostRole());
		if (hostRole != null)
		{
			myRoleDropdown.setSelectedItem(hostRole);
		}
	}

	private void saveLastPreset(PartyPreset preset)
	{
		configManager.setConfiguration(OSPartyConfig.GROUP, KEY_LAST_PRESET, gson.toJson(preset));
	}

	private PartyPreset loadLastPreset()
	{
		String json = configManager.getConfiguration(OSPartyConfig.GROUP, KEY_LAST_PRESET);
		if (json == null || json.isEmpty())
		{
			return null;
		}
		try
		{
			return gson.fromJson(json, PartyPreset.class);
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	private List<PartyPreset> loadFavourites()
	{
		String json = configManager.getConfiguration(OSPartyConfig.GROUP, KEY_FAVOURITES);
		if (json == null || json.isEmpty())
		{
			return new ArrayList<>();
		}
		try
		{
			PartyPreset[] favourites = gson.fromJson(json, PartyPreset[].class);
			return favourites == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(favourites));
		}
		catch (RuntimeException e)
		{
			return new ArrayList<>();
		}
	}

	private void saveFavourites(List<PartyPreset> favourites)
	{
		configManager.setConfiguration(OSPartyConfig.GROUP, KEY_FAVOURITES, gson.toJson(favourites));
	}

	/** Dropdown renderer that appends "(nearby)" to the recommended activity. */
	private class ActivityRenderer extends DefaultListCellRenderer
	{
		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index,
			boolean isSelected, boolean cellHasFocus)
		{
			super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
			if (value instanceof Activity)
			{
				Activity activity = (Activity) value;
				setText(activity == recommended
					? activity.getDisplayName() + "  (nearby)"
					: activity.getDisplayName());
			}
			return this;
		}
	}
}
