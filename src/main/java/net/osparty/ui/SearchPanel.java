package net.osparty.ui;

import net.osparty.api.PartyService;
import net.osparty.model.AccountTypes;
import net.osparty.model.Activity;
import net.osparty.model.LootRule;
import net.osparty.model.Party;
import net.osparty.party.LiveParty;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
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

	/** Checkbox list of activities to include; all selected by default. */
	private final JPanel activityListPanel = new JPanel();
	private final Set<Activity> selectedActivities = EnumSet.allOf(Activity.class);
	/** The activity we're currently standing near (floated to the top, pre-checked). */
	private Activity recommended;
	/** Free-text filter over host name / description / activity. */
	private final JTextField textField = new JTextField();

	private final JComboBox<String> lootFilter = new JComboBox<>(new String[]{"Any loot", "FFA", "Split"});
	private final JCheckBox ironmanFilter = new JCheckBox("Ironman parties only");
	private final JTextField codeField = new JTextField();
	private final JButton searchButton = new JButton("Refresh");
	private final JLabel statusLabel = new JLabel();
	private final JPanel resultsPanel = new JPanel();

	/** Last raw search results, kept so the filters can re-apply without re-querying. */
	private List<Party> lastResults;
	/** Signature of the currently-rendered cards, to skip rebuilds when nothing changed. */
	private String renderedSignature;
	private Timer autoRefreshTimer;

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
		north.add(buildActivityFilter());
		north.add(buildTextFilter());
		north.add(buildControls());
		north.add(buildFilters());
		north.add(buildJoinByCode());
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

		// Header: "Activities" label on the left, All/None toggles on the right.
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel label = new JLabel("Activities");
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
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

	/** A free-text filter over host name, description and activity. */
	private JPanel buildTextFilter()
	{
		JPanel panel = cappedRow(new BorderLayout(0, 4));
		panel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		JLabel label = new JLabel("Search text");
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());

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

		panel.add(label, BorderLayout.NORTH);
		panel.add(textField, BorderLayout.CENTER);
		return panel;
	}

	/** Check or uncheck every activity at once. */
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
		reapplyFilters();
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
				reapplyFilters();
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
		if (lastResults != null)
		{
			showResults(lastResults);
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
				if (!text.isEmpty() && !matchesText(party, act, text))
				{
					continue;
				}
				visible.add(party);
			}
		}

		// Skip the rebuild (and its flicker) when nothing rendered would change.
		String signature = selectedSignature() + "|" + text + "|" + signatureOf(visible);
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

	/** True if the free-text query matches the host, description or activity name. */
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

		// Activity name first, since the list now mixes activities.
		JLabel activityLabel = new JLabel(activity != null ? activity.getDisplayName() : party.getActivity());
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
		if (party.getWorld() != null && !party.getWorld().isEmpty())
		{
			sub.append(", W").append(party.getWorld());
		}
		String age = formatAge(party.getCreatedAt());
		if (age != null)
		{
			sub.append(", searching ").append(age);
		}
		JLabel meta = new JLabel(sub.toString());
		meta.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		meta.setFont(FontManager.getRunescapeSmallFont());

		info.add(activityLabel);
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
}
