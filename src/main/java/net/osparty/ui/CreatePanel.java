package net.osparty.ui;

import net.osparty.OSPartyConfig;
import net.osparty.api.PartyService;
import net.osparty.model.AccountTypes;
import net.osparty.model.Activity;
import net.osparty.model.LootRule;
import net.osparty.model.Party;
import net.osparty.model.PartyEditRequest;
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
import java.awt.Font;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JOptionPane;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.ImageIcon;
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
import net.osparty.service.KillcountService;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import java.awt.Rectangle;
import java.util.function.IntSupplier;
import javax.swing.Scrollable;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.DocumentFilter;

/**
 * "Create" tab: a form to host a new party. On submit the logged in player is
 * recorded as the host and the party is pushed to the queue API; the player
 * then enters that party (managed on the "Party" tab). A player can only host
 * while not already in a party.
 */
class CreatePanel extends JPanel implements Scrollable
{
	private static final int DESC_MAX = 200;

	private static final String KEY_LAST_PRESET = "lastPreset";
	private static final String KEY_FAVOURITES = "favourites";

	private final PartyService partyService;
	private final OSPartyConfig config;
	private final Supplier<String> playerNameSupplier;
	private final PartyState partyState;
	private final LiveParty liveParty;
	private final Supplier<AccountType> accountTypeSupplier;
	private final LongSupplier accountHashSupplier;
	private final Supplier<int[]> mapRegionsSupplier;
	private final Supplier<String> coxLayoutSupplier;
	private final ConfigManager configManager;
	private final Gson gson;
	private final KillcountService killcountService;
	private final IntSupplier worldSupplier;
	private final JLabel descCounter = new JLabel();

	private final JComboBox<Activity> activityDropdown = new JComboBox<>(Activity.values());
	/** The activity we're currently standing near (suggested at the top of the list). */
	private Activity recommended;
	private boolean rebuildingDropdown;
	private final JComboBox<LootRule> lootDropdown = new JComboBox<>(LootRule.values());
	private final JSpinner capacitySpinner;
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
	/** Chambers of Xeric team-size scaling, e.g. "3+4"; free text (host-defined format). */
	private final JTextField coxScaleField = new JTextField();
	private JPanel coxScaleRow;
	private final JButton createButton = new JButton("Create party");
	private final JLabel statusLabel = new JLabel();

	/** "Join existing" section: apply to a party by invite code (delegates the apply logic to the Search tab). */
	private final JTextField joinCodeField = new JTextField();
	private final JButton joinCodeButton = new JButton("Join");
	private JPanel joinExistingSection;
	/** Runs the actual join-by-code apply; args are (code, statusSink). Set by {@link #setJoinByCodeHandler}. */
	private BiConsumer<String, Consumer<String>> joinByCodeHandler;
	private JPanel difficultyHeader;
	private JPanel rolesHeader;

	private final JComboBox<String> favouriteDropdown = new JComboBox<>();
	private boolean rebuildingFavourites;

