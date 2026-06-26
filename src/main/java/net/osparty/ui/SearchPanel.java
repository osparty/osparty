package net.osparty.ui;

import net.osparty.api.PartyService;
import net.osparty.model.AccountTypes;
import net.osparty.model.Activity;
import net.osparty.model.LootRule;
import net.osparty.model.Party;
import net.osparty.party.LiveParty;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import net.runelite.api.vars.AccountType;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * "Search" tab: pick an activity, query the queue API for open parties, and
 * apply to one. A player can be in only one party at a time - applying leaves
 * the current party first (disbanding it if you were the host). Full parties
 * and your own party aren't appliable.
 *
 * <p>While applied to a party, a banner shows whether you're in the host's
 * friends chat and on the host's world (both read-only checks).
 */
class SearchPanel extends JPanel
{
	/** How long a party stays un-appliable after the player cancels. */
	private static final long COOLDOWN_MS = 30_000;

	private final PartyService partyService;
	private final Supplier<String> playerNameSupplier;
	private final Supplier<String> friendsChatOwnerSupplier;
	private final IntSupplier worldSupplier;
	private final PartyState partyState;
	private final LiveParty liveParty;
	private final Supplier<AccountType> accountTypeSupplier;
	private final Supplier<int[]> mapRegionsSupplier;

	private final JComboBox<Activity> activityDropdown = new JComboBox<>(Activity.values());
	/** The activity we're currently standing near (suggested at the top of the list). */
	private Activity recommended;
	/** True while we're programmatically rebuilding the dropdown (suppress search). */
	private boolean rebuildingDropdown;
	private final JComboBox<String> lootFilter = new JComboBox<>(new String[]{"Any loot", "FFA", "Split"});
	private final JCheckBox ironmanFilter = new JCheckBox("Ironman parties only");
	private final JTextField codeField = new JTextField();
	private final JButton searchButton = new JButton("Refresh");
	private final JLabel statusLabel = new JLabel();
	private final JPanel resultsPanel = new JPanel();

	/** Last raw search results, kept so the filters can re-apply without re-querying. */
	private List<Party> lastResults;
	private Activity lastActivity;
	/** Signature of the currently-rendered cards, to skip rebuilds when nothing changed. */
	private String renderedSignature;
	private Timer autoRefreshTimer;

	// Application banner (visible only while applied to a party as a member).
	private final JPanel applicationPanel = new JPanel();
	private final JLabel applicationHostLabel = new JLabel();
	private final JLabel friendsChatLabel = new JLabel();
	private final JLabel worldLabel = new JLabel();

	/** Party id -> epoch millis when its cooldown expires. */
	private final Map<String, Long> cooldownExpiry = new HashMap<>();
	/** Apply buttons currently displayed, keyed by party id (rebuilt per search). */
	private final Map<String, JButton> applyButtons = new HashMap<>();
	/** Parties currently displayed, keyed by id (rebuilt per search). */
	private final Map<String, Party> partiesById = new HashMap<>();

	private Timer uiTimer;

