package net.osparty.ui;

import net.osparty.HostApplicationHandler;
import net.osparty.api.MockApplicants;
import net.osparty.api.PartyService;
import net.osparty.model.AccountTypes;
import net.osparty.model.Activity;
import net.osparty.model.Applicant;
import net.osparty.model.Applicant.EquipmentSlot;
import net.osparty.model.LootRule;
import net.osparty.model.Party;
import net.osparty.party.LiveParty;
import net.osparty.party.LiveParty.RosterMember;
import net.osparty.party.LiveParty.Status;
import net.osparty.party.PlayerUpdate;
import net.osparty.runewatch.RuneWatchCase;
import net.osparty.runewatch.RuneWatchService;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * "Current" tab: the live party the player is in. The roster, statuses and each
 * member's gear/inventory/stats come from the peer-to-peer party
 * ({@link LiveParty}); the API only advertised the room. Click a member to
 * inspect their Skills / Gear / Inventory. The host additionally sees pending
 * applicants with Admit / Decline and can Kick admitted members or Disband; a
 * member gets Leave.
 */
class CurrentPanel extends JPanel
{
	/** Inspection sub-tabs shown when a member is expanded. */
	private static final int TAB_SKILLS = 0;
	private static final int TAB_GEAR = 1;
	private static final int TAB_INVENTORY = 2;

	/** Size of an item icon tile in the gear/inventory views. */
	private static final Dimension SLOT_SIZE = new Dimension(36, 32);

	private final PartyService partyService;
	private final Supplier<String> playerNameSupplier;
	private final HostApplicationHandler hostApplicationHandler;
	private final PartyState partyState;
	private final ItemManager itemManager;
	private final LiveParty liveParty;
	private final RuneWatchService runeWatch;

	private final JPanel content = new JPanel();
	private final JLabel statusLabel = new JLabel();

	/** Member ids whose inspection view is currently expanded. */
	private final Set<Long> expanded = new HashSet<>();
	/** Selected inspection sub-tab per expanded member (defaults to Skills). */
	private final Map<Long, Integer> detailTab = new HashMap<>();
	/** Pending applicants the host has already been notified about (overlay/chat). */
	private final Set<Long> notifiedPending = new HashSet<>();

	CurrentPanel(PartyService partyService, Supplier<String> playerNameSupplier,
		HostApplicationHandler hostApplicationHandler, PartyState partyState, ItemManager itemManager,
		LiveParty liveParty, RuneWatchService runeWatch)
	{
		this.partyService = partyService;
		this.playerNameSupplier = playerNameSupplier;
		this.hostApplicationHandler = hostApplicationHandler;
		this.partyState = partyState;
		this.itemManager = itemManager;
		this.liveParty = liveParty;
		this.runeWatch = runeWatch;

		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrap.add(content, BorderLayout.NORTH);

		JScrollPane scroll = new JScrollPane(wrap,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);

		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		add(statusLabel, BorderLayout.SOUTH);

		partyState.addListener(this::refresh);
		// Live roster/data changes arrive off the EDT - marshal back before redraw.
		liveParty.addListener(() -> SwingUtilities.invokeLater(this::refresh));
		// Re-render once the RuneWatch watchlist has loaded so badges appear.
		runeWatch.addListener(() -> SwingUtilities.invokeLater(this::refresh));
		// Kicked, or the host closed the room: drop our local party state.
		liveParty.setOnEnded(() -> SwingUtilities.invokeLater(() -> {
			if (partyState.isInParty())
			{
				partyState.clear();
				setStatus("You are no longer in the party.");
			}
		}));

		// While we host an advertised party, ping the bulletin board so the backend
		// doesn't reap our ad as stale. No-op when we're not hosting.
		new Timer(30_000, e -> {
			if (partyState.isHost() && partyState.getCurrentParty() != null)
			{
				partyService.heartbeat(partyState.getCurrentParty().getId(), ok -> { }, err -> { });
			}
		}).start();

		refresh();
	}

