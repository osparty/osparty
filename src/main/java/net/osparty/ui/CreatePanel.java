package net.osparty.ui;

import net.osparty.OSPartyConfig;
import net.osparty.api.BoardService;
import net.osparty.model.AccountTypes;
import net.osparty.model.Activity;
import net.osparty.model.LootRule;
import net.osparty.model.Advertisement;
import net.osparty.model.AdvertisementEditRequest;
import net.osparty.model.AdvertisementPreset;
import net.osparty.model.AdvertisementRequest;
import net.osparty.model.Role;
import net.osparty.party.LivePartyBackend;
import net.osparty.party.PartyStatus;
import com.google.gson.Gson;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
import java.util.function.IntSupplier;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.DocumentFilter;

/** "Create" tab: a form to host a new party (only while not already in one). */
class CreatePanel extends ScrollableColumn
{
	private static final int DESC_MAX = 200;

	private static final String KEY_LAST_PRESET = "lastPreset";
	/** Value stays "favourites": it is what presets have always been saved under. */
	private static final String KEY_PRESETS = "favourites";

	private final BoardService boardService;
	private final OSPartyConfig config;
	private final Supplier<String> playerNameSupplier;
	private final PartyState partyState;
	private final LivePartyBackend liveParty;
	private final Supplier<AccountType> accountTypeSupplier;
	private final LongSupplier accountHashSupplier;
	private final Supplier<int[]> mapRegionsSupplier;
	private final ConfigManager configManager;
	private final Gson gson;
	private final KillcountService killcountService;
	private final IntSupplier worldSupplier;
	private final JLabel descCounter = new JLabel();

	private final JComboBox<Activity> activityDropdown = new JComboBox<>(sortedActivities());
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
	private final JTextArea statusLabel = PanelWidgets.wrappingText();

	/** "Join existing" section: apply to a party by invite code (delegates the apply logic to the Search tab). */
	private final JTextField joinCodeField = new JTextField();
	private final JButton joinCodeButton = new JButton("Join");
	private JPanel joinExistingSection;
	/** Runs the actual join-by-code apply; args are (code, statusSink). Set by {@link #setJoinByCodeHandler}. */
	private BiConsumer<String, Consumer<String>> joinByCodeHandler;
	private JPanel difficultyHeader;
	private JPanel rolesHeader;
	/** Collapsible "Requirements", "Difficulty" and "Roles" sections, all collapsed by default. */
	private boolean requirementsExpanded;
	private final JButton requirementsToggle = new JButton();
	private JPanel requirementsContent;
	private boolean difficultyExpanded;
	private final JButton difficultyToggle = new JButton();
	private JPanel difficultyContent;
	private boolean rolesExpanded;
	private final JButton rolesToggle = new JButton();

	private final JComboBox<String> presetDropdown = new JComboBox<>();
	private boolean rebuildingPresets;

