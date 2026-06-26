package net.osparty.ui;

import net.osparty.HostApplicationHandler;
import net.osparty.KillcountService;
import net.osparty.PersonalBests;
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
import java.awt.image.BufferedImage;
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
import net.runelite.api.vars.AccountType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageUtil;

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
	private final KillcountService killcounts;
	private final SkillIconManager skillIcons;
	private final IntSupplier currentWorld;
	private final IntConsumer worldHopper;
	private final Supplier<String> friendsChatOwnerSupplier;

	/** Skills in the in-game skills-tab layout (row-major, 3 columns), total last. */
	private static final Skill[] SKILL_LAYOUT = {
		Skill.ATTACK, Skill.HITPOINTS, Skill.MINING,
		Skill.STRENGTH, Skill.AGILITY, Skill.SMITHING,
		Skill.DEFENCE, Skill.HERBLORE, Skill.FISHING,
		Skill.RANGED, Skill.THIEVING, Skill.COOKING,
		Skill.PRAYER, Skill.CRAFTING, Skill.FIREMAKING,
		Skill.MAGIC, Skill.FLETCHING, Skill.WOODCUTTING,
		Skill.RUNECRAFT, Skill.SLAYER, Skill.FARMING,
		Skill.CONSTRUCTION, Skill.HUNTER, Skill.SAILING,
	};

	private static final ImageIcon TOTAL_ICON = loadTotalIcon();

	private static ImageIcon loadTotalIcon()
	{
		try
		{
			BufferedImage img = ImageUtil.loadImageResource(CurrentPanel.class, "/net/osparty/icons/total.png");
			return new ImageIcon(img.getScaledInstance(18, 16, java.awt.Image.SCALE_SMOOTH));
		}
		catch (Exception e)
		{
			return null;
		}
	}

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
		LiveParty liveParty, RuneWatchService runeWatch, KillcountService killcounts,
		SkillIconManager skillIcons, IntSupplier currentWorld, IntConsumer worldHopper,
		Supplier<String> friendsChatOwnerSupplier)
	{
		this.partyService = partyService;
		this.playerNameSupplier = playerNameSupplier;
		this.hostApplicationHandler = hostApplicationHandler;
		this.partyState = partyState;
		this.itemManager = itemManager;
		this.liveParty = liveParty;
		this.runeWatch = runeWatch;
		this.killcounts = killcounts;
		this.skillIcons = skillIcons;
		this.currentWorld = currentWorld;
		this.worldHopper = worldHopper;
		this.friendsChatOwnerSupplier = friendsChatOwnerSupplier;

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
			hostApplicationHandler.setPendingApplicants(java.util.Collections.emptyList(), null);
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
			// "In the friends chat" means the host's own friends chat (owned by the
			// host), identified by the host's name — not just any chat they're in.
			String hostName = party.getHost();
			boolean anyPending = false;
			for (RosterMember member : roster)
			{
				// Real applicants (someone other than you who has actually synced)
				// go in their own section below; a data-less ghost is ignored.
				if (member.getStatus() == Status.PENDING && !member.isLocal())
				{
					if (member.getData() != null)
					{
						anyPending = true;
					}
					continue;
				}
				content.add(buildMemberEntry(party, activity, member, host, hostName));
				content.add(Box.createVerticalStrut(4));
			}

			if (!host && isLocalPending(roster))
			{
				content.add(subLabel("Awaiting host approval..."));
			}

			if (anyPending && host)
			{
				content.add(Box.createVerticalStrut(4));
				content.add(sectionLabel("Pending applicants"));
				for (RosterMember member : roster)
				{
					if (member.getStatus() == Status.PENDING && !member.isLocal() && member.getData() != null)
					{
						content.add(buildMemberEntry(party, activity, member, true, hostName));
						content.add(Box.createVerticalStrut(4));
					}
				}
			}
		}

		// Keep the in-game applicant overlay in sync with the pending list.
		if (host && roster != null)
		{
			updatePendingApplicants(roster, activity);
		}
		else
		{
			hostApplicationHandler.setPendingApplicants(java.util.Collections.emptyList(), null);
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

	/**
	 * Push the full pending-applicant list to the in-game overlay (with killcounts
	 * filled in), and chat-ping each one the first time it appears.
	 */
	private void updatePendingApplicants(List<RosterMember> roster, Activity activity)
	{
		List<Applicant> pending = new ArrayList<>();
		for (RosterMember member : roster)
		{
			if (member.getStatus() != Status.PENDING || member.getData() == null)
			{
				continue;
			}
			Applicant applicant = toApplicant(member.getData());
			fillKillcount(applicant, activity);
			pending.add(applicant);

			if (notifiedPending.add(member.getMemberId()))
			{
				hostApplicationHandler.announceApplicant(applicant, activity);
			}
		}
		hostApplicationHandler.setPendingApplicants(pending, activity);
	}

	/** Fill an applicant's killcount from the hiscores cache (looking up if needed). */
	private void fillKillcount(Applicant applicant, Activity activity)
	{
		if (applicant.getKillCount() >= 0 || activity == null || applicant.getName() == null)
		{
			return; // already known (e.g. mock applicant) or no name/activity
		}
		KillcountService.Killcount cached = killcounts.cached(applicant.getName(), activity);
		if (cached != null)
		{
			applicant.setKillCount(cached.killCount);
			applicant.setHardModeKillCount(cached.hardModeKillCount);
		}
		else
		{
			killcounts.lookup(applicant.getName(), activity, this::refresh);
		}
	}

	private JPanel buildMemberEntry(Party party, Activity activity, RosterMember member, boolean host,
		String hostName)
	{
		Status status = member.getStatus();
		boolean isExpanded = expanded.contains(member.getMemberId());

		JPanel entry = cappedPanel(new BorderLayout(0, 4));
		entry.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		entry.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		entry.setAlignmentX(Component.LEFT_ALIGNMENT);

		// A vertical stack of lines:
		//   1. name
		//   2. world  friends-chat indicator
		//   3. action buttons (Admit/Decline/Kick, Hop to, Request FC)
		//   4. expand/collapse chevron
		JPanel stack = new JPanel();
		stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
		stack.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Line 1: a presence dot, then the name (click either - or the chevron - to
		// inspect gear & stats).
		boolean online = member.isOnline();
		JLabel dot = new JLabel(online ? StatusIcons.ONLINE : StatusIcons.OFFLINE);
		dot.setToolTipText(online ? "Online" : "Offline");
		dot.addMouseListener(expandOnClick(member));

		String tag = status == Status.HOST ? " (host)" : status == Status.PENDING ? " (pending)" : "";
		String you = member.isLocal() ? " (you)" : "";
		JLabel name = new JLabel(member.getName() + tag + you);
		name.setForeground(status == Status.HOST ? ColorScheme.BRAND_ORANGE
			: status == Status.PENDING ? ColorScheme.PROGRESS_INPROGRESS_COLOR : Color.WHITE);
		applyAccountIcon(name, member.getData());
		name.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		name.setToolTipText("Click to inspect gear & stats");
		name.addMouseListener(expandOnClick(member));

		JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		nameRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		nameRow.add(dot);
		nameRow.add(name);
		addLine(stack, nameRow, 0);

		// Line 2: world + friends-chat indicator.
		JComponent meta = buildMemberMeta(member, hostName);
		if (meta != null)
		{
			addLine(stack, meta, 16);
		}

		// Warnings (RuneWatch / non-ironman) - each already self-indents.
		RuneWatchCase flagged = runeWatch.get(member.getName());
		if (flagged != null)
		{
			addLine(stack, runeWatchBadge(flagged), 0);
		}
		if (party.isIronmanOnly() && status != Status.HOST && member.getData() != null
			&& !AccountTypes.isIronman(AccountTypes.fromName(member.getData().getAccountType())))
		{
			addLine(stack, warnBadge("Not an ironman"), 0);
		}

		// Line 3: action buttons.
		JComponent actions = buildActionsRow(activity, member, host, hostName);
		if (actions != null)
		{
			addLine(stack, actions, 16);
		}

		// Line 4: expand/collapse chevron (mirrors clicking the name).
		JLabel chevron = new JLabel(isExpanded ? StatusIcons.CHEVRON_UP : StatusIcons.CHEVRON_DOWN);
		chevron.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		chevron.setToolTipText(isExpanded ? "Hide gear & stats" : "Show gear & stats");
		chevron.addMouseListener(expandOnClick(member));
		addLine(stack, chevron, 16);

		entry.add(stack, BorderLayout.NORTH);

		if (isExpanded)
		{
			entry.add(buildDetail(activity, member), BorderLayout.CENTER);
		}

		return entry;
	}

	/** Add one left-aligned, height-capped line to a vertical BoxLayout stack. */
	private void addLine(JPanel stack, JComponent content, int indent)
	{
		JPanel row = cappedPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setBorder(BorderFactory.createEmptyBorder(0, indent, 2, 0));
		row.add(content);
		stack.add(row);
	}

	/** A click handler that toggles a member's expanded inspection view. */
	private MouseAdapter expandOnClick(RosterMember member)
	{
		return new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				long id = member.getMemberId();
				if (!expanded.remove(id))
				{
					expanded.add(id);
				}
				refresh();
			}
		};
	}

	/** Host/member action buttons: Admit/Decline/Kick, Hop to, and Request FC. */
	private JComponent buildActionsRow(Activity activity, RosterMember member, boolean host, String hostName)
	{
		if (member.isLocal())
		{
			return null; // no actions on yourself
		}

		JPanel wrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		wrap.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		boolean any = false;

		// Host membership controls.
		if (host && member.getStatus() == Status.PENDING)
		{
			JButton admit = smallButton("Admit");
			admit.addActionListener(e -> admit(activity, member));
			JButton decline = smallButton("Decline");
			decline.addActionListener(e -> decline(activity, member));
			wrap.add(admit);
			wrap.add(decline);
			any = true;
		}
		else if (host && member.getStatus() == Status.MEMBER)
		{
			JButton kick = smallButton("Kick");
			kick.addActionListener(e -> kick(activity, member));
			wrap.add(kick);
			any = true;
		}

		// Hop to the member's world (any viewer) when it differs from ours.
		PlayerUpdate data = member.getData();
		int world = data != null ? data.getWorld() : 0;
		int mine = currentWorld.getAsInt();
		if (world > 0 && mine > 0 && mine != world)
		{
			JButton hop = smallButton("Hop to");
			hop.setToolTipText("Hop to world " + world);
			hop.addActionListener(e -> {
				worldHopper.accept(world);
				setStatus("Hopping to world " + world + "...");
			});
			wrap.add(hop);
			any = true;
		}

		// Host can ask a member who isn't in the host's own friends chat to join it,
		// but only when the host actually has their own chat open.
		if (host && data != null && hostName != null
			&& !sameRsn(data.getFriendsChatOwner(), hostName)
			&& sameRsn(friendsChatOwnerSupplier.get(), hostName))
		{
			JButton request = smallButton("Request FC");
			request.setToolTipText("Ask " + member.getName() + " to join your friends chat");
			request.addActionListener(e -> requestFc(member, hostName));
			wrap.add(request);
			any = true;
		}

		return any ? wrap : null;
	}

	/**
	 * The world / friends-chat indicator row: the member's world, and a check/cross
	 * showing whether they're in the <b>host's own</b> friends chat (the chat owned
	 * by the host, matched on the host's name). Buttons live on the actions row.
	 * @return the row, or {@code null} when there's nothing to show.
	 */
	private JComponent buildMemberMeta(RosterMember member, String hostName)
	{
		PlayerUpdate data = member.getData();
		int world = data != null ? data.getWorld() : 0;
		boolean showFc = hostName != null && data != null;
		boolean inFc = showFc && sameRsn(data.getFriendsChatOwner(), hostName);

		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		boolean any = false;

		if (world > 0)
		{
			JLabel worldLabel = new JLabel("W" + world);
			worldLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			worldLabel.setFont(FontManager.getRunescapeSmallFont());
			row.add(worldLabel);
			any = true;
		}

		if (showFc)
		{
			// The friends-chat channel logo, then a check/cross for membership.
			if (StatusIcons.FRIENDS_CHAT != null)
			{
				JLabel fcLogo = new JLabel(StatusIcons.FRIENDS_CHAT);
				fcLogo.setToolTipText(hostName + "'s friends chat");
				row.add(fcLogo);
			}
			JLabel fcIcon = new JLabel(inFc ? StatusIcons.CHECK : StatusIcons.CROSS);
			fcIcon.setToolTipText(inFc
				? "In " + hostName + "'s friends chat"
				: "Not in " + hostName + "'s friends chat");
			row.add(fcIcon);
			any = true;
		}

		return any ? row : null;
	}

	/** True if two RuneScape names refer to the same account (case- and space-insensitive). */
	private static boolean sameRsn(String a, String b)
	{
		return a != null && b != null && norm(a).equalsIgnoreCase(norm(b));
	}

	/** Normalise an RSN for comparison: non-breaking spaces to spaces, trimmed. */
	private static String norm(String name)
	{
		return name.replace(' ', ' ').trim();
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
		JPanel panel = new JPanel(new BorderLayout(0, 6));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// The in-game skills grid (3 columns), each cell a skill icon + level.
		JPanel grid = new JPanel(new GridLayout(0, 3, 1, 1));
		grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		Map<String, Integer> levels = stats.getStats();
		int total = 0;
		for (Skill skill : SKILL_LAYOUT)
		{
			int level = levelOf(levels, skill);
			total += level;
			grid.add(skillCell(skill, level));
		}
		panel.add(grid, BorderLayout.NORTH);

		// Total level (with icon) below the grid, then combat + KC.
		JPanel bottom = new JPanel(new BorderLayout(0, 4));
		bottom.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		bottom.add(totalRow(total), BorderLayout.NORTH);
		bottom.add(buildCombatAndKc(activity, stats), BorderLayout.CENTER);
		panel.add(bottom, BorderLayout.CENTER);
		return panel;
	}

	private static int levelOf(Map<String, Integer> levels, Skill skill)
	{
		if (levels == null)
		{
			return 1;
		}
		Integer level = levels.get(skill.getName());
		return level != null ? level : 1;
	}

	private JPanel skillCell(Skill skill, int level)
	{
		JPanel cell = new JPanel(new BorderLayout(2, 0));
		cell.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel icon = new JLabel();
		icon.setToolTipText(skill.getName());
		try
		{
			BufferedImage img = skillIcons.getSkillImage(skill, true);
			if (img != null)
			{
				icon.setIcon(new ImageIcon(img.getScaledInstance(18, 18, java.awt.Image.SCALE_SMOOTH)));
			}
		}
		catch (Exception ignored)
		{
			// No icon for this skill (e.g. an unreleased one) - leave it blank.
		}

		JLabel value = new JLabel(String.valueOf(level));
		value.setFont(FontManager.getRunescapeSmallFont());
		value.setForeground(Color.YELLOW);

		cell.add(icon, BorderLayout.WEST);
		cell.add(value, BorderLayout.CENTER);
		return cell;
	}

	private JPanel totalRow(int total)
	{
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		if (TOTAL_ICON != null)
		{
			row.add(new JLabel(TOTAL_ICON));
		}
		JLabel value = new JLabel("Total level: " + total);
		value.setForeground(Color.WHITE);
		value.setFont(FontManager.getRunescapeSmallFont());
		row.add(value);
		return row;
	}

	private JPanel buildCombatAndKc(Activity activity, Applicant stats)
	{
		JPanel detail = new JPanel(new GridLayout(0, 2, 6, 2));
		detail.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		detail.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

		detail.add(detailLeft("Combat"));
		detail.add(detailRight(String.valueOf(stats.getCombatLevel())));

		// Killcount: live reports carry -1, so fall back to a hiscores lookup by name.
		int kc = stats.getKillCount();
		int hardKc = stats.getHardModeKillCount();
		boolean lookingUp = false;
		if (kc < 0 && activity != null && stats.getName() != null)
		{
			KillcountService.Killcount cached = killcounts.cached(stats.getName(), activity);
			if (cached != null)
			{
				kc = cached.killCount;
				hardKc = cached.hardModeKillCount;
			}
			else
			{
				killcounts.lookup(stats.getName(), activity, this::refresh);
				lookingUp = true;
			}
		}

		String activityName = activity != null ? activity.getDisplayName() : "Activity";
		if (kc >= 0)
		{
			detail.add(detailLeft(activityName + " KC"));
			detail.add(detailRight(String.valueOf(kc)));

			if (activity != null && activity.hasHardMode() && hardKc >= 0)
			{
				detail.add(detailLeft(activity.getHardModeLabel() + " KC"));
				detail.add(detailRight(String.valueOf(hardKc)));
			}
		}
		else if (lookingUp)
		{
			detail.add(detailLeft(activityName + " KC"));
			detail.add(detailRight("looking up..."));
		}

		// Personal best (broadcast by the applicant's own client) for timed activities.
		if (activity != null && PersonalBests.isPbActivity(activity.getId()))
		{
			detail.add(detailLeft(activityName + " PB"));
			detail.add(detailRight(stats.getPbSeconds() >= 0
				? PersonalBests.format(stats.getPbSeconds()) : "n/a"));
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
		applicant.setPbSeconds(update.getPbSeconds());
		return applicant;
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
			hostApplicationHandler.announceResolved(toApplicant(member.getData()), activity, true);
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
			hostApplicationHandler.announceResolved(toApplicant(member.getData()), activity, false);
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

	private void requestFc(RosterMember member, String hostFc)
	{
		liveParty.requestFriendsChat(member.getMemberId(), hostFc);
		setStatus("Asked " + member.getName() + " to join friends chat \"" + hostFc + "\".");
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

	/** Put the player's ironman badge (if any) before their name. */
	private void applyAccountIcon(JLabel label, PlayerUpdate data)
	{
		if (data == null)
		{
			return;
		}
		AccountType type = AccountTypes.fromName(data.getAccountType());
		ImageIcon icon = AccountIcons.forType(type);
		if (icon != null)
		{
			label.setIcon(icon);
			label.setIconTextGap(4);
			label.setToolTipText(accountTypeName(type));
		}
	}

	private static String accountTypeName(AccountType type)
	{
		switch (type)
		{
			case IRONMAN:
				return "Ironman";
			case HARDCORE_IRONMAN:
				return "Hardcore Ironman";
			case ULTIMATE_IRONMAN:
				return "Ultimate Ironman";
			case GROUP_IRONMAN:
				return "Group Ironman";
			case HARDCORE_GROUP_IRONMAN:
				return "Hardcore Group Ironman";
			default:
				return "Ironman";
		}
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