	void refresh()
	{
		content.removeAll();

		Party party = partyState.getCurrentParty();
		if (party == null)
		{
			expanded.clear();
			detailTab.clear();
			notifiedPending.clear();
			content.revalidate();
			content.repaint();
			return;
		}

		boolean host = partyState.isHost();
		Activity activity = Activity.fromId(party.getActivity());
		String activityName = activity != null ? activity.getDisplayName() : party.getActivity();

		JLabel header = new JLabel(host
			? "Your " + activityName + " party"
			: party.getHost() + "'s " + activityName + " party");
		header.setForeground(ColorScheme.BRAND_ORANGE);
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(header);

		List<RosterMember> roster = liveParty.isConnected() ? liveParty.roster() : null;

		int admitted = roster == null ? 0
			: (int) roster.stream().filter(m -> m.getStatus() != Status.PENDING).count();
		StringBuilder spots = new StringBuilder();
		spots.append(party.getCapacity() > 0 ? admitted + "/" + party.getCapacity() + " players" : admitted + " players");
		if (party.getWorld() != null && !party.getWorld().isEmpty())
		{
			spots.append(", W").append(party.getWorld());
		}
		content.add(subLabel(spots.toString()));

		String req = requirementText(activity, party);
		if (req != null)
		{
			JLabel reqLabel = subLabel(req);
			reqLabel.setForeground(ColorScheme.PROGRESS_INPROGRESS_COLOR);
			content.add(reqLabel);
		}

		// Party type tags: loot rule, ironman-only, private.
		List<String> tags = new ArrayList<>();
		LootRule loot = LootRule.fromName(party.getLootRule());
		if (loot != LootRule.UNSPECIFIED)
		{
			tags.add("Loot: " + loot.getDisplayName());
		}
		if (party.isIronmanOnly())
		{
			tags.add("Ironman only");
		}
		if (party.isPrivateParty())
		{
			tags.add("Private");
		}
		if (!tags.isEmpty())
		{
			content.add(subLabel(String.join(", ", tags)));
		}

		// Host can share the invite code / passphrase to invite directly.
		if (host && party.getInviteCode() != null)
		{
			String inviteCode = party.getInviteCode();
			JLabel code = subLabel("Invite code: " + inviteCode + " (copy)");
			code.setForeground(ColorScheme.BRAND_ORANGE);
			code.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			code.setToolTipText("Click to copy");
			code.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					Toolkit.getDefaultToolkit().getSystemClipboard()
						.setContents(new StringSelection(inviteCode), null);
					setStatus("Invite code copied to clipboard.");
				}
			});
			content.add(code);
		}
		if (host && liveParty.passphrase() != null)
		{
			content.add(subLabel("Passphrase: " + liveParty.passphrase()));
		}

		content.add(Box.createVerticalStrut(6));

		if (roster == null || roster.isEmpty())
		{
			content.add(subLabel("Connecting to live room..."));
		}
		else
		{
			boolean anyPending = false;
			for (RosterMember member : roster)
			{
				if (member.getStatus() == Status.PENDING)
				{
					anyPending = true;
					continue;
				}
				content.add(buildMemberEntry(party, activity, member, host));
				content.add(Box.createVerticalStrut(4));
			}

			if (anyPending && host)
			{
				content.add(Box.createVerticalStrut(4));
				content.add(sectionLabel("Pending applicants"));
				for (RosterMember member : roster)
				{
					if (member.getStatus() == Status.PENDING)
					{
						content.add(buildMemberEntry(party, activity, member, true));
						content.add(Box.createVerticalStrut(4));
					}
				}
			}
			else if (!host && isLocalPending(roster))
			{
				content.add(subLabel("Awaiting host approval..."));
			}

			if (host && activity != null)
			{
				notifyHostOfPending(roster, activity);
			}
		}

		// Host test tool: inject a fake applicant so Admit/Decline can be exercised
		// without a second account. It never touches the network.
		if (host && liveParty.isConnected())
		{
			JButton simulate = smallButton("Simulate join request");
			simulate.addActionListener(e -> {
				liveParty.addSimulatedApplicant(fakeUpdate(activity));
				refresh();
			});
			content.add(Box.createVerticalStrut(6));
			content.add(leftRow(simulate));
		}

		content.add(Box.createVerticalStrut(8));
		content.add(buildActions(party, host));

		content.revalidate();
		content.repaint();
	}

	private boolean isLocalPending(List<RosterMember> roster)
	{
		for (RosterMember member : roster)
		{
			if (member.isLocal())
			{
				return member.getStatus() == Status.PENDING;
			}
		}
		return false;
	}

	/** Show the host an overlay + chatbox ping the first time each applicant appears. */
	private void notifyHostOfPending(List<RosterMember> roster, Activity activity)
	{
		for (RosterMember member : roster)
		{
			if (member.getStatus() == Status.PENDING && member.getData() != null
				&& notifiedPending.add(member.getMemberId()))
			{
				hostApplicationHandler.onApplicantShown(toApplicant(member.getData()), activity);
			}
		}
	}

	private JPanel buildMemberEntry(Party party, Activity activity, RosterMember member, boolean host)
	{
		Status status = member.getStatus();
		boolean isExpanded = expanded.contains(member.getMemberId());

		JPanel entry = cappedPanel(new BorderLayout(0, 4));
		entry.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		entry.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		entry.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel headerRow = new JPanel(new BorderLayout(6, 0));
		headerRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerRow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		headerRow.setToolTipText("Click to inspect gear & stats");

		String tag = status == Status.HOST ? " (host)" : status == Status.PENDING ? " (pending)" : "";
		String you = member.isLocal() ? " (you)" : "";
		String ironTag = ironTag(member);
		JLabel name = new JLabel(member.getName() + ironTag + tag + you);
		name.setForeground(status == Status.HOST ? ColorScheme.BRAND_ORANGE
			: status == Status.PENDING ? ColorScheme.PROGRESS_INPROGRESS_COLOR : Color.WHITE);
		headerRow.add(name, BorderLayout.CENTER);

		headerRow.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (isExpanded)
				{
					expanded.remove(member.getMemberId());
				}
				else
				{
					expanded.add(member.getMemberId());
				}
				refresh();
			}
		});

		// Name on top, optional warnings (RuneWatch / non-ironman) in the middle, and
		// the action buttons (Admit/Decline/Kick) on their own row below.
		JPanel top = new JPanel(new BorderLayout(0, 4));
		top.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		top.add(headerRow, BorderLayout.NORTH);

		List<JComponent> notes = new ArrayList<>();
		RuneWatchCase flagged = runeWatch.get(member.getName());
		if (flagged != null)
		{
			notes.add(runeWatchBadge(flagged));
		}
		if (party.isIronmanOnly() && status != Status.HOST && member.getData() != null
			&& !AccountTypes.isIronman(AccountTypes.fromName(member.getData().getAccountType())))
		{
			notes.add(warnBadge("Not an ironman"));
		}
		if (!notes.isEmpty())
		{
			JPanel notesPanel = new JPanel();
			notesPanel.setLayout(new BoxLayout(notesPanel, BoxLayout.Y_AXIS));
			notesPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			for (JComponent note : notes)
			{
				note.setAlignmentX(Component.LEFT_ALIGNMENT);
				notesPanel.add(note);
			}
			top.add(notesPanel, BorderLayout.CENTER);
		}

		JComponent actions = buildMemberActions(activity, member, host);
		if (actions != null)
		{
			top.add(actions, BorderLayout.SOUTH);
		}
		entry.add(top, BorderLayout.NORTH);

		if (isExpanded)
		{
			entry.add(buildDetail(activity, member), BorderLayout.CENTER);
		}

		return entry;
	}

	/** Host controls: Admit/Decline for pending applicants, Kick for admitted members. */
	private JComponent buildMemberActions(Activity activity, RosterMember member, boolean host)
	{
		if (!host || member.isLocal())
		{
			return null;
		}

		// Left-aligned and indented to sit under the name (past the caret).
		JPanel wrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		wrap.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		wrap.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 0));

		if (member.getStatus() == Status.PENDING)
		{
			JButton admit = smallButton("Admit");
			admit.addActionListener(e -> admit(activity, member));
			JButton decline = smallButton("Decline");
			decline.addActionListener(e -> decline(activity, member));
			wrap.add(admit);
			wrap.add(decline);
		}
		else if (member.getStatus() == Status.MEMBER)
		{
			JButton kick = smallButton("Kick");
			kick.addActionListener(e -> kick(activity, member));
			wrap.add(kick);
		}
		else
		{
			return null;
		}
		return wrap;
	}

	/**
	 * The expanded inspection view: a Skills / Gear / Inventory sub-tab strip plus
	 * the selected view's body, built from the member's live self-report.
	 */
	private JComponent buildDetail(Activity activity, RosterMember member)
	{
		PlayerUpdate data = member.getData();
		if (data == null)
		{
			JPanel waiting = new JPanel(new BorderLayout());
			waiting.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			waiting.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
			waiting.add(detailLeft("Waiting for live data..."), BorderLayout.CENTER);
			return waiting;
		}

		Applicant stats = toApplicant(data);
		int tab = detailTab.getOrDefault(member.getMemberId(), TAB_SKILLS);

		JPanel detail = new JPanel(new BorderLayout(0, 6));
		detail.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		detail.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		detail.add(buildDetailTabs(member.getMemberId(), tab), BorderLayout.NORTH);

		JComponent body;
		switch (tab)
		{
			case TAB_GEAR:
				body = buildEquipment(stats);
				break;
			case TAB_INVENTORY:
				body = buildInventory(stats);
				break;
			default:
				body = buildSkills(activity, stats);
		}
		detail.add(body, BorderLayout.CENTER);
		return detail;
	}

	private JPanel buildDetailTabs(long memberId, int selected)
	{
		JPanel tabs = new JPanel(new GridLayout(1, 3, 4, 0));
		tabs.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		tabs.add(detailTabButton("Skills", memberId, TAB_SKILLS, selected));
		tabs.add(detailTabButton("Gear", memberId, TAB_GEAR, selected));
		tabs.add(detailTabButton("Inv", memberId, TAB_INVENTORY, selected));
		return tabs;
	}

	private JButton detailTabButton(String text, long memberId, int tab, int selected)
	{
		JButton button = new JButton(text);
		button.setFocusPainted(false);
		button.setMargin(new Insets(2, 4, 2, 4));
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setForeground(tab == selected ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		button.addActionListener(e -> {
			detailTab.put(memberId, tab);
			refresh();
		});
		return button;
	}

	private JPanel buildSkills(Activity activity, Applicant stats)
	{
		JPanel detail = new JPanel(new GridLayout(0, 2, 6, 2));
		detail.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		detail.add(detailLeft("Combat"));
		detail.add(detailRight(String.valueOf(stats.getCombatLevel())));

		if (stats.getStats() != null)
		{
			for (Map.Entry<String, Integer> stat : stats.getStats().entrySet())
			{
				detail.add(detailLeft(stat.getKey()));
				detail.add(detailRight(String.valueOf(stat.getValue())));
			}
		}

		// Killcount is only shown for mock/explicit data; live reports carry -1.
		if (stats.getKillCount() >= 0)
		{
			String activityName = activity != null ? activity.getDisplayName() : "Activity";
			detail.add(detailLeft(activityName + " KC"));
			detail.add(detailRight(String.valueOf(stats.getKillCount())));

			if (activity != null && activity.hasHardMode() && stats.getHardModeKillCount() >= 0)
			{
				detail.add(detailLeft(activity.getHardModeLabel() + " KC"));
				detail.add(detailRight(String.valueOf(stats.getHardModeKillCount())));
			}
		}

		return detail;
	}

	/** Worn equipment laid out like the in-game equipment screen (3-wide). */
	private JPanel buildEquipment(Applicant stats)
	{
		int[] equip = stats.getEquipment();
		if (equip == null)
		{
			JPanel empty = new JPanel(new BorderLayout());
			empty.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			empty.add(detailLeft("No gear data."), BorderLayout.CENTER);
			return empty;
		}

		JPanel grid = new JPanel(new GridBagLayout());
		grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(1, 1, 1, 1);

		addEquipSlot(grid, c, equip, EquipmentSlot.HEAD, 1, 0);
		addEquipSlot(grid, c, equip, EquipmentSlot.CAPE, 0, 1);
		addEquipSlot(grid, c, equip, EquipmentSlot.AMULET, 1, 1);
		addEquipSlot(grid, c, equip, EquipmentSlot.AMMO, 2, 1);
		addEquipSlot(grid, c, equip, EquipmentSlot.WEAPON, 0, 2);
		addEquipSlot(grid, c, equip, EquipmentSlot.BODY, 1, 2);
		addEquipSlot(grid, c, equip, EquipmentSlot.SHIELD, 2, 2);
		addEquipSlot(grid, c, equip, EquipmentSlot.LEGS, 1, 3);
		addEquipSlot(grid, c, equip, EquipmentSlot.GLOVES, 0, 4);
		addEquipSlot(grid, c, equip, EquipmentSlot.BOOTS, 1, 4);
		addEquipSlot(grid, c, equip, EquipmentSlot.RING, 2, 4);

		return center(grid);
	}

	private void addEquipSlot(JPanel grid, GridBagConstraints c, int[] equip, EquipmentSlot slot, int x, int y)
	{
		c.gridx = x;
		c.gridy = y;
		grid.add(itemSlot(equip[slot.ordinal()]), c);
	}

	/** The 28-slot inventory grid (4 wide), in game order. */
	private JPanel buildInventory(Applicant stats)
	{
		int[] inv = stats.getInventory();
		if (inv == null)
		{
			JPanel empty = new JPanel(new BorderLayout());
			empty.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			empty.add(detailLeft("No inventory data."), BorderLayout.CENTER);
			return empty;
		}

		JPanel grid = new JPanel(new GridLayout(7, 4, 2, 2));
		grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		for (int i = 0; i < 28; i++)
		{
			grid.add(itemSlot(i < inv.length ? inv[i] : -1));
		}
		return center(grid);
	}

	/** A single fixed-size item tile; resolves a real icon when {@code itemId > 0}. */
	private JLabel itemSlot(int itemId)
	{
		JLabel slot = new JLabel();
		slot.setHorizontalAlignment(SwingConstants.CENTER);
		slot.setVerticalAlignment(SwingConstants.CENTER);
		slot.setPreferredSize(SLOT_SIZE);
		slot.setMinimumSize(SLOT_SIZE);
		slot.setOpaque(true);
		slot.setBackground(ColorScheme.DARK_GRAY_COLOR);
		if (itemId > 0)
		{
			// Loads asynchronously and repaints the label once the icon is ready.
			itemManager.getImage(itemId).addTo(slot);
		}
		return slot;
	}

	private JPanel center(JComponent inner)
	{
		JPanel wrap = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
		wrap.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		wrap.add(inner);
		return wrap;
	}

	private Applicant toApplicant(PlayerUpdate update)
	{
		Applicant applicant = new Applicant();
		applicant.setName(update.getName());
		applicant.setCombatLevel(update.getCombatLevel());
		applicant.setStats(update.getStats());
		applicant.setEquipment(update.getEquipment());
		applicant.setInventory(update.getInventory());
		applicant.setKillCount(update.getKillCount());
		applicant.setHardModeKillCount(update.getHardModeKillCount());
		return applicant;
	}

	/** Build a fake applicant's live report from the mock generator (host testing). */
	private PlayerUpdate fakeUpdate(Activity activity)
	{
		Applicant fake = MockApplicants.randomFor(activity != null ? activity : Activity.CHAMBERS_OF_XERIC);
		PlayerUpdate update = new PlayerUpdate();
		update.setName(fake.getName());
		update.setCombatLevel(fake.getCombatLevel());
		update.setStats(fake.getStats());
		update.setEquipment(fake.getEquipment());
		update.setInventory(fake.getInventory());
		update.setKillCount(fake.getKillCount());
		update.setHardModeKillCount(fake.getHardModeKillCount());
		return update;
	}

	// ---- host / member actions ----------------------------------------------

	private void admit(Activity activity, RosterMember member)
	{
		if (!liveParty.admit(member.getMemberId(), member.getName()))
		{
			setStatus("Party is full - can't admit " + member.getName() + ".");
			return;
		}
		notifiedPending.remove(member.getMemberId());
		if (activity != null && member.getData() != null)
		{
			hostApplicationHandler.onApplicantResolved(toApplicant(member.getData()), activity, true);
		}
		setStatus("Admitted " + member.getName() + ".");
		refresh();
	}

	private void decline(Activity activity, RosterMember member)
	{
		liveParty.reject(member.getMemberId());
		notifiedPending.remove(member.getMemberId());
		if (activity != null && member.getData() != null)
		{
			hostApplicationHandler.onApplicantResolved(toApplicant(member.getData()), activity, false);
		}
		setStatus("Declined " + member.getName() + ".");
		refresh();
	}

	private void kick(Activity activity, RosterMember member)
	{
		liveParty.kick(member.getMemberId());
		expanded.remove(member.getMemberId());
		detailTab.remove(member.getMemberId());
		setStatus("Kicked " + member.getName() + ".");
		refresh();
	}

	private JPanel buildActions(Party party, boolean host)
	{
		JPanel actions = cappedPanel(new BorderLayout());
		actions.setBackground(ColorScheme.DARK_GRAY_COLOR);
		actions.setAlignmentX(Component.LEFT_ALIGNMENT);

		JButton button = new JButton(host ? "Disband party" : "Leave party");
		button.setFocusPainted(false);
		button.addActionListener(e -> {
			if (host)
			{
				disband(party, button);
			}
			else
			{
				leave(button);
			}
		});
		actions.add(button, BorderLayout.CENTER);
		return actions;
	}

	private void disband(Party party, JButton button)
	{
		button.setEnabled(false);
		setStatus("Disbanding party...");
		// Remove the ad (fire-and-forget) and close the live room.
		partyService.disbandParty(party.getId(), party.getHost(), ignored -> { }, error -> { });
		liveParty.leave();
		partyState.clear();
	}

	private void leave(JButton button)
	{
		button.setEnabled(false);
		setStatus("Leaving party...");
		liveParty.leave();
		partyState.clear();
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

	private JButton smallButton(String text)
	{
		JButton button = new JButton(text);
		button.setFocusPainted(false);
		button.setMargin(new Insets(2, 6, 2, 6));
		button.setFont(FontManager.getRunescapeSmallFont());
		return button;
	}

	/** Account-type suffix for the roster name (e.g. " [HCIM]"), or "" for a main/unknown. */
	private String ironTag(RosterMember member)
	{
		if (member.getData() == null)
		{
			return "";
		}
		String tag = AccountTypes.tag(AccountTypes.fromName(member.getData().getAccountType()));
		return tag == null ? "" : " [" + tag + "]";
	}

	/** A generic red warning badge (e.g. for a non-ironman in an ironman-only party). */
	private JLabel warnBadge(String text)
	{
		JLabel label = new JLabel("(!) " + text);
		label.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setBorder(BorderFactory.createEmptyBorder(2, 16, 0, 0));
		return label;
	}

	/** A red RuneWatch / WDR warning shown under a flagged member's name. */
	private JLabel runeWatchBadge(RuneWatchCase flagged)
	{
		String reason = flagged.getReason() == null || flagged.getReason().isEmpty()
			? "listed" : flagged.getReason();
		// HTML width-caps the label so a long reason wraps instead of widening the card.
		JLabel label = new JLabel("<html><div style='width:150px'>(!) "
			+ flagged.sourceName() + ": " + escape(reason) + "</div></html>");
		label.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setBorder(BorderFactory.createEmptyBorder(2, 16, 0, 0));

		StringBuilder tip = new StringBuilder("<html>").append(flagged.sourceName()).append(" case");
		if (flagged.getCode() != null)
		{
			tip.append(' ').append(escape(flagged.getCode()));
		}
		if (flagged.getRating() != null)
		{
			tip.append(" - evidence rating ").append(escape(flagged.getRating()));
		}
		if (flagged.getDate() != null)
		{
			tip.append("<br>").append(escape(flagged.getDate()));
		}
		label.setToolTipText(tip.append("</html>").toString());
		return label;
	}

	private static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private JPanel leftRow(JComponent inner)
	{
		JPanel row = cappedPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.add(inner);
		return row;
	}

	private JLabel sectionLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(ColorScheme.BRAND_ORANGE);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private JLabel subLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private JLabel detailLeft(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		return label;
	}

	private JLabel detailRight(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(Color.WHITE);
		label.setFont(FontManager.getRunescapeSmallFont());
		return label;
	}

	private static JPanel cappedPanel(LayoutManager layout)
	{
		return new JPanel(layout)
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
	}

	private void setStatus(String text)
	{
		statusLabel.setText(text);
	}
}