	private final JSpinner minKcSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 100_000, 10));
	private final JLabel hardKcLabel = new JLabel("Minimum CM KC");
	private final JSpinner hardKcSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 100_000, 10));
	private JPanel minKcField;
	private JPanel hardKcField;

	private final JComboBox<Role> myRoleDropdown = new JComboBox<>();
	private JPanel rolesSection;
	private JPanel roleCountsPanel;
	private final JLabel roleTotalLabel = new JLabel();
	private final LinkedHashMap<String, JSpinner> roleCountSpinners = new LinkedHashMap<>();
	private boolean rebuildingRoles;

	private static final String LOGIN_HINT = "Log in to create a party.";
	private boolean creating;

	/** True while the form is editing an existing hosted party rather than creating one. */
	private boolean editing;
	/** Invoked after a successful edit so the owning panel can return to the Party tab. */
	private Runnable onEditDone;

	CreatePanel(PartyService partyService, OSPartyConfig config, Supplier<String> playerNameSupplier,
		PartyState partyState, LiveParty liveParty, Supplier<AccountType> accountTypeSupplier,
		LongSupplier accountHashSupplier, Supplier<int[]> mapRegionsSupplier, Supplier<String> coxLayoutSupplier,
		ConfigManager configManager, Gson gson, KillcountService killcountService, IntSupplier worldSupplier)
	{
		this.gson = gson;
		this.killcountService = killcountService;
		this.worldSupplier = worldSupplier;
		this.partyService = partyService;
		this.config = config;
		this.playerNameSupplier = playerNameSupplier;
		this.partyState = partyState;
		this.liveParty = liveParty;
		this.accountTypeSupplier = accountTypeSupplier;
		this.accountHashSupplier = accountHashSupplier;
		this.mapRegionsSupplier = mapRegionsSupplier;
		this.coxLayoutSupplier = coxLayoutSupplier;
		this.configManager = configManager;

		int defaultCapacity = Math.max(1, config.defaultCapacity());
		this.capacitySpinner = new JSpinner(new SpinnerNumberModel(defaultCapacity, 1, 100, 1));

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(buildFavourites());

		// ---- Join existing ---- (apply to a party by invite code, above the create form)
		joinExistingSection = buildJoinExisting();
		add(joinExistingSection);

		// ---- Basics ----
		add(sectionHeader("Basics"));
		add(field("Activity", activityDropdown));
		add(field("Party size", capacitySpinner));
		add(field("Loot rule", lootDropdown));

		descriptionArea.setLineWrap(true);
		descriptionArea.setWrapStyleWord(true);
		descriptionArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		descriptionArea.setForeground(Color.WHITE);
		// Cap the description length and show a live used/limit counter (point 24).
		((AbstractDocument) descriptionArea.getDocument()).setDocumentFilter(new DocumentFilter()
		{
			@Override
			public void insertString(FilterBypass fb, int offset, String string, javax.swing.text.AttributeSet attr)
				throws javax.swing.text.BadLocationException
			{
				replace(fb, offset, 0, string, attr);
			}

			@Override
			public void replace(FilterBypass fb, int offset, int length, String text,
				javax.swing.text.AttributeSet attr) throws javax.swing.text.BadLocationException
			{
				int room = DESC_MAX - (fb.getDocument().getLength() - length);
				if (room <= 0)
				{
					return;
				}
				String ins = text != null && text.length() > room ? text.substring(0, room) : text;
				super.replace(fb, offset, length, ins, attr);
			}
		});
		add(field("Description (optional)", new JScrollPane(descriptionArea)));

		descCounter.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		descCounter.setFont(FontManager.getRunescapeSmallFont());
		descCounter.setAlignmentX(Component.LEFT_ALIGNMENT);
		descriptionArea.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				updateDescCounter();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				updateDescCounter();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				updateDescCounter();
			}
		});
		updateDescCounter();
		add(descCounter);

		// ---- Requirements ----
		add(sectionHeader("Requirements"));
		minKcField = field("Minimum KC", minKcSpinner);
		add(minKcField);
		hardKcField = field(hardKcLabel, hardKcSpinner);
		add(hardKcField);
		add(checkBoxRow(privateCheck));
		add(checkBoxRow(ironmanCheck));

		// ---- Difficulty ---- (header + rows hidden by applyActivityBounds when N/A)
		difficultyHeader = sectionHeader("Difficulty");
		add(difficultyHeader);
		// Chambers of Xeric only: shown/hidden by applyActivityBounds.
		includeLayoutRow = checkBoxRow(includeLayoutCheck);
		includeLayoutRow.setVisible(false);
		add(includeLayoutRow);

		// A CM/HMT toggle (CoX/ToB) or an invocation level (ToA). Only the relevant
		// control is shown, driven by applyActivityBounds.
		hardModeRow = checkBoxRow(hardModeCheck);
		hardModeRow.setVisible(false);
		add(hardModeRow);

		invocationRow = field("Invocation level", invocationSpinner);
		invocationRow.setVisible(false);
		add(invocationRow);

		// Chambers of Xeric only: the team-size scaling the raid is run at, e.g. a 3-man
		// scaled to 4 is written "3+4". Free text (the host's own notation), constrained to
		// digits and '+' so it stays a compact scale string.
		coxScaleField.setToolTipText("Team-size scaling, e.g. 3+4");
		((AbstractDocument) coxScaleField.getDocument()).setDocumentFilter(new DocumentFilter()
		{
			private boolean allowed(String text)
			{
				return text.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '+');
			}

			@Override
			public void insertString(FilterBypass fb, int offset, String string,
				javax.swing.text.AttributeSet attr) throws javax.swing.text.BadLocationException
			{
				replace(fb, offset, 0, string, attr);
			}

			@Override
			public void replace(FilterBypass fb, int offset, int length, String text,
				javax.swing.text.AttributeSet attr) throws javax.swing.text.BadLocationException
			{
				if (text == null || (allowed(text) && fb.getDocument().getLength() - length + text.length() <= 7))
				{
					super.replace(fb, offset, length, text, attr);
				}
			}
		});
		coxScaleRow = field("Scale (e.g. 3+4)", coxScaleField);
		coxScaleRow.setVisible(false);
		add(coxScaleRow);

		// Learner-raid tagging (raids only): ticking either Learner or Teacher marks
		// the ad as a learner raid; neither leaves it a normal raid.
		learnerRow = buildLearnerRow();
		learnerRow.setVisible(false);
		add(learnerRow);

		// ---- Roles ---- (ToB/CoX only): the host's own role plus a count per role.
		rolesHeader = sectionHeader("Roles");
		add(rolesHeader);
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

		// Only ironman accounts may host an ironman-only party: the toggle is disabled
		// for mains (see updateIronmanToggle), so there's nothing to bounce here.

		createButton.setFocusPainted(false);
		createButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		createButton.addActionListener(e -> {
			if (editing)
			{
				saveEdit();
			}
			else
			{
				create();
			}
		});
		add(createButton);

		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		statusLabel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		add(statusLabel);

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
		updateIronmanToggle();
		refreshValidation();
		joinCodeButton.setEnabled(loggedIn);
		joinCodeButton.setToolTipText(loggedIn ? null : "Log in to join a party");
		if (!loggedIn)
		{
			setStatus(LOGIN_HINT);
		}
		else if (LOGIN_HINT.equals(statusLabel.getText()))
		{
			setStatus("");
		}
	}

	/** Disable the ironman-only toggle for non-ironman accounts, with a why tooltip (point 21). */
	private void updateIronmanToggle()
	{
		boolean iron = AccountTypes.isIronman(accountTypeSupplier.get());
		ironmanCheck.setEnabled(iron);
		if (!iron)
		{
			ironmanCheck.setSelected(false);
			ironmanCheck.setToolTipText("Only ironman accounts can host an ironman-only party.");
		}
		else
		{
			ironmanCheck.setToolTipText(null);
		}
	}

	/** Whether the form may be submitted: a valid role composition for role activities. */
	private boolean isFormValid()
	{
		Activity activity = (Activity) activityDropdown.getSelectedItem();
		if (activity == null)
		{
			return false;
		}
		if (activity.hasRoles())
		{
			// Mirror create()'s check exactly. captureRequiredRoles already returns the
			// fixed composition for ToB (always sized to capacity) and the spinner-built
			// composition for CoX — don't use assignedRoleTotal(), which is 0 for ToB.
			int capacity = (Integer) capacitySpinner.getValue();
			List<String> req = captureRequiredRoles(activity, capacity);
			if (!activity.hasFlexibleRoles())
			{
				int assigned = req == null ? 0 : req.size();
				if (assigned != capacity)
				{
					return false;
				}
			}
			Role mine = (Role) myRoleDropdown.getSelectedItem();
			String hostRole = mine != null ? mine.getId() : null;
			if (hostRole == null || !req.contains(hostRole))
			{
				return false;
			}
		}
		return true;
	}

	/** Enable/disable Create live based on validity (point 18); the role total label shows why. */
	private void refreshValidation()
	{
		boolean loggedIn = playerNameSupplier.get() != null;
		createButton.setEnabled(loggedIn && !creating && isFormValid());
	}

	private void updateDescCounter()
	{
		int len = descriptionArea.getDocument().getLength();
		descCounter.setText(len + "/" + DESC_MAX);
		descCounter.setForeground(len >= DESC_MAX
			? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
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

		// A minimum-KC bar only makes sense where there's a hiscore killcount to check
		// (Barbarian Assault, a minigame, has none).
		boolean hasKillcount = activity.hasKillcount();
		minKcField.setVisible(hasKillcount);
		if (!hasKillcount)
		{
			minKcSpinner.setValue(0);
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

		// Team-size scaling is a Chambers of Xeric concept only.
		coxScaleRow.setVisible(isCox);
		if (!isCox)
		{
			coxScaleField.setText("");
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

		// Hide a section header when none of its rows apply to this activity.
		boolean anyDifficulty = includeLayoutRow.isVisible() || hardModeRow.isVisible()
			|| invocationRow.isVisible() || coxScaleRow.isVisible() || learnerRow.isVisible();
		difficultyHeader.setVisible(anyDifficulty);
		rolesHeader.setVisible(hasRoles);

		refreshValidation();
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

		if (activity.hasFlexibleRoles())
		{
			// Barbarian Assault: no spinners - one of each role plus a flexible "extra" slot that
			// whoever applies doubles up (capped at two of any one role), for a team of the size.
			rebuildingRoles = true;
			roleCountSpinners.clear();
			roleCountsPanel.removeAll();
			rebuildingRoles = false;
			roleTotalLabel.setText("Team of " + capacity + ": one of each role, plus 1 extra (max 2 of a role).");
			roleTotalLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}
		else if (activity.hasFixedComposition(hardMode))
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
		refreshValidation();
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
		if (activity.hasFlexibleRoles())
		{
			// Barbarian Assault: the composition isn't laid out slot by slot. We advertise the
			// base roles (one of each is mandatory); the flexible "extra" is resolved as members
			// pick, so it isn't part of the stored multiset.
			List<String> roles = new ArrayList<>();
			for (Role role : activity.roles(false))
			{
				roles.add(role.getId());
			}
			return roles;
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
		// World is always the host's live world (no manual field — point 22).
		int hostWorld = worldSupplier != null ? worldSupplier.getAsInt() : 0;
		String world = hostWorld > 0 ? Integer.toString(hostWorld) : "";
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

		// A host must meet the killcount requirement they set (point 18). Block only when
		// we know they're below; if not yet looked up, kick off a lookup and allow.
		if ((minKc > 0 || minHardKc > 0) && killcountService != null)
		{
			KillcountService.Killcount kc = killcountService.cached(player, activity);
			if (kc == null)
			{
				killcountService.lookup(player, activity, this::refreshValidation);
			}
			else
			{
				boolean below = (minKc > 0 && kc.killCount >= 0 && kc.killCount < minKc)
					|| (minHardKc > 0 && kc.hardModeKillCount >= 0 && kc.hardModeKillCount < minHardKc);
				if (below)
				{
					setStatus("You don't meet the minimum KC you set — you must meet it yourself.");
					return;
				}
			}
		}

		// Chambers of Xeric: advertise the live raid layout (sent via heartbeat once
		// the host is inside the raid), rather than baking it into the description.
		boolean advertiseLayout = includeLayoutCheck.isSelected() && "cox".equals(activityId);

		// Raid difficulty: CM/HMT toggle (CoX/ToB) or invocation level (ToA).
		boolean hardMode = activity.hasHardMode() && !activity.usesInvocation() && hardModeCheck.isSelected();
		int invocation = activity.usesInvocation() ? (Integer) invocationSpinner.getValue() : 0;
		// Chambers of Xeric team-size scaling (e.g. "3+4"); empty for other activities.
		String coxScale = "cox".equals(activityId) ? coxScaleField.getText().trim() : "";

		// Learner-raid tagging (raids only): either flag marks the ad as a learner raid.
		boolean learner = activity.isRaid() && learnerCheck.isSelected();
		boolean teacher = activity.isRaid() && teacherCheck.isSelected();

		// Roles (ToB/CoX): the composition must fill exactly the party size, and the
		// host's chosen role must be one of those slots (the host fills it). ToB's
		// composition is fixed by size, so it always validates; CoX is host-defined.
		RoleSelection selection = captureRoleSelection(activity, capacity);
		if (selection == null)
		{
			return;
		}
		final List<String> requiredRoles = selection.requiredRoles;
		final String hostRole = selection.hostRole;

		// Remember these settings so the form is pre-filled next time.
		saveLastPreset(captureForm(null));

		creating = true;
		createButton.setEnabled(false);
		setStatus("Creating party…");

		final String advertisedDescription = description;
		// A secret that authorises host-only changes to this ad: sent on create (the
		// server binds it to the party's session) and on later heartbeat/disband.
		final String hostKey = java.util.UUID.randomUUID().toString();
		// The passphrase must be generated on the client thread (RuneLite reads item
		// names to build it), so this is async; advertise once we have it.
		final long hostAccountHash = accountHashSupplier != null ? accountHashSupplier.getAsLong() : 0L;
		liveParty.generatePassphrase(passphrase -> {
			PartyRequest request = new PartyRequest(
				activityId, player, hostAccountHash, advertisedDescription, capacity, world, minKc, minHardKc,
				passphrase, privateParty, lootRule, ironmanOnly, hostAccountType, hardMode, invocation, coxScale,
				requiredRoles, hostRole, learner, teacher);

			partyService.createParty(request, hostKey,
				party -> SwingUtilities.invokeLater(
					() -> onCreated(party, passphrase, player, capacity, advertiseLayout, hostRole, learner, teacher,
						hostKey)),
				error -> SwingUtilities.invokeLater(() -> {
					creating = false;
					createButton.setEnabled(true);
					setStatus("Create failed — " + net.osparty.api.PartyErrors.friendly(error));
				}));
		});
	}

	private void onCreated(Party party, String passphrase, String host, int capacity, boolean advertiseLayout,
		String hostRole, boolean hostLearner, boolean hostTeacher, String hostKey)
	{
		creating = false;
		createButton.setEnabled(true);
		descriptionArea.setText("");
		// Remember whether to advertise the live CoX layout, so the Party tab's
		// heartbeat knows to include it once the host is inside the raid.
		partyState.setAdvertiseLayout(advertiseLayout);
		// Host the live room now that the ad is up; applicants who join are pending
		// until admitted from the Party tab.
		liveParty.hostParty(passphrase, host, party.getActivity(), capacity, false, hostRole, hostLearner, hostTeacher);
		if (party.isPrivateParty() && party.getInviteCode() != null)
		{
			setStatus("Private party created — invite code " + party.getInviteCode() + " (also on the Party tab).");
		}
		else
		{
			setStatus("Party created — manage it on the Party tab.");
		}
		partyState.setHosting(party, hostKey);
	}

	/**
	 * The "Join existing" section: an invite-code field + Join button, sitting above the
	 * create form so hosting and joining live in one place. The apply itself is delegated to
	 * {@link #joinByCodeHandler} (wired to the Search tab, which owns the apply flow).
	 */
	private JPanel buildJoinExisting()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);
		section.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.add(sectionHeader("Join existing"));

		joinCodeButton.setFocusPainted(false);
		joinCodeButton.addActionListener(e -> submitJoinByCode());
		joinCodeField.addActionListener(e -> submitJoinByCode());

		JPanel row = new JPanel(new BorderLayout(6, 0))
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.add(joinCodeField, BorderLayout.CENTER);
		row.add(joinCodeButton, BorderLayout.EAST);
		section.add(field("Join a private party by code", row));
		return section;
	}

	private void submitJoinByCode()
	{
		if (joinByCodeHandler == null)
		{
			return;
		}
		joinByCodeHandler.accept(joinCodeField.getText(), this::setStatus);
	}

	/** Wire the join-by-code apply (owned by the Search tab); {@code (code, statusSink)}. */
	void setJoinByCodeHandler(BiConsumer<String, Consumer<String>> handler)
	{
		this.joinByCodeHandler = handler;
	}

	/** A bold section divider in the Create form (Basics / Requirements / Difficulty / Roles). */
	private JPanel sectionHeader(String text)
	{
		JPanel row = new JPanel(new BorderLayout())
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(8, 0, 3, 0)));
		JLabel label = new JLabel(text);
		label.setForeground(ColorScheme.BRAND_ORANGE);
		label.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		row.add(label, BorderLayout.WEST);
		return row;
	}

	/** The host's validated role composition + own role for the current form. */
	private static final class RoleSelection
	{
		final List<String> requiredRoles;
		final String hostRole;

		RoleSelection(List<String> requiredRoles, String hostRole)
		{
			this.requiredRoles = requiredRoles;
			this.hostRole = hostRole;
		}
	}

	/**
	 * Validate and capture the role composition for {@code activity} at {@code capacity}. Returns
	 * the selection, or null after setting a status message when the composition is invalid.
	 * Activities without roles return an empty selection.
	 */
	private RoleSelection captureRoleSelection(Activity activity, int capacity)
	{
		if (!activity.hasRoles())
		{
			return new RoleSelection(null, null);
		}
		List<String> requiredRoles = captureRequiredRoles(activity, capacity);
		// Flexible activities (BA) advertise the base roles rather than a size-exact multiset —
		// the "extra" slot is filled by whoever doubles up, so don't demand assigned == capacity.
		if (!activity.hasFlexibleRoles())
		{
			int assigned = requiredRoles == null ? 0 : requiredRoles.size();
			if (assigned != capacity)
			{
				setStatus("Assign exactly " + capacity + " role slots (currently " + assigned + ").");
				return null;
			}
		}
		Role mine = (Role) myRoleDropdown.getSelectedItem();
		String hostRole = mine != null ? mine.getId() : null;
		if (hostRole == null || !requiredRoles.contains(hostRole))
		{
			setStatus("Add at least one " + (mine != null ? mine.getDisplayName() : "of your role")
				+ " slot — that's the role you'll fill.");
			return null;
		}
		return new RoleSelection(requiredRoles, hostRole);
	}

	// ---- edit an existing hosted party ---------------------------------------

	/** Called by the owning panel to return to the Party tab after a successful save. */
	void setOnEditDone(Runnable onEditDone)
	{
		this.onEditDone = onEditDone;
	}

	/**
	 * Switch the form into edit mode, pre-filled from {@code party}. The activity can't be
	 * changed (it keys the live room and the role/difficulty model), so its dropdown is locked.
	 */
	void enterEditMode(Party party)
	{
		if (party == null)
		{
			return;
		}
		editing = true;
		joinExistingSection.setVisible(false); // editing an existing party, not joining another
		applyPreset(partyToPreset(party));
		activityDropdown.setEnabled(false);
		createButton.setText("Save changes");
		createButton.setEnabled(true);
		setStatus("Editing your party — the activity can't be changed.");
	}

	/** Leave edit mode and restore the create form (defaults / last preset). */
	void exitEditMode()
	{
		if (!editing)
		{
			return;
		}
		editing = false;
		joinExistingSection.setVisible(true);
		activityDropdown.setEnabled(true);
		createButton.setText("Create party");
		applyPreset(loadLastPreset());
		applyRecommendation();
		updateLoginState();
	}

	/** Map a hosted {@link Party} onto a {@link PartyPreset} so {@link #applyPreset} can fill the form. */
	private PartyPreset partyToPreset(Party party)
	{
		PartyPreset preset = new PartyPreset();
		preset.setActivityId(party.getActivity());
		preset.setCapacity(party.getCapacity());
		preset.setLootRule(party.getLootRule());
		preset.setMinKc(party.getMinKillCount());
		preset.setHardKc(party.getMinHardModeKillCount());
		preset.setWorld(party.getWorld());
		preset.setDescription(party.getDescription());
		preset.setPrivateParty(party.isPrivateParty());
		preset.setIronmanOnly(party.isIronmanOnly());
		preset.setIncludeLayout(partyState.isAdvertiseLayout());
		preset.setHardMode(party.isHardMode());
		preset.setInvocation(party.getInvocation());
		preset.setCoxScale(party.getCoxScale());
		preset.setLearner(party.isLearner());
		preset.setTeacher(party.isTeacher());
		preset.setRequiredRoles(party.getRequiredRoles());
		preset.setHostRole(party.getHostRole());
		return preset;
	}

	private void saveEdit()
	{
		Party party = partyState.getCurrentParty();
		if (party == null || !partyState.isHost())
		{
			setStatus("You're not hosting a party to edit.");
			return;
		}

		Activity activity = (Activity) activityDropdown.getSelectedItem();
		if (activity == null)
		{
			return;
		}

		int capacity = (Integer) capacitySpinner.getValue();
		// Can't shrink the party below the people already in it (host + admitted members).
		int present = liveParty.isConnected()
			? (int) liveParty.roster().stream()
				.filter(m -> m.getStatus() != net.osparty.party.LiveParty.Status.PENDING).count()
			: 1;
		if (capacity < present)
		{
			setStatus("Capacity can't be below the " + present + " already in the party.");
			return;
		}

		int minHardKc = activity.hasHardMode() ? (Integer) hardKcSpinner.getValue() : 0;
		String description = descriptionArea.getText().trim();
		// World is always the host's live world (the manual field was removed).
		int hostWorld = worldSupplier != null ? worldSupplier.getAsInt() : 0;
		String world = hostWorld > 0 ? Integer.toString(hostWorld) : "";
		int minKc = (Integer) minKcSpinner.getValue();
		LootRule loot = (LootRule) lootDropdown.getSelectedItem();
		String lootRule = (loot == null ? LootRule.UNSPECIFIED : loot).name();
		boolean privateParty = privateCheck.isSelected();
		boolean ironmanOnly = ironmanCheck.isSelected();
		AccountType accountType = accountTypeSupplier.get();
		if (ironmanOnly && !AccountTypes.isIronman(accountType))
		{
			setStatus("Only ironman accounts can host an ironman-only party.");
			return;
		}

		boolean advertiseLayout = includeLayoutCheck.isSelected() && "cox".equals(activity.getId());
		boolean hardMode = activity.hasHardMode() && !activity.usesInvocation() && hardModeCheck.isSelected();
		int invocation = activity.usesInvocation() ? (Integer) invocationSpinner.getValue() : 0;
		String coxScale = "cox".equals(activity.getId()) ? coxScaleField.getText().trim() : "";
		boolean learner = activity.isRaid() && learnerCheck.isSelected();
		boolean teacher = activity.isRaid() && teacherCheck.isSelected();

		RoleSelection selection = captureRoleSelection(activity, capacity);
		if (selection == null)
		{
			return;
		}

		// Remember the new settings so a future create is pre-filled with them too.
		saveLastPreset(captureForm(null));

		PartyEditRequest edit = new PartyEditRequest(description, capacity, world, minKc, minHardKc, lootRule,
			privateParty, ironmanOnly, invocation, hardMode, coxScale, selection.requiredRoles, selection.hostRole,
			learner, teacher);

		createButton.setEnabled(false);
		setStatus("Saving changes…");
		partyService.editParty(party.getId(), partyState.getHostKey(), edit,
			ignored -> SwingUtilities.invokeLater(() -> onEdited(party, edit, advertiseLayout)),
			error -> SwingUtilities.invokeLater(() -> {
				createButton.setEnabled(true);
				setStatus("Edit failed — " + net.osparty.api.PartyErrors.friendly(error));
			}));
	}

	/** Apply the saved edit to our local party copy and the live room, then leave edit mode. */
	private void onEdited(Party party, PartyEditRequest edit, boolean advertiseLayout)
	{
		createButton.setEnabled(true);

		// Reflect the edit on our local hosted-party copy so the Party tab updates at once
		// (the server's own 'updated' broadcast only refreshes the search list).
		party.setDescription(edit.getDescription());
		party.setCapacity(edit.getCapacity());
		party.setWorld(edit.getWorld());
		party.setMinKillCount(edit.getMinKillCount());
		party.setMinHardModeKillCount(edit.getMinHardModeKillCount());
		party.setLootRule(edit.getLootRule());
		party.setPrivateParty(edit.isPrivateParty());
		party.setIronmanOnly(edit.isIronmanOnly());
		party.setInvocation(edit.getInvocation());
		party.setHardMode(edit.isHardMode());
		party.setCoxScale(edit.getCoxScale());
		party.setRequiredRoles(edit.getRequiredRoles());
		party.setHostRole(edit.getHostRole());
		party.setLearner(edit.isLearner());
		party.setTeacher(edit.isTeacher());

		partyState.setAdvertiseLayout(advertiseLayout);
		partyState.update(party);

		// Sync the live P2P room so admit limits, the host's role and learner/teacher markers
		// follow the edit and re-broadcast to members.
		liveParty.setCapacity(edit.getCapacity());
		liveParty.setLocalRole(edit.getHostRole());
		liveParty.setLocalLearner(edit.isLearner());
		liveParty.setLocalTeacher(edit.isTeacher());

		exitEditMode();
		setStatus("Party updated.");
		if (onEditDone != null)
		{
			onEditDone.run();
		}
	}

	private void setStatus(String text)
	{
		statusLabel.setText(text);
	}

	// ---- Scrollable: track the viewport width so fields fill it, and scroll vertically (point 17) ----

	@Override
	public Dimension getPreferredScrollableViewportSize()
	{
		return getPreferredSize();
	}

	@Override
	public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
	{
		return 16;
	}

	@Override
	public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
	{
		return visibleRect.height;
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

	// ---- favourites / presets ------------------------------------------------

	private static final String FAV_PLACEHOLDER = "Presets…";

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
				setStatus("Loaded preset \"" + favourites.get(idx - 1).getName() + "\".");
			}
		});

		JButton save = miniButton(StatusIcons.PLUS, "Save the current settings as a preset");
		save.addActionListener(e -> saveCurrentAsFavourite());

		JButton remove = miniButton(StatusIcons.CROSS, "Remove the selected preset");
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

	private JButton miniButton(ImageIcon icon, String tooltip)
	{
		JButton button = new JButton(icon);
		button.setFocusPainted(false);
		button.setMargin(new Insets(2, 6, 2, 6));
		button.setToolTipText(tooltip);
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
		String name = JOptionPane.showInputDialog(this, "Preset name:", "Save preset",
			JOptionPane.PLAIN_MESSAGE);
		if (name == null)
		{
			return;
		}
		name = name.trim();
		if (name.isEmpty() || FAV_PLACEHOLDER.equals(name))
		{
			setStatus("Enter a name for the preset.");
			return;
		}
		List<PartyPreset> favourites = loadFavourites();
		String chosen = name;
		favourites.removeIf(f -> chosen.equalsIgnoreCase(f.getName())); // overwrite a same-named one
		favourites.add(captureForm(chosen));
		saveFavourites(favourites);
		rebuildFavourites();
		favouriteDropdown.setSelectedItem(chosen);
		setStatus("Saved preset \"" + chosen + "\".");
	}

	private void removeSelectedFavourite()
	{
		int idx = favouriteDropdown.getSelectedIndex();
		if (idx <= 0)
		{
			setStatus("Select a preset to remove.");
			return;
		}
		List<PartyPreset> favourites = loadFavourites();
		if (idx - 1 < favourites.size())
		{
			String removed = favourites.remove(idx - 1).getName();
			saveFavourites(favourites);
			rebuildFavourites();
			setStatus("Removed preset \"" + removed + "\".");
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
		preset.setDescription(descriptionArea.getText());
		preset.setPrivateParty(privateCheck.isSelected());
		preset.setIronmanOnly(ironmanCheck.isSelected());
		preset.setIncludeLayout(includeLayoutCheck.isSelected());
		preset.setHardMode(hardModeCheck.isSelected());
		preset.setInvocation((Integer) invocationSpinner.getValue());
		preset.setCoxScale(coxScaleField.getText().trim());
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
		descriptionArea.setText(preset.getDescription() != null ? preset.getDescription() : "");
		privateCheck.setSelected(preset.isPrivateParty());
		// Honour the ironman-only rule even if the saved preset had it ticked.
		ironmanCheck.setSelected(preset.isIronmanOnly() && AccountTypes.isIronman(accountTypeSupplier.get()));
		includeLayoutCheck.setSelected(preset.isIncludeLayout());
		hardModeCheck.setSelected(preset.isHardMode());
		invocationSpinner.setValue(Math.max(0, Math.min(600, preset.getInvocation())));
		coxScaleField.setText(preset.getCoxScale() != null ? preset.getCoxScale() : "");
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