	private final JSpinner minKcSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 100_000, 10));
	private final JLabel hardKcLabel = new JLabel("Minimum CM KC");
	private final JSpinner hardKcSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 100_000, 10));
	private JPanel minKcField;
	private JPanel hardKcField;
	private final JTextArea kcWarningLabel = PanelWidgets.wrappingText();
	/** Latches the one-time auto-expand of Requirements while a blocking KC message is up. */
	private boolean kcBlockShown;

	private final JComboBox<Role> myRoleDropdown = new JComboBox<>();
	private JPanel rolesSection;
	private JPanel roleCountsPanel;
	/** Composition hint under "My role"; a wrapping text area so long summaries (BA, 4/5-man ToB) never truncate to "…". */
	private final JTextArea roleTotalLabel = PanelWidgets.wrappingText();
	private final LinkedHashMap<String, JSpinner> roleCountSpinners = new LinkedHashMap<>();
	private boolean rebuildingRoles;

	private static final String LOGIN_HINT = "Log in to create a party.";
	private boolean creating;
	/** How long to wait for the similar-party lookup before just creating. An older server never answers it. */
	private static final int SIMILAR_LOOKUP_TIMEOUT_MS = 1500;
	/** Where to ask about similar parties. Null until the plugin registers a surface for it. */
	private Consumer<SimilarParties> similarHandler;
	/** Applies to a party we suggested instead; args are (ad, roleChooser). Set by the plugin. */
	private BiConsumer<Advertisement, RoleChooser> applyToHandler;

	/** True while the form is editing an existing hosted party rather than creating one. */
	private boolean editing;
	/** Invoked after a successful edit so the owning panel can return to the Party tab. */
	private Runnable onEditDone;

	private final Timer recommendationTimer;
	private final Timer loginStateTimer;

	CreatePanel(BoardService boardService, OSPartyConfig config, Supplier<String> playerNameSupplier,
		PartyState partyState, LivePartyBackend liveParty, Supplier<AccountType> accountTypeSupplier,
		LongSupplier accountHashSupplier, Supplier<int[]> mapRegionsSupplier,
		ConfigManager configManager, Gson gson, KillcountService killcountService, IntSupplier worldSupplier)
	{
		super(null);
		this.gson = gson;
		this.killcountService = killcountService;
		this.worldSupplier = worldSupplier;
		this.boardService = boardService;
		this.config = config;
		this.playerNameSupplier = playerNameSupplier;
		this.partyState = partyState;
		this.liveParty = liveParty;
		this.accountTypeSupplier = accountTypeSupplier;
		this.accountHashSupplier = accountHashSupplier;
		this.mapRegionsSupplier = mapRegionsSupplier;
		this.configManager = configManager;

		int defaultCapacity = Math.max(1, config.defaultCapacity());
		this.capacitySpinner = new JSpinner(new SpinnerNumberModel(defaultCapacity, 1, 100, 1));

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(buildPresets());

		// ---- Join existing ---- (apply to a party by invite code, above the create form)
		joinExistingSection = buildJoinExisting();
		add(joinExistingSection);

		// ---- Basics ----
		add(SectionHeader.formDivider("Basics"));
		add(field("Activity", activityDropdown));
		add(field("Party size", capacitySpinner));
		add(field("Loot rule", lootDropdown));

		descriptionArea.setLineWrap(true);
		descriptionArea.setWrapStyleWord(true);
		descriptionArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		descriptionArea.setForeground(Color.WHITE);
		// Cap the description length and show a live used/limit counter.
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

		// ---- Requirements ---- (collapsible, collapsed by default)
		add(SectionHeader.formToggleRow(requirementsToggle, "Requirements", this::toggleRequirements));
		requirementsContent = PanelWidgets.cappedColumn();
		minKcField = field("Minimum KC", minKcSpinner);
		requirementsContent.add(minKcField);
		hardKcField = field(hardKcLabel, hardKcSpinner);
		requirementsContent.add(hardKcField);
		kcWarningLabel.setForeground(ColorScheme.PROGRESS_INPROGRESS_COLOR);
		kcWarningLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		kcWarningLabel.setVisible(false);
		requirementsContent.add(kcWarningLabel);
		minKcSpinner.addChangeListener(e -> updateKcWarning());
		hardKcSpinner.addChangeListener(e -> updateKcWarning());
		requirementsContent.add(checkBoxRow(privateCheck));
		requirementsContent.add(checkBoxRow(ironmanCheck));
		requirementsContent.setVisible(requirementsExpanded);
		add(requirementsContent);

		// ---- Difficulty ---- (collapsible; header + rows hidden by applyActivityBounds when N/A)
		difficultyHeader = SectionHeader.formToggleRow(difficultyToggle, "Difficulty", this::toggleDifficulty);
		add(difficultyHeader);
		difficultyContent = PanelWidgets.cappedColumn();
		// Chambers of Xeric only: shown/hidden by applyActivityBounds.
		includeLayoutRow = checkBoxRow(includeLayoutCheck);
		includeLayoutRow.setVisible(false);
		difficultyContent.add(includeLayoutRow);

		// A CM/HMT toggle (CoX/ToB) or an invocation level (ToA); applyActivityBounds picks one.
		hardModeRow = checkBoxRow(hardModeCheck);
		hardModeRow.setVisible(false);
		difficultyContent.add(hardModeRow);

		invocationRow = field("Invocation level", invocationSpinner);
		invocationRow.setVisible(false);
		difficultyContent.add(invocationRow);

		// Chambers of Xeric only: the scaling the raid is run at, entered as a plain number (the size
		// the raid is scaled to). It's shown combined with the party size, e.g. a 3-man scaled to 4
		// is entered as "4" and displayed as "3+4". Digits only.
		coxScaleField.setToolTipText("Scaling (team size the raid is scaled to), e.g. 4");
		((AbstractDocument) coxScaleField.getDocument()).setDocumentFilter(new DocumentFilter()
		{
			private boolean allowed(String text)
			{
				return text.chars().allMatch(c -> c >= '0' && c <= '9');
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
				if (text == null || (allowed(text) && fb.getDocument().getLength() - length + text.length() <= 3))
				{
					super.replace(fb, offset, length, text, attr);
				}
			}
		});
		coxScaleRow = field("Scale (e.g. 4)", coxScaleField);
		coxScaleRow.setVisible(false);
		difficultyContent.add(coxScaleRow);

		// Learner-raid tagging (raids only): ticking either Learner or Teacher marks
		// the ad as a learner raid; neither leaves it a normal raid.
		learnerRow = buildLearnerRow();
		learnerRow.setVisible(false);
		difficultyContent.add(learnerRow);
		difficultyContent.setVisible(difficultyExpanded);
		add(difficultyContent);

		// ---- Roles ---- (ToB/CoX only): the host's own role plus a count per role.
		// Collapsible and collapsed by default; only shown at all for role activities.
		rolesHeader = SectionHeader.formToggleRow(rolesToggle, "Roles", this::toggleRolesSection);
		add(rolesHeader);
		rolesSection = buildRolesSection();
		rolesSection.setVisible(false);
		add(rolesSection);

		// Party size drives ToB's composition and CoX's Fill count, so rebuild roles on change.
		capacitySpinner.addChangeListener(e -> {
			Activity activity = (Activity) activityDropdown.getSelectedItem();
			if (!rebuildingRoles && activity != null && activity.hasRoles())
			{
				rebuildRoles(activity);
				absorbRemainderIntoFill();
			}
		});

		// CoX's role split (normal vs CM) changes with hard mode, so rebuild on flip.
		hardModeCheck.addActionListener(e -> {
			Activity activity = (Activity) activityDropdown.getSelectedItem();
			if (!rebuildingRoles && activity != null && activity.hasRoles())
			{
				rebuildRoles(activity);
				absorbRemainderIntoFill();
			}
		});

		createButton.setFocusPainted(false);
		createButton.setBackground(ColorScheme.BRAND_ORANGE);
		createButton.setForeground(Color.WHITE);
		createButton.setFont(createButton.getFont().deriveFont(Font.BOLD));
		createButton.addPropertyChangeListener("enabled", e -> {
			boolean on = createButton.isEnabled();
			createButton.setBackground(on ? ColorScheme.BRAND_ORANGE : ColorScheme.MEDIUM_GRAY_COLOR);
			createButton.setForeground(on ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
		});
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
		JPanel createRow = PanelWidgets.cappedRow(new BorderLayout());
		createRow.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		createRow.add(createButton, BorderLayout.CENTER);
		add(createRow);

		// Sits directly under Create, where the click that raises it was made. Hidden until there is one.
		similarPanel.setVisible(false);
		add(similarPanel);

		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		add(statusLabel);

		activityDropdown.setRenderer(new ActivityRenderer());
		activityDropdown.addActionListener(e -> {
			if (!rebuildingDropdown)
			{
				clearKcRequirements();
				applyActivityBounds();
			}
		});
		AdvertisementPreset last = loadLastPreset();
		if (last != null)
		{
			applyPreset(last);
		}
		else
		{
			applyActivityBounds();
		}

		addAncestorListener(new AncestorListener()
		{
			@Override
			public void ancestorAdded(AncestorEvent event)
			{
				applyRecommendation();
				// Re-read the configured default so a changed party size applies without a restart.
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
		recommendationTimer = new Timer(10_000, e -> {
			if (isShowing())
			{
				applyRecommendation();
			}
		});
		recommendationTimer.start();

		loginStateTimer = new Timer(1_000, e -> {
			if (isShowing())
			{
				updateLoginState();
			}
		});
		loginStateTimer.start();

		updateLoginState();
	}

	/** Stop the ticks; called when the plugin shuts down (a running Timer holds the panel alive). */
	void dispose()
	{
		recommendationTimer.stop();
		loginStateTimer.stop();
	}

	/** The form stays usable logged out; only the Create button is gated on login. */
	private void updateLoginState()
	{
		boolean loggedIn = playerNameSupplier.get() != null;
		updateIronmanToggle();
		updateKcWarning();
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

	/** Disable the ironman-only toggle for non-ironman accounts, with a why tooltip. */
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
			// Mirror create(): use captureRequiredRoles, not assignedRoleTotal() (0 for ToB).
			int capacity = (Integer) capacitySpinner.getValue();
			List<String> req = captureRequiredRoles(activity, capacity);
			if (!activity.hasFlexibleRoles() && req.size() != capacity)
			{
				return false;
			}
			Role mine = (Role) myRoleDropdown.getSelectedItem();
			String hostRole = mine != null ? mine.getId() : null;
			if (hostRole == null)
			{
				return false;
			}
			// The host's own pick can consume a Fill/Any slot (e.g. 5x Fill CoX, hosting as Melee).
			if (!req.contains(hostRole) && !hasFillSlot(activity, req))
			{
				return false;
			}
		}
		return true;
	}

	/** Whether the composition still has a Fill/Any slot for this activity and difficulty. */
	private boolean hasFillSlot(Activity activity, List<String> requiredRoles)
	{
		Role fill = activity.fillRole(hardModeCheck.isSelected());
		return fill != null && requiredRoles.contains(fill.getId());
	}

	/** Enable/disable Create live based on validity; the role total label shows why. */
	private void refreshValidation()
	{
		boolean loggedIn = playerNameSupplier.get() != null;
		String shortfall = kcShortfall();
		createButton.setEnabled(loggedIn && !creating && shortfall == null && isFormValid());
		createButton.setToolTipText(shortfall);
	}

	private void updateDescCounter()
	{
		int len = descriptionArea.getDocument().getLength();
		descCounter.setText(len + "/" + DESC_MAX);
		descCounter.setForeground(len >= DESC_MAX
			? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
	}

	/** Float the nearby activity to the top of the list, but never move the selection off the host's pick. */
	private void applyRecommendation()
	{
		if (editing)
		{
			// The activity is locked to the hosted party's; reordering would reselect it programmatically.
			return;
		}
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
		for (Activity activity : sortedActivities())
		{
			if (activity != near)
			{
				activityDropdown.addItem(activity);
			}
		}
		// Only a form with nothing picked yet lands on the nearby activity.
		Activity select = current != null ? current : near;
		if (select != null)
		{
			activityDropdown.setSelectedItem(select);
		}
		rebuildingDropdown = false;
		applyActivityBounds();
	}

	/** All activities in alphabetical order for the dropdown (the nearby one still floats to the top). */
	private static Activity[] sortedActivities()
	{
		Activity[] sorted = Activity.values().clone();
		Arrays.sort(sorted, Comparator.comparing(Activity::getDisplayName, String.CASE_INSENSITIVE_ORDER));
		return sorted;
	}

	private JPanel field(String labelText, Component input)
	{
		return field(new JLabel(labelText), input);
	}

	private JPanel field(JLabel label, Component input)
	{
		// cappedRow tracks the preferred height; a fixed max collapses the field under BoxLayout.
		JPanel panel = PanelWidgets.cappedRow(new BorderLayout(0, 4));
		panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

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

		JPanel panel = PanelWidgets.cappedRow(new BorderLayout());
		panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
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

	private void clearKcRequirements()
	{
		minKcSpinner.setValue(0);
		hardKcSpinner.setValue(0);
		kcWarningLabel.setVisible(false);
	}

	private String kcShortfall()
	{
		Activity activity = (Activity) activityDropdown.getSelectedItem();
		String player = playerNameSupplier.get();
		if (activity == null || player == null || killcountService == null || !activity.hasKillcount())
		{
			return null;
		}
		int minKc = (Integer) minKcSpinner.getValue();
		int minHardKc = activity.hasHardMode() ? (Integer) hardKcSpinner.getValue() : 0;
		if (minKc <= 0 && minHardKc <= 0)
		{
			return null;
		}

		KillcountService.Killcount kc = killcountService.cached(player, activity);
		if (kc == null)
		{
			return null; // Not looked up yet; updateKcWarning() is fetching and will re-run this.
		}
		if (minKc > 0 && kc.isKnown(false) && kc.killCount < minKc)
		{
			return "You have " + kc.killCount + " " + activity.getDisplayName() + " KC, below the "
				+ minKc + " you're asking for. You must meet your own requirement.";
		}
		if (minHardKc > 0 && kc.isKnown(true) && kc.hardModeKillCount < minHardKc)
		{
			return "You have " + kc.hardModeKillCount + " " + activity.getHardModeLabel() + " KC, below the "
				+ minHardKc + " you're asking for. You must meet your own requirement.";
		}
		return null;
	}

	private void updateKcWarning()
	{
		Activity activity = (Activity) activityDropdown.getSelectedItem();
		String player = playerNameSupplier.get();
		int minKc = (Integer) minKcSpinner.getValue();
		int minHardKc = activity != null && activity.hasHardMode() ? (Integer) hardKcSpinner.getValue() : 0;
		if (activity == null || player == null || killcountService == null
			|| !activity.hasKillcount() || (minKc <= 0 && minHardKc <= 0))
		{
			showKcMessage(null, false);
			return;
		}

		KillcountService.Killcount kc = killcountService.cached(player, activity);
		if (kc == null)
		{
			showKcMessage(null, false);
			killcountService.lookup(player, activity, this::updateKcWarning);
			return;
		}

		String shortfall = kcShortfall();
		if (shortfall != null)
		{
			showKcMessage(shortfall, true);
		}
		else if (kc.unavailable)
		{
			showKcMessage("Hiscores are unavailable, so your own KC can't be checked right now. This "
				+ "party will be created without verifying it.", false);
		}
		else if ((minKc > 0 && kc.killCount < 0) || (minHardKc > 0 && kc.hardModeKillCount < 0))
		{
			showKcMessage("You're not ranked on the hiscores for this activity, so your own KC "
				+ "can't be checked.", false);
		}
		else
		{
			showKcMessage(null, false);
		}
	}

	private void showKcMessage(String message, boolean blocking)
	{
		kcWarningLabel.setText(message == null ? "" : message);
		kcWarningLabel.setForeground(blocking
			? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.PROGRESS_INPROGRESS_COLOR);
		kcWarningLabel.setVisible(message != null);
		// Expand once when the block first appears; a 1s tick re-asserting it would make Requirements uncollapsible.
		if (!blocking)
		{
			kcBlockShown = false;
		}
		else if (!kcBlockShown)
		{
			kcBlockShown = true;
			if (!requirementsExpanded)
			{
				toggleRequirements();
			}
		}
		refreshValidation();
	}

	private void applyActivityBounds()
	{
		Activity activity = (Activity) activityDropdown.getSelectedItem();
		if (activity == null)
		{
			return;
		}

		applyCapacityBounds(activity);
		applyKcRows(activity);
		applyDifficultyRows(activity);
		applyRoleRows(activity);

		// Hide a section header when none of its rows apply to this activity.
		boolean anyDifficulty = anyDifficultyRows();
		difficultyHeader.setVisible(anyDifficulty);
		difficultyContent.setVisible(anyDifficulty && difficultyExpanded);

		updateKcWarning();
		refreshValidation();
		revalidate();
		repaint();
	}

	private void applyCapacityBounds(Activity activity)
	{
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
	}

	private void applyKcRows(Activity activity)
	{
		// A minimum-KC bar only makes sense where there's a hiscore killcount (BA has none).
		boolean hasKillcount = activity.hasKillcount();
		minKcField.setVisible(hasKillcount);
		if (!hasKillcount)
		{
			minKcSpinner.setValue(0);
		}

		// The hard-mode KC requirement only applies to activities with one (CoX CM, ToB HM, ToA Expert).
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
	}

	private void applyDifficultyRows(Activity activity)
	{
		// The "include raid layout" option only makes sense for Chambers of Xeric.
		boolean isCox = isCox(activity);
		// Default it on when switching to CoX (a saved preset may still override it afterwards).
		if (isCox && !includeLayoutRow.isVisible())
		{
			includeLayoutCheck.setSelected(true);
		}
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
	}

	/** Roles: a "my role" dropdown + per-role count spinners for ToB/CoX. */
	private void applyRoleRows(Activity activity)
	{
		boolean hasRoles = activity.hasRoles();
		rolesHeader.setVisible(hasRoles);
		rolesSection.setVisible(hasRoles && rolesExpanded);
		if (hasRoles)
		{
			rebuildRoles(activity);
		}
	}

	private static boolean isCox(Activity activity)
	{
		return activity == Activity.CHAMBERS_OF_XERIC;
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
		roleTotalLabel.setLineWrap(true);
		roleTotalLabel.setWrapStyleWord(true);
		roleTotalLabel.setEditable(false);
		roleTotalLabel.setFocusable(false);
		roleTotalLabel.setOpaque(false);
		panel.add(roleTotalLabel);
		return panel;
	}

	/** Rebuild the role controls at the current party size: ToB is fixed by size, CoX uses count spinners. */
	private void rebuildRoles(Activity activity)
	{
		int capacity = (Integer) capacitySpinner.getValue();
		// The role split depends on the difficulty (CoX normal vs CM, ToB vs HMT).
		boolean hardMode = hardModeCheck.isSelected();
		List<Role> roles = activity.roles(hardMode, capacity);
		Role fillRole = activity.fillRole(hardMode);

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
			// Barbarian Assault: no spinners - one of each role plus a flexible "extra" slot.
			rebuildingRoles = true;
			roleCountSpinners.clear();
			roleCountsPanel.removeAll();
			rebuildingRoles = false;
			roleTotalLabel.setText("Team of " + capacity + ": one of each role, plus 1 extra (max 2 of a role).");
			roleTotalLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}
		else if (activity.hasFixedComposition())
		{
			// ToB/HMT: no spinners - the team make-up is determined by party size.
			rebuildingRoles = true;
			roleCountSpinners.clear();
			roleCountsPanel.removeAll();
			rebuildingRoles = false;
			roleTotalLabel.setText("Team: " + compositionSummary(captureRequiredRoles(activity, capacity)));
			roleTotalLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}
		else
		{
			// CoX: a count per role, summing to the party size.
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
				// Seed Fill (CoX only) with the whole party on first build so the total matches capacity.
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
			return; // no Fill slot for this activity (e.g. ToB) - nothing to absorb
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

	/** The required-role multiset for the activity (ToB fixed / CoX spinners); null only when it has no roles. */
	private List<String> captureRequiredRoles(Activity activity, int capacity)
	{
		if (activity == null || !activity.hasRoles())
		{
			return null;
		}
		if (activity.hasFlexibleRoles())
		{
			// Barbarian Assault: advertise the base roles only; the flexible "extra" isn't stored.
			List<String> roles = new ArrayList<>();
			for (Role role : activity.roles(false))
			{
				roles.add(role.getId());
			}
			return roles;
		}
		if (activity.hasFixedComposition())
		{
			List<String> roles = new ArrayList<>();
			for (Role role : activity.fixedComposition(capacity, hardModeCheck.isSelected()))
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

	private boolean hostMeetsOwnKc(Activity activity, String player, int minKc, int minHardKc, Runnable retry)
	{
		if ((minKc <= 0 && minHardKc <= 0) || killcountService == null || player == null || activity == null)
		{
			return true;
		}

		KillcountService.Killcount kc = killcountService.cached(player, activity);
		if (kc == null)
		{
			setStatus("Checking your KC…");
			createButton.setEnabled(false);
			killcountService.lookup(player, activity, () -> {
				updateKcWarning(); // also re-runs refreshValidation, re-enabling the button
				retry.run();
			});
			return false;
		}

		if (kc.unavailable)
		{
			killcountService.lookup(player, activity, this::updateKcWarning);
		}

		updateKcWarning();
		String shortfall = kcShortfall();
		if (shortfall != null)
		{
			setError(shortfall);
			return false;
		}
		return true;
	}

	/** The form's activity-guarded values, shared by {@link #create()} and {@link #saveEdit()}. */
	private final class FormValues
	{
		final int capacity = (Integer) capacitySpinner.getValue();
		final String description = descriptionArea.getText().trim();
		final int minKc = (Integer) minKcSpinner.getValue();
		final boolean privateParty = privateCheck.isSelected();
		final boolean ironmanOnly = ironmanCheck.isSelected();
		final String world;
		final String lootRule;
		final String hostAccountType;
		final int minHardKc;
		final boolean advertiseLayout;
		final boolean hardMode;
		final int invocation;
		final String coxScale;
		final boolean learner;
		final boolean teacher;

		FormValues(Activity activity)
		{
			// World is always the host's live world.
			int hostWorld = worldSupplier != null ? worldSupplier.getAsInt() : 0;
			world = hostWorld > 0 ? Integer.toString(hostWorld) : "";
			LootRule loot = (LootRule) lootDropdown.getSelectedItem();
			lootRule = (loot == null ? LootRule.UNSPECIFIED : loot).name();
			AccountType accountType = accountTypeSupplier.get();
			hostAccountType = accountType != null ? accountType.name() : null;
			minHardKc = activity.hasHardMode() ? (Integer) hardKcSpinner.getValue() : 0;
			// CoX: advertise the live raid layout (sent via heartbeat once inside), not baked into the description.
			advertiseLayout = includeLayoutCheck.isSelected() && isCox(activity);
			// Raid difficulty: CM/HMT toggle (CoX/ToB) or invocation level (ToA).
			hardMode = activity.hasHardMode() && !activity.usesInvocation() && hardModeCheck.isSelected();
			invocation = activity.usesInvocation() ? (Integer) invocationSpinner.getValue() : 0;
			// Chambers of Xeric team-size scaling (e.g. "3+4"); empty for other activities.
			coxScale = isCox(activity) ? coxScaleField.getText().trim() : "";
			// Learner-raid tagging (raids only): either flag marks the ad as a learner raid.
			learner = activity.isRaid() && learnerCheck.isSelected();
			teacher = activity.isRaid() && teacherCheck.isSelected();
		}
	}

	/** Snapshot the form for this activity; null (with an error shown) when it can't be advertised. */
	private FormValues captureFormValues(Activity activity)
	{
		if (ironmanCheck.isSelected() && !AccountTypes.isIronman(accountTypeSupplier.get()))
		{
			setError("Only ironman accounts can host an ironman-only party.");
			return null;
		}
		return new FormValues(activity);
	}

	private void create()
	{
		if (partyState.isInParty())
		{
			setError("Leave your current party before creating one.");
			return;
		}

		String player = playerNameSupplier.get();
		if (player == null)
		{
			setError("Log in before creating a party.");
			return;
		}

		Activity activity = (Activity) activityDropdown.getSelectedItem();
		if (activity == null)
		{
			return;
		}

		final FormValues form = captureFormValues(activity);
		if (form == null)
		{
			return;
		}

		if (!hostMeetsOwnKc(activity, player, form.minKc, form.minHardKc, this::create))
		{
			return;
		}

		// Roles (ToB/CoX): the composition must fill the party size and include the host's chosen role.
		RoleSelection selection = captureRoleSelection(activity, form.capacity);
		if (selection == null)
		{
			return;
		}
		final List<String> requiredRoles = selection.requiredRoles;
		final String hostRole = selection.hostRole;

		// Remember these settings so the form is pre-filled next time.
		saveLastPreset(captureForm(null));

		if (config.similarPartyCheck() && similarHandler != null)
		{
			askAboutSimilar(activity, form, requiredRoles, hostRole);
			return;
		}
		doCreate(activity, form, requiredRoles, hostRole);
	}

	/**
	 * Look for a party already running this before advertising beside it. The answer is the user's, not
	 * ours: everything joinable is offered, and creating anyway is one click.
	 *
	 * <p>Bounded by a timeout because an older server does not know the lookup and will never answer it.
	 * Timing out creates the party, which is what they asked for in the first place.
	 */
	private void askAboutSimilar(Activity activity, FormValues form, List<String> requiredRoles, String hostRole)
	{
		createButton.setEnabled(false);
		setStatus("Checking for parties already running this…");

		java.util.concurrent.atomic.AtomicBoolean answered = new java.util.concurrent.atomic.AtomicBoolean();
		Timer timeout = new Timer(SIMILAR_LOOKUP_TIMEOUT_MS, e ->
		{
			if (answered.compareAndSet(false, true))
			{
				doCreate(activity, form, requiredRoles, hostRole);
			}
		});
		timeout.setRepeats(false);
		timeout.start();

		boardService.fetchSimilarParties(activity.getId(), form.hardMode, matches ->
			SwingUtilities.invokeLater(() ->
			{
				if (!answered.compareAndSet(false, true))
				{
					return;
				}
				timeout.stop();
				if (matches == null || matches.isEmpty())
				{
					doCreate(activity, form, requiredRoles, hostRole);
					return;
				}
				createButton.setEnabled(true);
				setStatus("");
				similarHandler.accept(similarOffer(activity, form, requiredRoles, hostRole, matches));
			}));
	}

	private SimilarParties similarOffer(Activity activity, FormValues form, List<String> requiredRoles,
		String hostRole, List<Advertisement> matches)
	{
		// The question is up on two surfaces at once and each takes the other down, but a double answer
		// would create two parties, so it is settled here rather than trusted to the dismissals.
		java.util.concurrent.atomic.AtomicBoolean answered = new java.util.concurrent.atomic.AtomicBoolean();
		return new SimilarParties()
		{
			@Override
			public List<Advertisement> matches()
			{
				return matches;
			}

			@Override
			public void requestJoin(Advertisement ad, RoleChooser chooser)
			{
				once(() ->
				{
					setStatus("Asking " + ad.getHost() + " to let you in…");
					applyToHandler.accept(ad, chooser);
				});
			}

			@Override
			public void createAnyway()
			{
				once(() -> doCreate(activity, form, requiredRoles, hostRole));
			}

			@Override
			public void createAndStopAsking()
			{
				once(() ->
				{
					configManager.setConfiguration(OSPartyConfig.GROUP, OSPartyConfig.SIMILAR_PARTY_CHECK, false);
					doCreate(activity, form, requiredRoles, hostRole);
				});
			}

			private void once(Runnable action)
			{
				SwingUtilities.invokeLater(() ->
				{
					if (answered.compareAndSet(false, true))
					{
						action.run();
					}
				});
			}
		};
	}

	private void doCreate(Activity activity, FormValues form, List<String> requiredRoles, String hostRole)
	{
		String player = playerNameSupplier.get();
		if (player == null)
		{
			setError("Log in before creating a party.");
			createButton.setEnabled(true);
			return;
		}

		creating = true;
		createButton.setEnabled(false);
		setStatus("Creating party…");

		final String activityId = activity.getId();
		// A secret authorising host-only changes to this ad; bound to the session server-side.
		final String hostKey = java.util.UUID.randomUUID().toString();
		// The passphrase must be built on the client thread (reads item names), so this is async.
		final long hostAccountHash = accountHashSupplier != null ? accountHashSupplier.getAsLong() : 0L;
		liveParty.generatePassphrase(passphrase -> {
			AdvertisementRequest request = new AdvertisementRequest(
				activityId, player, hostAccountHash, form.description, form.capacity, form.world, form.minKc,
				form.minHardKc, passphrase, form.privateParty, form.lootRule, form.ironmanOnly, form.hostAccountType,
				form.hardMode, form.invocation, form.coxScale, requiredRoles, hostRole, form.learner, form.teacher);

			boardService.createAd(request, hostKey,
				ad -> SwingUtilities.invokeLater(
					() -> onCreated(ad, passphrase, player, form.capacity, form.advertiseLayout, hostRole,
						form.learner, form.teacher, hostKey)),
				error -> SwingUtilities.invokeLater(() -> {
					creating = false;
					createButton.setEnabled(true);
					setError("Create failed: " + net.osparty.api.PartyErrors.friendly(error));
				}));
		});
	}

	private void onCreated(Advertisement ad, String passphrase, String host, int capacity, boolean advertiseLayout,
		String hostRole, boolean hostLearner, boolean hostTeacher, String hostKey)
	{
		creating = false;
		createButton.setEnabled(true);
		descriptionArea.setText("");
		// Remember whether to advertise the live CoX layout, for the Party tab's heartbeat.
		partyState.setAdvertiseLayout(advertiseLayout);
		// Host the live room now the ad is up; applicants are pending until admitted.
		liveParty.hostParty(passphrase, host, ad.getActivity(), capacity, false, hostRole, hostLearner, hostTeacher);
		if (ad.isPrivateAd() && ad.getInviteCode() != null)
		{
			setSuccess("Private party created. Invite code " + ad.getInviteCode() + " (also on the Party tab).");
		}
		else
		{
			setSuccess("Party created. Manage it on the Party tab.");
		}
		partyState.setHosting(ad, hostKey);
	}

	/** The "Join existing" section: invite-code field + Join button; the apply is delegated to {@link #joinByCodeHandler}. */
	private JPanel buildJoinExisting()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);
		section.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.add(SectionHeader.formDivider("Join existing"));

		joinCodeButton.setFocusPainted(false);
		joinCodeButton.addActionListener(e -> submitJoinByCode());
		joinCodeField.addActionListener(e -> submitJoinByCode());

		JPanel row = PanelWidgets.cappedRow(new BorderLayout(6, 0));
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
	/**
	 * Inline "this is already running" prompt, directly under the Create button. EDT only.
	 *
	 * <p>A capped column, like every other child of this BoxLayout: a plain JPanel would default to
	 * centre alignment and to an unbounded height, and one misaligned child shifts the whole tab.
	 */
	private final JPanel similarPanel = PanelWidgets.cappedColumn();

	/**
	 * Show the inline prompt. Every button finishes the create the player started, so there is no way to
	 * leave it up: each one hides it again.
	 */
	void showSimilar(SimilarParties similar, RoleChooser chooser, Runnable onAnswered)
	{
		similarPanel.removeAll();
		List<Advertisement> matches = similar.matches();
		Advertisement best = matches.get(0);

		JPanel box = PanelWidgets.cappedRow(new BorderLayout(0, 4));
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, ColorScheme.BRAND_ORANGE),
			BorderFactory.createEmptyBorder(8, 8, 8, 8)));

		Activity activity = Activity.fromId(best.getActivity());
		String label = activity == null ? "this" : activity.displayName(best.isHardMode(), best.getInvocation());
		StringBuilder text = new StringBuilder("<html><body style='width:170px'>");
		text.append(matches.size() == 1 ? "<b>" + best.getHost() + "</b> is already running " + label
			: "<b>" + matches.size() + " parties</b> are already running " + label);
		text.append(" (").append(best.getSize()).append('/').append(best.getCapacity()).append(')');
		text.append(". Join instead of starting another?</body></html>");
		JLabel blurb = new JLabel(text.toString());
		blurb.setForeground(Color.WHITE);
		blurb.setFont(FontManager.getRunescapeSmallFont());
		box.add(blurb, BorderLayout.NORTH);

		JPanel buttons = new JPanel(new java.awt.GridLayout(3, 1, 0, 4));
		buttons.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		buttons.add(answerButton("Request join to " + best.getHost(), onAnswered,
			() -> similar.requestJoin(best, chooser)));
		buttons.add(answerButton("Create anyway", onAnswered, similar::createAnyway));
		buttons.add(answerButton("Create, don't ask again", onAnswered, similar::createAndStopAsking));
		box.add(buttons, BorderLayout.SOUTH);

		similarPanel.add(box);
		similarPanel.setVisible(true);
		similarPanel.revalidate();
		similarPanel.repaint();
	}

	private JButton answerButton(String label, Runnable onAnswered, Runnable action)
	{
		JButton button = new JButton(label);
		button.setFocusPainted(false);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.addActionListener(e ->
		{
			hideSimilar();
			if (onAnswered != null)
			{
				onAnswered.run();
			}
			action.run();
		});
		return button;
	}

	void hideSimilar()
	{
		similarPanel.removeAll();
		similarPanel.setVisible(false);
		similarPanel.revalidate();
		similarPanel.repaint();
	}

	/** Register where a "this is already running" prompt is shown, and how to apply to one of them. */
	void setSimilarHandlers(Consumer<SimilarParties> handler, BiConsumer<Advertisement, RoleChooser> applyTo)
	{
		this.similarHandler = handler;
		this.applyToHandler = applyTo;
	}

	void setJoinByCodeHandler(BiConsumer<String, Consumer<String>> handler)
	{
		this.joinByCodeHandler = handler;
	}

	private void toggleRequirements()
	{
		requirementsExpanded = !requirementsExpanded;
		requirementsContent.setVisible(requirementsExpanded);
		requirementsToggle.setIcon(requirementsExpanded
			? Carets.EXPANDED : Carets.COLLAPSED);
		revalidate();
		repaint();
	}

	/** Whether the current activity has any difficulty-section rows to show. */
	private boolean anyDifficultyRows()
	{
		return includeLayoutRow.isVisible() || hardModeRow.isVisible()
			|| invocationRow.isVisible() || coxScaleRow.isVisible() || learnerRow.isVisible();
	}

	private void toggleDifficulty()
	{
		difficultyExpanded = !difficultyExpanded;
		difficultyContent.setVisible(difficultyExpanded && anyDifficultyRows());
		difficultyToggle.setIcon(difficultyExpanded
			? Carets.EXPANDED : Carets.COLLAPSED);
		revalidate();
		repaint();
	}

	private void toggleRolesSection()
	{
		rolesExpanded = !rolesExpanded;
		Activity activity = (Activity) activityDropdown.getSelectedItem();
		rolesSection.setVisible(rolesExpanded && activity != null && activity.hasRoles());
		rolesToggle.setIcon(rolesExpanded ? Carets.EXPANDED : Carets.COLLAPSED);
		revalidate();
		repaint();
	}

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

	/** Validate and capture the role composition; null (with a status message) when invalid. */
	private RoleSelection captureRoleSelection(Activity activity, int capacity)
	{
		if (!activity.hasRoles())
		{
			return new RoleSelection(null, null);
		}
		List<String> requiredRoles = captureRequiredRoles(activity, capacity);
		// Flexible activities (BA) advertise base roles, so don't demand assigned == capacity.
		if (!activity.hasFlexibleRoles() && requiredRoles.size() != capacity)
		{
			setError("Assign exactly " + capacity + " role slots (currently " + requiredRoles.size() + ").");
			return null;
		}
		Role mine = (Role) myRoleDropdown.getSelectedItem();
		String hostRole = mine != null ? mine.getId() : null;
		if (hostRole == null)
		{
			setError("Pick the role you'll fill.");
			return null;
		}
		if (!requiredRoles.contains(hostRole))
		{
			// The host's pick consumes a Fill/Any slot: swap one Fill for their actual role so the
			// advertised composition stays consistent with the role the host occupies.
			Role fill = activity.fillRole(hardModeCheck.isSelected());
			int fillIdx = fill == null ? -1 : requiredRoles.indexOf(fill.getId());
			if (fillIdx < 0)
			{
				setError("Add at least one " + mine.getDisplayName()
					+ " slot; that's the role you'll fill.");
				return null;
			}
			requiredRoles = new ArrayList<>(requiredRoles);
			requiredRoles.set(fillIdx, hostRole);
		}
		return new RoleSelection(requiredRoles, hostRole);
	}

	// ---- edit an existing hosted party ---------------------------------------

	/** Called by the owning panel to return to the Party tab after a successful save. */
	void setOnEditDone(Runnable onEditDone)
	{
		this.onEditDone = onEditDone;
	}

	/** Switch to edit mode, pre-filled from {@code ad}. The activity is locked (it keys the live room). */
	void enterEditMode(Advertisement ad)
	{
		if (ad == null)
		{
			return;
		}
		editing = true;
		joinExistingSection.setVisible(false); // editing an existing ad, not joining another
		applyPreset(adToPreset(ad));
		activityDropdown.setEnabled(false);
		createButton.setText("Save changes");
		createButton.setEnabled(true);
		setStatus("Editing your party. The activity can't be changed.");
	}

	/** The party those messages pointed at is gone, so drop them rather than leave a dead pointer to the Party tab. */
	void onPartyEnded()
	{
		setStatus("");
		updateLoginState();
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

	/** Map a hosted {@link Advertisement} onto an {@link AdvertisementPreset} so {@link #applyPreset} can fill the form. */
	private AdvertisementPreset adToPreset(Advertisement ad)
	{
		AdvertisementPreset preset = new AdvertisementPreset();
		preset.setActivityId(ad.getActivity());
		preset.setCapacity(ad.getCapacity());
		preset.setLootRule(ad.getLootRule());
		preset.setMinKc(ad.getMinKillCount());
		preset.setHardKc(ad.getMinHardModeKillCount());
		preset.setWorld(ad.getWorld());
		preset.setDescription(ad.getDescription());
		preset.setPrivateParty(ad.isPrivateAd());
		preset.setIronmanOnly(ad.isIronmanOnly());
		preset.setIncludeLayout(partyState.isAdvertiseLayout());
		preset.setHardMode(ad.isHardMode());
		preset.setInvocation(ad.getInvocation());
		preset.setCoxScale(ad.getCoxScale());
		preset.setLearner(ad.isLearner());
		preset.setTeacher(ad.isTeacher());
		preset.setRequiredRoles(ad.getRequiredRoles());
		preset.setHostRole(ad.getHostRole());
		return preset;
	}

	private void saveEdit()
	{
		Advertisement ad = partyState.getCurrentAd();
		if (ad == null || !partyState.isHost())
		{
			setError("You're not hosting a party to edit.");
			return;
		}

		Activity activity = (Activity) activityDropdown.getSelectedItem();
		if (activity == null)
		{
			return;
		}

		FormValues form = captureFormValues(activity);
		if (form == null)
		{
			return;
		}

		// Can't shrink the party below the people already in it (host + admitted members).
		int present = liveParty.isInParty()
			? (int) liveParty.roster().stream().filter(m -> m.getStatus() != PartyStatus.PENDING).count()
			: 1;
		if (form.capacity < present)
		{
			setError("Capacity can't be below the " + present + " already in the party.");
			return;
		}

		if (!hostMeetsOwnKc(activity, playerNameSupplier.get(), form.minKc, form.minHardKc, this::saveEdit))
		{
			return;
		}

		RoleSelection selection = captureRoleSelection(activity, form.capacity);
		if (selection == null)
		{
			return;
		}

		// Remember the new settings so a future create is pre-filled with them too.
		saveLastPreset(captureForm(null));

		AdvertisementEditRequest edit = new AdvertisementEditRequest(form.description, form.capacity, form.world,
			form.minKc, form.minHardKc, form.lootRule, form.privateParty, form.ironmanOnly, form.invocation,
			form.hardMode, form.coxScale, selection.requiredRoles, selection.hostRole, form.learner, form.teacher);

		createButton.setEnabled(false);
		setStatus("Saving changes…");
		boardService.editAd(ad.getId(), partyState.getHostKey(), edit,
			ignored -> SwingUtilities.invokeLater(() -> onEdited(ad, edit, form.advertiseLayout)),
			error -> SwingUtilities.invokeLater(() -> {
				createButton.setEnabled(true);
				setError("Edit failed: " + net.osparty.api.PartyErrors.friendly(error));
			}));
	}

	/** Apply the saved edit to our local party copy and the live room, then leave edit mode. */
	private void onEdited(Advertisement ad, AdvertisementEditRequest edit, boolean advertiseLayout)
	{
		createButton.setEnabled(true);

		// Reflect the edit locally so the Party tab updates at once (the server broadcast only refreshes search).
		ad.setDescription(edit.getDescription());
		ad.setCapacity(edit.getCapacity());
		ad.setWorld(edit.getWorld());
		ad.setMinKillCount(edit.getMinKillCount());
		ad.setMinHardModeKillCount(edit.getMinHardModeKillCount());
		ad.setLootRule(edit.getLootRule());
		ad.setPrivateAd(edit.isPrivateAd());
		ad.setIronmanOnly(edit.isIronmanOnly());
		ad.setInvocation(edit.getInvocation());
		ad.setHardMode(edit.isHardMode());
		ad.setCoxScale(edit.getCoxScale());
		ad.setRequiredRoles(edit.getRequiredRoles());
		ad.setHostRole(edit.getHostRole());
		ad.setLearner(edit.isLearner());
		ad.setTeacher(edit.isTeacher());

		partyState.setAdvertiseLayout(advertiseLayout);
		partyState.update(ad);

		// Sync the live room so admit limits, host role and learner/teacher markers follow the edit.
		liveParty.setCapacity(edit.getCapacity());
		liveParty.setLocalRole(edit.getHostRole());
		liveParty.setLocalLearner(edit.isLearner());
		liveParty.setLocalTeacher(edit.isTeacher());

		exitEditMode();
		setSuccess("Party updated.");
		if (onEditDone != null)
		{
			onEditDone.run();
		}
	}

	/** Neutral progress/confirmation text. */
	private void setStatus(String text)
	{
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setText(text);
	}

	/** Something the host has to act on — red rather than the same grey as everything else. */
	private void setError(String text)
	{
		statusLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		statusLabel.setText(text);
	}

	/** A finished action that worked. */
	private void setSuccess(String text)
	{
		statusLabel.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
		statusLabel.setText(text);
	}

	// ---- presets -------------------------------------------------------------

	private static final String PRESET_PLACEHOLDER = "Presets…";

	private JPanel buildPresets()
	{
		JPanel panel = PanelWidgets.cappedRow(new BorderLayout(4, 0));
		panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

		presetDropdown.addActionListener(e -> {
			if (rebuildingPresets)
			{
				return;
			}
			int idx = presetDropdown.getSelectedIndex();
			if (idx <= 0)
			{
				return; // placeholder row
			}
			List<AdvertisementPreset> presets = loadPresets();
			if (idx - 1 < presets.size())
			{
				applyPreset(presets.get(idx - 1));
				setStatus("Loaded preset \"" + presets.get(idx - 1).getName() + "\".");
			}
		});

		JButton save = miniButton(StatusIcons.PLUS, "Save the current settings as a preset");
		save.addActionListener(e -> saveCurrentAsPreset());

		JButton remove = miniButton(StatusIcons.CROSS, "Remove the selected preset");
		remove.addActionListener(e -> removeSelectedPreset());

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttons.add(save);
		buttons.add(remove);

		panel.add(presetDropdown, BorderLayout.CENTER);
		panel.add(buttons, BorderLayout.EAST);
		rebuildPresets();
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

	private void rebuildPresets()
	{
		rebuildingPresets = true;
		presetDropdown.removeAllItems();
		presetDropdown.addItem(PRESET_PLACEHOLDER);
		for (AdvertisementPreset preset : loadPresets())
		{
			presetDropdown.addItem(preset.getName());
		}
		presetDropdown.setSelectedIndex(0);
		rebuildingPresets = false;
	}

	private void saveCurrentAsPreset()
	{
		String name = JOptionPane.showInputDialog(this, "Preset name:", "Save preset",
			JOptionPane.PLAIN_MESSAGE);
		if (name == null)
		{
			return;
		}
		name = name.trim();
		if (name.isEmpty() || PRESET_PLACEHOLDER.equals(name))
		{
			setError("Enter a name for the preset.");
			return;
		}
		List<AdvertisementPreset> presets = loadPresets();
		String chosen = name;
		presets.removeIf(p -> chosen.equalsIgnoreCase(p.getName())); // overwrite a same-named one
		presets.add(captureForm(chosen));
		savePresets(presets);
		rebuildPresets();
		// Suppressed: selecting it would re-apply the preset we just captured and overwrite this status.
		rebuildingPresets = true;
		presetDropdown.setSelectedItem(chosen);
		rebuildingPresets = false;
		setSuccess("Saved preset \"" + chosen + "\".");
	}

	private void removeSelectedPreset()
	{
		int idx = presetDropdown.getSelectedIndex();
		if (idx <= 0)
		{
			setError("Select a preset to remove.");
			return;
		}
		List<AdvertisementPreset> presets = loadPresets();
		if (idx - 1 < presets.size())
		{
			String removed = presets.remove(idx - 1).getName();
			savePresets(presets);
			rebuildPresets();
			setSuccess("Removed preset \"" + removed + "\".");
		}
	}

	/** Snapshot the current form into a preset (raw description, no appended layout). */
	private AdvertisementPreset captureForm(String name)
	{
		AdvertisementPreset preset = new AdvertisementPreset();
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

	private void applyPreset(AdvertisementPreset preset)
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

		lootDropdown.setSelectedItem(LootRule.fromName(preset.getLootRule()));
		minKcSpinner.setValue(Math.max(0, preset.getMinKc()));
		hardKcSpinner.setValue(Math.max(0, preset.getHardKc()));
		descriptionArea.setText(preset.getDescription() != null ? preset.getDescription() : "");
		privateCheck.setSelected(preset.isPrivateParty());
		// Honour the ironman-only rule even if the saved preset had it ticked.
		ironmanCheck.setSelected(preset.isIronmanOnly() && AccountTypes.isIronman(accountTypeSupplier.get()));
		hardModeCheck.setSelected(preset.isHardMode());
		invocationSpinner.setValue(Math.max(0, Math.min(600, preset.getInvocation())));
		coxScaleField.setText(preset.getCoxScale() != null ? preset.getCoxScale() : "");
		learnerCheck.setSelected(preset.isLearner());
		teacherCheck.setSelected(preset.isTeacher());

		applyActivityBounds(); // capacity bounds, row visibility and the role controls

		SpinnerNumberModel model = (SpinnerNumberModel) capacitySpinner.getModel();
		int min = ((Number) model.getMinimum()).intValue();
		int max = ((Number) model.getMaximum()).intValue();
		int wanted = preset.getCapacity() <= 0 ? min : preset.getCapacity();
		model.setValue(Math.min(max, Math.max(min, wanted)));

		// After applyActivityBounds, whose CoX default would otherwise overwrite the saved choice.
		includeLayoutCheck.setSelected(preset.isIncludeLayout());
		applyRolePreset(preset);
	}

	/** Restore the saved role composition + host role into the (already-rebuilt) controls. */
	private void applyRolePreset(AdvertisementPreset preset)
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
			myRoleDropdown.setSelectedItem(nearestOffered(hostRole));
		}
	}

	/**
	 * The dropdown entry closest to {@code wanted}: itself when offered, else a role it
	 * can fill (a saved North freeze still lands on Freeze in a three-man ToB team).
	 */
	private Role nearestOffered(Role wanted)
	{
		for (int i = 0; i < myRoleDropdown.getItemCount(); i++)
		{
			if (myRoleDropdown.getItemAt(i) == wanted)
			{
				return wanted;
			}
		}
		for (int i = 0; i < myRoleDropdown.getItemCount(); i++)
		{
			Role offered = myRoleDropdown.getItemAt(i);
			if (wanted.canFill(offered.getId()))
			{
				return offered;
			}
		}
		return wanted;
	}

	private void saveLastPreset(AdvertisementPreset preset)
	{
		configManager.setConfiguration(OSPartyConfig.GROUP, KEY_LAST_PRESET, gson.toJson(preset));
	}

	private AdvertisementPreset loadLastPreset()
	{
		String json = configManager.getConfiguration(OSPartyConfig.GROUP, KEY_LAST_PRESET);
		if (json == null || json.isEmpty())
		{
			return null;
		}
		try
		{
			return gson.fromJson(json, AdvertisementPreset.class);
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	private List<AdvertisementPreset> loadPresets()
	{
		String json = configManager.getConfiguration(OSPartyConfig.GROUP, KEY_PRESETS);
		if (json == null || json.isEmpty())
		{
			return new ArrayList<>();
		}
		try
		{
			AdvertisementPreset[] presets = gson.fromJson(json, AdvertisementPreset[].class);
			return presets == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(presets));
		}
		catch (RuntimeException e)
		{
			return new ArrayList<>();
		}
	}

	private void savePresets(List<AdvertisementPreset> presets)
	{
		configManager.setConfiguration(OSPartyConfig.GROUP, KEY_PRESETS, gson.toJson(presets));
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