	SearchPanel(PartyService partyService, Supplier<String> playerNameSupplier,
		Supplier<String> friendsChatOwnerSupplier, IntSupplier worldSupplier, PartyState partyState,
		LiveParty liveParty, Supplier<AccountType> accountTypeSupplier, Supplier<int[]> mapRegionsSupplier)
	{
		this.partyService = partyService;
		this.playerNameSupplier = playerNameSupplier;
		this.friendsChatOwnerSupplier = friendsChatOwnerSupplier;
		this.worldSupplier = worldSupplier;
		this.partyState = partyState;
		this.liveParty = liveParty;
		this.accountTypeSupplier = accountTypeSupplier;
		this.mapRegionsSupplier = mapRegionsSupplier;

		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel north = new JPanel();
		north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
		north.setBackground(ColorScheme.DARK_GRAY_COLOR);
		north.add(buildControls());
		north.add(buildFilters());
		north.add(buildJoinByCode());
		north.add(buildApplicationPanel());
		add(north, BorderLayout.NORTH);

		resultsPanel.setLayout(new BoxLayout(resultsPanel, BoxLayout.Y_AXIS));
		resultsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Wrap results so a long party list scrolls within the tab rather than
		// growing the whole side-panel.
		JPanel resultsWrap = new JPanel(new BorderLayout());
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

		// Mark the nearby activity with a "(nearby)" suffix in the dropdown.
		activityDropdown.setRenderer(new ActivityRenderer());

		searchButton.setToolTipText("Refresh the list for the selected activity");
		searchButton.addActionListener(e -> search());

		// Selecting a different activity searches it immediately (but not while we're
		// programmatically reordering the list to surface a nearby activity).
		activityDropdown.addActionListener(e -> {
			if (!rebuildingDropdown)
			{
				search();
			}
		});

		// Auto-refresh the selected activity every 10s while the tab is visible, and
		// re-check whether we've moved near a different activity.
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
			updateApplicationBanner();
			maybeStartTimer();
		});
		updateApplicationBanner();
	}

	private JPanel buildControls()
	{
		JPanel controls = new JPanel(new BorderLayout(0, 6))
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
		// Match the other north children's alignment — a mixed BoxLayout alignmentX
		// pushes the LEFT-aligned panels (filters, join row) to the right.
		controls.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel label = new JLabel("Activity");
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		controls.add(label, BorderLayout.NORTH);

		controls.add(activityDropdown, BorderLayout.CENTER);

		searchButton.setFocusPainted(false);
		controls.add(searchButton, BorderLayout.SOUTH);

		return controls;
	}

	private JPanel buildFilters()
	{
		lootFilter.addActionListener(e -> reapplyFilters());

		ironmanFilter.setBackground(ColorScheme.DARK_GRAY_COLOR);
		ironmanFilter.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		ironmanFilter.setFocusPainted(false);
		ironmanFilter.addActionListener(e -> reapplyFilters());

		// GridBagLayout centres each control within a full-width cell (weightx=1 +
		// anchor CENTER) - robust regardless of the surrounding BoxLayout.
		JPanel panel = cappedRow(new GridBagLayout());
		panel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.weightx = 1.0;
		c.anchor = GridBagConstraints.CENTER;
		c.gridy = 0;
		c.insets = new Insets(0, 0, 4, 0);
		panel.add(lootFilter, c);
		c.gridy = 1;
		c.insets = new Insets(0, 0, 0, 0);
		panel.add(ironmanFilter, c);
		return panel;
	}

	private JPanel buildJoinByCode()
	{
		JPanel panel = cappedRow(new BorderLayout(6, 4));
		panel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		JLabel label = new JLabel("Join private party by code");
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());

		JButton join = new JButton("Join");
		join.setFocusPainted(false);
		join.addActionListener(e -> joinByCode());
		codeField.addActionListener(e -> joinByCode());

		panel.add(label, BorderLayout.NORTH);
		panel.add(codeField, BorderLayout.CENTER);
		panel.add(join, BorderLayout.EAST);
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
		if (lastResults != null && lastActivity != null)
		{
			showResults(lastActivity, lastResults);
		}
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
		codeField.setText("");
		leaveCurrentThen(() -> doApply(party));
	}

	/** Whether the local account satisfies a party's ironman-only requirement. */
	private boolean meetsIronmanRule(Party party)
	{
		return !party.isIronmanOnly() || AccountTypes.isIronman(accountTypeSupplier.get());
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

	private JPanel buildApplicationPanel()
	{
		applicationPanel.setLayout(new GridLayout(0, 1));
		applicationPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		applicationPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		applicationPanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createEmptyBorder(8, 0, 0, 0),
			BorderFactory.createEmptyBorder(8, 8, 8, 8)));
		applicationPanel.setVisible(false);

		applicationHostLabel.setForeground(ColorScheme.BRAND_ORANGE);

		friendsChatLabel.setFont(FontManager.getRunescapeSmallFont());
		worldLabel.setFont(FontManager.getRunescapeSmallFont());

		applicationPanel.add(applicationHostLabel);
		applicationPanel.add(friendsChatLabel);
		applicationPanel.add(worldLabel);
		return applicationPanel;
	}

	/**
	 * If the player is standing near an activity, float it to the top of the
	 * dropdown and select it. No-op when the nearby activity hasn't changed, so it
	 * doesn't fight the user's manual selection.
	 */
	private void applyRecommendation()
	{
		Activity near = Activity.nearby(mapRegionsSupplier.get());
		if (near == recommended)
		{
			return;
		}
		recommended = near;
		rebuildDropdown(near);
	}

	/** Reorder the dropdown so {@code near} (if any) is first; keep the rest in order. */
	private void rebuildDropdown(Activity near)
	{
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
		// Arriving at an activity selects it; otherwise keep the prior choice.
		Activity select = near != null ? near : current;
		if (select != null)
		{
			activityDropdown.setSelectedItem(select);
		}
		rebuildingDropdown = false;

		search();
	}

	private void search()
	{
		String player = playerNameSupplier.get();
		Activity activity = (Activity) activityDropdown.getSelectedItem();
		if (activity == null)
		{
			return;
		}

		// No pre-clear: results stay put until the new ones arrive, so the periodic
		// auto-refresh doesn't flicker.
		partyService.searchParties(activity, player,
			parties -> SwingUtilities.invokeLater(() -> showResults(activity, parties)),
			error -> SwingUtilities.invokeLater(() -> setStatus("Refresh failed: " + error.getMessage())));
	}

	private void showResults(Activity activity, List<Party> parties)
	{
		lastActivity = activity;
		lastResults = parties;

		LootRule wantLoot = lootFilterValue();
		boolean ironOnly = ironmanFilter.isSelected();

		// Show only joinable, filter-matching parties (full ones are hidden).
		List<Party> visible = new ArrayList<>();
		if (parties != null)
		{
			for (Party party : parties)
			{
				if (party.isFull())
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
				visible.add(party);
			}
		}

		// Skip the rebuild (and its flicker) when the visible set is unchanged.
		String signature = activity.getId() + "|" + signatureOf(visible);
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
			setStatus("No open " + activity.getDisplayName() + " parties.");
		}
		else
		{
			setStatus(visible.size() + " open " + (visible.size() == 1 ? "party" : "parties") + ".");
			for (Party party : visible)
			{
				partiesById.put(party.getId(), party);
				resultsPanel.add(buildPartyCard(activity, party));
				resultsPanel.add(Box.createVerticalStrut(6));
			}
			updateAllButtons();
		}

		resultsPanel.revalidate();
		resultsPanel.repaint();
	}

	/** A stable signature of the visible parties so unchanged refreshes can no-op. */
	private static String signatureOf(List<Party> parties)
	{
		StringBuilder sb = new StringBuilder();
		for (Party party : parties)
		{
			sb.append(party.getId()).append(':').append(party.getSize())
				.append('/').append(party.getCapacity()).append(';');
		}
		return sb.toString();
	}

	private JPanel buildPartyCard(Activity activity, Party party)
	{
		// Cap height dynamically: a fixed maximum computed before the children
		// are added collapses the card under BoxLayout and hides its text.
		JPanel card = new JPanel(new BorderLayout(6, 0))
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

		JLabel host = new JLabel(party.getHost() == null ? "Unknown host" : party.getHost());
		host.setForeground(ColorScheme.BRAND_ORANGE);
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
		if (party.getWorld() != null && !party.getWorld().isEmpty())
		{
			sub.append(", W").append(party.getWorld());
		}
		JLabel meta = new JLabel(sub.toString());
		meta.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		meta.setFont(FontManager.getRunescapeSmallFont());

		info.add(host);
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

		if (party.getDescription() != null && !party.getDescription().isEmpty())
		{
			JLabel desc = new JLabel(party.getDescription());
			desc.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			desc.setFont(FontManager.getRunescapeSmallFont());
			info.add(desc);
		}

		card.add(info, BorderLayout.CENTER);

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
		card.add(buttonWrap, BorderLayout.EAST);

		return card;
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
		if (activity.hasHardMode() && party.getMinHardModeKillCount() > 0)
		{
			if (any)
			{
				req.append(", ");
			}
			req.append(party.getMinHardModeKillCount()).append(' ').append(activity.getHardModeLabel()).append(" KC");
		}
		return req.toString();
	}

	/** True if this is the party we're currently in as a member. */
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
		if (cooldownRemainingSeconds(party.getId()) > 0)
		{
			setStatus("On cooldown for this party.");
			return;
		}

		JButton button = applyButtons.get(party.getId());
		if (button != null)
		{
			button.setEnabled(false);
			button.setText("Applying...");
		}

		// Leave whatever party we're currently in first (one party at a time).
		leaveCurrentThen(() -> doApply(party));
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
			partyService.disbandParty(current.getId(), playerNameSupplier.get(),
				p -> { }, e -> { });
		}
		liveParty.leave();
		partyState.clear();
		next.run();
	}

	private void doApply(Party party)
	{
		String passphrase = party.getPassphrase();
		if (passphrase == null || passphrase.isEmpty())
		{
			setStatus("This party has no live room to join.");
			updateAllButtons();
			return;
		}

		liveParty.joinParty(passphrase, party.getActivity(), party.getCapacity());
		partyState.setMember(party);
		setStatus("Joined " + party.getHost() + "'s room - awaiting host approval.");
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
	}

	private void updateApplyButton(JButton button, Party party)
	{
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
		button.setText("Apply");
		button.setEnabled(true);
		button.setToolTipText(partyState.isInParty() ? "Applying will leave your current party" : null);
	}

	/**
	 * Refresh the "in friends chat / same world as host" banner from the
	 * (read-only) live client state. Shown only while applied to a party.
	 */
	private void updateApplicationBanner()
	{
		if (!isMemberInParty())
		{
			applicationPanel.setVisible(false);
			return;
		}

		Party party = partyState.getCurrentParty();
		String hostName = party.getHost();
		applicationHostLabel.setText("Applied to " + hostName);

		String fcOwner = friendsChatOwnerSupplier.get();
		boolean inFc = fcOwner != null && !fcOwner.isEmpty()
			&& normalize(fcOwner).equalsIgnoreCase(normalize(hostName));
		friendsChatLabel.setText(hostName + "'s friends chat: " + (inFc ? "joined" : "not joined"));
		friendsChatLabel.setForeground(inFc
			? ColorScheme.PROGRESS_COMPLETE_COLOR
			: ColorScheme.PROGRESS_ERROR_COLOR);

		int myWorld = worldSupplier.getAsInt();
		int hostWorld = parseWorld(party.getWorld());
		if (hostWorld <= 0)
		{
			worldLabel.setText("Host world unknown (you are on W" + myWorld + ")");
			worldLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}
		else
		{
			boolean sameWorld = myWorld == hostWorld;
			worldLabel.setText("World: W" + myWorld + (sameWorld
				? " (same as host)"
				: " (host on W" + hostWorld + ")"));
			worldLabel.setForeground(sameWorld
				? ColorScheme.PROGRESS_COMPLETE_COLOR
				: ColorScheme.PROGRESS_ERROR_COLOR);
		}

		applicationPanel.setVisible(true);
	}

	private boolean isMemberInParty()
	{
		return partyState.isInParty() && !partyState.isHost();
	}

	private static int parseWorld(String world)
	{
		if (world == null || world.isEmpty())
		{
			return 0;
		}
		try
		{
			return Integer.parseInt(world.trim());
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
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
				updateApplicationBanner();
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

	/** Dropdown renderer that appends "(nearby)" to the recommended activity. */
	private class ActivityRenderer extends DefaultListCellRenderer
	{
		@Override
		public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
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
