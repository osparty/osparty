package net.osparty.ui;

import net.osparty.OSPartyConfig;
import net.osparty.api.PartyService;
import net.osparty.model.AccountTypes;
import net.osparty.model.Activity;
import net.osparty.model.LootRule;
import net.osparty.model.Party;
import net.osparty.model.PartyRequest;
import net.osparty.party.LiveParty;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import net.runelite.api.vars.AccountType;
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
	private final PartyService partyService;
	private final Supplier<String> playerNameSupplier;
	private final PartyState partyState;
	private final LiveParty liveParty;
	private final Supplier<AccountType> accountTypeSupplier;

	private final JComboBox<Activity> activityDropdown = new JComboBox<>(Activity.values());
	private final JComboBox<LootRule> lootDropdown = new JComboBox<>(LootRule.values());
	private final JSpinner capacitySpinner;
	private final JTextField worldField = new JTextField();
	private final JTextArea descriptionArea = new JTextArea(3, 0);
	private final JCheckBox privateCheck = new JCheckBox("Private (join by code only)");
	private final JCheckBox ironmanCheck = new JCheckBox("Ironman only");
	private final JButton createButton = new JButton("Create party");
	private final JLabel statusLabel = new JLabel();

	private final JSpinner minKcSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 100_000, 10));
	private final JLabel hardKcLabel = new JLabel("Minimum CM KC");
	private final JSpinner hardKcSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 100_000, 10));
	private JPanel hardKcField;

	CreatePanel(PartyService partyService, OSPartyConfig config, Supplier<String> playerNameSupplier,
		PartyState partyState, LiveParty liveParty, Supplier<AccountType> accountTypeSupplier)
	{
		this.partyService = partyService;
		this.playerNameSupplier = playerNameSupplier;
		this.partyState = partyState;
		this.liveParty = liveParty;
		this.accountTypeSupplier = accountTypeSupplier;

		int defaultCapacity = Math.max(1, config.defaultCapacity());
		this.capacitySpinner = new JSpinner(new SpinnerNumberModel(defaultCapacity, 1, 100, 1));

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

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

		activityDropdown.addActionListener(e -> applyActivityBounds());
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
		revalidate();
		repaint();
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

		createButton.setEnabled(false);
		setStatus("Creating party...");

		// The passphrase must be generated on the client thread (RuneLite reads item
		// names to build it), so this is async; advertise once we have it.
		liveParty.generatePassphrase(passphrase -> {
			PartyRequest request = new PartyRequest(
				activityId, player, description, capacity, world, minKc, minHardKc, passphrase,
				privateParty, lootRule, ironmanOnly, hostAccountType);

			partyService.createParty(request,
				party -> SwingUtilities.invokeLater(() -> onCreated(party, passphrase, player, capacity)),
				error -> SwingUtilities.invokeLater(() -> {
					createButton.setEnabled(true);
					setStatus("Create failed: " + error.getMessage());
				}));
		});
	}

	private void onCreated(Party party, String passphrase, String host, int capacity)
	{
		createButton.setEnabled(true);
		descriptionArea.setText("");
		// Host the live room now that the ad is up; applicants who join are pending
		// until admitted from the Current tab.
		liveParty.hostParty(passphrase, host, capacity, false);
		if (party.isPrivateParty() && party.getInviteCode() != null)
		{
			setStatus("Private party created - invite code " + party.getInviteCode() + " (also on Current tab).");
		}
		else
		{
			setStatus("Party created - manage it on the Current tab.");
		}
		partyState.setHosting(party);
	}

	private void setStatus(String text)
	{
		statusLabel.setText(text);
	}
}
