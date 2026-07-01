package net.osparty.ui;

import net.osparty.FavoritesService;
import net.osparty.HostApplicationHandler;
import net.osparty.KillcountService;
import net.osparty.OSPartyConfig;
import net.osparty.PersonalBests;
import net.osparty.api.PartyService;
import net.osparty.model.AccountTypes;
import net.osparty.model.Activity;
import net.osparty.model.Applicant;
import net.osparty.model.Applicant.EquipmentSlot;
import net.osparty.model.LootRule;
import net.osparty.model.Party;
import net.osparty.model.Role;
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
import java.awt.Rectangle;
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
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageUtil;

/**
 * "Party" tab: the live party the player is in. The roster, statuses and each
 * member's gear/inventory/stats come from the peer-to-peer party
 * ({@link LiveParty}); the API only advertised the room.
 */
class PartyPanel extends JPanel
{
	private static final int TAB_SKILLS = 0;
	private static final int TAB_GEAR = 1;
	private static final int TAB_INVENTORY = 2;

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
	private final Supplier<String> coxLayoutSupplier;
	private final OSPartyConfig config;
	private final ConfigManager configManager;
	private final FavoritesService favoritesService;
	private final net.osparty.BlockListService blockListService;

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
			BufferedImage img = ImageUtil.loadImageResource(PartyPanel.class, "/net/osparty/icons/total.png");
			return new ImageIcon(img.getScaledInstance(18, 16, java.awt.Image.SCALE_SMOOTH));
		}
		catch (Exception e)
		{
			return null;
		}
	}

	private final JPanel content = new JPanel();
	private final JLabel statusLabel = new JLabel();

	private final Set<Long> expanded = new HashSet<>();
	private final Map<Long, Integer> detailTab = new HashMap<>();
	private final Set<Long> notifiedPending = new HashSet<>();
	private int lastReportedSize = -1;
	private String lastReportedLayout;
	/** Invoked when the host clicks "Edit party"; wired by the owning panel to open the edit form. */
	private Runnable onEditParty;
	/** memberId -> epoch millis until which the "Request FC" button is on cooldown. */
	private final Map<Long, Long> fcRequestCooldown = new HashMap<>();
	private static final long FC_REQUEST_COOLDOWN_MS = 10_000;

	PartyPanel(PartyService partyService, Supplier<String> playerNameSupplier,
		HostApplicationHandler hostApplicationHandler, PartyState partyState, ItemManager itemManager,
		LiveParty liveParty, RuneWatchService runeWatch, KillcountService killcounts,
		SkillIconManager skillIcons, IntSupplier currentWorld, IntConsumer worldHopper,
		Supplier<String> friendsChatOwnerSupplier, Supplier<String> coxLayoutSupplier,
		OSPartyConfig config, ConfigManager configManager, FavoritesService favoritesService,
		net.osparty.BlockListService blockListService)
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
		this.coxLayoutSupplier = coxLayoutSupplier;
		this.config = config;
		this.configManager = configManager;
		this.favoritesService = favoritesService;
		this.blockListService = blockListService;

		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Track the viewport width so rows never exceed it (otherwise long rows clip on
		// the right and the chevron disappears with HORIZONTAL_SCROLLBAR_NEVER).
		JPanel wrap = new ScrollableColumn(new BorderLayout());
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
		// doesn't reap our ad as stale (and report current occupancy). No-op when
		// we're not hosting.
		new Timer(30_000, e -> {
			if (partyState.isHost() && partyState.getCurrentParty() != null)
			{
				partyService.heartbeat(partyState.getCurrentParty().getId(), currentPartySize(),
					currentWorld.getAsInt(), currentLayout(), currentNeededRolesParam(), partyState.getHostKey(),
					ok -> { }, err -> { });
			}
		}).start();

		// The CoX layout fills in as the host explores the raid; push it promptly
		// (rather than waiting for the 30s keep-alive) whenever it changes.
		new Timer(3_000, e -> {
			if (!partyState.isHost() || partyState.getCurrentParty() == null)
			{
				return;
			}
			String layout = currentLayout();
			if (layout != null && !layout.equals(lastReportedLayout))
			{
				lastReportedLayout = layout;
				partyService.heartbeat(partyState.getCurrentParty().getId(), currentPartySize(),
					currentWorld.getAsInt(), layout, currentNeededRolesParam(), partyState.getHostKey(),
					ok -> { }, err -> { });
			}
		}).start();

		refresh();
	}

	private int currentPartySize()
	{
		if (!liveParty.isConnected())
		{
			return 1;
		}
		int count = (int) liveParty.roster().stream()
			.filter(m -> m.getStatus() != Status.PENDING).count();
		return Math.max(1, count);
	}

	private String currentLayout()
	{
		Party party = partyState.getCurrentParty();
		if (party == null || !partyState.isHost() || !partyState.isAdvertiseLayout()
			|| !"cox".equals(party.getActivity()))
		{
			return null;
		}
		return coxLayoutSupplier.get();
	}

	private String currentNeededRolesParam()
	{
		Party party = partyState.getCurrentParty();
		if (party == null || !partyState.isHost())
		{
			return null;
		}
		Activity activity = Activity.fromId(party.getActivity());
		if (activity == null || !activity.hasRoles())
		{
			return null;
		}
		List<String> needed = liveParty.neededRoles(party.getRequiredRoles());
		if (needed == null || needed.isEmpty())
		{
			return null;
		}
		return String.join(",", needed);
	}

	private String neededRolesText(Activity activity, Party party)
	{
		if (activity == null || !activity.hasRoles())
		{
			return null;
		}
		List<String> needed = partyState.isHost()
			? liveParty.neededRoles(party.getRequiredRoles())
			: party.getNeededRoles();
		if (needed == null || needed.isEmpty())
		{
			return null;
		}
		Map<String, Integer> counts = new java.util.LinkedHashMap<>();
		for (String id : needed)
		{
			counts.merge(id, 1, Integer::sum);
		}
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : counts.entrySet())
		{
			String name = Role.displayNameOf(entry.getKey());
			parts.add(entry.getValue() > 1 ? name + " x" + entry.getValue() : name);
		}
		return "Needs: " + String.join(", ", parts);
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
			lastReportedSize = -1;
			lastReportedLayout = null;
			hostApplicationHandler.setPendingApplicants(java.util.Collections.emptyList(), null);
			// Clear any leftover action text so a new party doesn't open showing the last
			// party's "Disbanding…/Left…" message (the stale "one step behind" status).
			setStatus("");
			content.revalidate();
			content.repaint();
			return;
		}

		boolean host = partyState.isHost();
		Activity activity = Activity.fromId(party.getActivity());
		String activityName = activity != null
			? activity.displayName(party.isHardMode(), party.getInvocation())
			: party.getActivity();

		JLabel header = new JLabel(host
			? "Your " + activityName + " party"
			: party.getHost() + "'s " + activityName + " party");
		header.setForeground(Color.WHITE);
		header.setFont(FontManager.getRunescapeBoldFont());
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(0, 0, 4, 0)));
		content.add(header);
		content.add(Box.createVerticalStrut(4));

		List<RosterMember> roster = liveParty.isConnected() ? liveParty.roster() : null;

		int admitted = roster == null ? 0
			: (int) roster.stream().filter(m -> m.getStatus() != Status.PENDING).count();

		// Push the live occupancy to the ad as soon as it changes, so search results
		// reflect who's actually in the party (not just the host).
		if (host && admitted > 0 && admitted != lastReportedSize)
		{
			lastReportedSize = admitted;
			partyService.heartbeat(party.getId(), admitted, currentWorld.getAsInt(), currentLayout(),
				currentNeededRolesParam(), partyState.getHostKey(), ok -> { }, err -> { });
		}

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

		String needs = neededRolesText(activity, party);
		if (needs != null)
		{
			JLabel needsLabel = subLabel(needs);
			needsLabel.setForeground(ColorScheme.BRAND_ORANGE);
			content.add(needsLabel);
		}

		List<String> tags = new ArrayList<>();
		if (party.isLearnerRaid())
		{
			tags.add(party.learnerLabel());
		}
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

		if (host && party.getInviteCode() != null)
		{
			content.add(copyRow("Invite code: " + party.getInviteCode(), party.getInviteCode(),
				"Copy invite code", "Invite code copied to clipboard.", ColorScheme.LIGHT_GRAY_COLOR));
		}
		if (host && liveParty.passphrase() != null)
		{
			content.add(copyRow("Passphrase: " + liveParty.passphrase(), liveParty.passphrase(),
				"Copy passphrase", "Passphrase copied to clipboard.", ColorScheme.LIGHT_GRAY_COLOR));
		}

		// Ready check at the top (anyone can start; everyone readies up).
		if (liveParty.isConnected())
		{
			content.add(Box.createVerticalStrut(8));
			content.add(buildReadyCheck());
		}

		content.add(Box.createVerticalStrut(6));

		if (roster == null || roster.isEmpty())
		{
			content.add(subLabel("Connecting to live room…"));
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
				content.add(subLabel("Awaiting host approval…"));
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

		JPanel stack = new JPanel();
		stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
		stack.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// ---- primary row: dot · star · name ............ chevron (the one disclosure trigger) ----
		JPanel topRow = cappedPanel(new BorderLayout(4, 0));
		topRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		topRow.setAlignmentX(Component.LEFT_ALIGNMENT);

		boolean online = member.isOnline();
		JLabel dot = new JLabel(online ? StatusIcons.ONLINE : StatusIcons.OFFLINE);
		dot.setToolTipText(online ? "Online" : "Offline");

		// Host gets a gold crown next to the name (not "(host)" text); we never label
		// ourselves "(you)" — we know who we are.
		String tag = status == Status.PENDING ? " (pending)" : "";
		JLabel name = new JLabel(member.getName() + tag);
		name.setForeground(status == Status.HOST ? ColorScheme.BRAND_ORANGE
			: status == Status.PENDING ? ColorScheme.PROGRESS_INPROGRESS_COLOR : Color.WHITE);
		applyAccountIcon(name, member.getData());

		JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		((FlowLayout) left.getLayout()).setAlignOnBaseline(true);
		left.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		left.add(dot);
		JButton star = memberStar(member);
		if (star != null)
		{
			left.add(star);
		}
		JButton block = memberBlock(member);
		if (block != null)
		{
			left.add(block);
		}
		if (status == Status.HOST && StatusIcons.CROWN != null)
		{
			JLabel crown = new JLabel(StatusIcons.CROWN);
			crown.setToolTipText("Host");
			left.add(crown);
		}
		left.add(name);

		// RuneWatch warning icon, trailing the name (point 31; non-ironman badge dropped).
		RuneWatchCase flagged = runeWatch.get(member.getName());
		if (flagged != null)
		{
			left.add(runeWatchBadge(flagged));
		}

		JLabel chevron = new JLabel(isExpanded ? StatusIcons.CHEVRON_UP : StatusIcons.CHEVRON_DOWN);
		chevron.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		chevron.setToolTipText(isExpanded ? "Hide gear & stats" : "Show gear & stats");
		chevron.setHorizontalAlignment(SwingConstants.CENTER);
		chevron.setVerticalAlignment(SwingConstants.CENTER);
		chevron.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 2));
		chevron.addMouseListener(expandOnClick(member));

		topRow.add(left, BorderLayout.CENTER);
		topRow.add(chevron, BorderLayout.EAST);
		stack.add(topRow);

		// ---- secondary line: role · learner · world, plus the friends-chat icons ----
		List<String> bits = new ArrayList<>();
		if (activity != null && activity.hasRoles())
		{
			String roleId = member.isLocal()
				? liveParty.getLocalRole()
				: (member.getData() != null ? member.getData().getRole() : null);
			if (roleId != null)
			{
				bits.add(Role.displayNameOf(roleId));
			}
		}
		boolean memberLearner = member.isLocal()
			? liveParty.isLocalLearner()
			: (member.getData() != null && member.getData().isLearner());
		if (memberLearner && activity != null && activity.isRaid())
		{
			bits.add("Learner");
		}
		PlayerUpdate data = member.getData();
		int world = data != null ? data.getWorld() : 0;
		if (world > 0)
		{
			bits.add("W" + world);
		}

		JPanel metaRow = cappedPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		((FlowLayout) metaRow.getLayout()).setAlignOnBaseline(true);
		metaRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		metaRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		metaRow.setBorder(BorderFactory.createEmptyBorder(0, 16, 2, 0));
		boolean anyMeta = false;
		if (!bits.isEmpty())
		{
			JLabel metaLabel = new JLabel(String.join("  ·  ", bits));
			metaLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			metaLabel.setFont(FontManager.getRunescapeSmallFont());
			metaRow.add(metaLabel);
			anyMeta = true;
		}
		boolean showFc = hostName != null && data != null;
		if (showFc)
		{
			if (StatusIcons.FRIENDS_CHAT != null)
			{
				JLabel fcLogo = new JLabel(StatusIcons.FRIENDS_CHAT);
				fcLogo.setToolTipText(hostName + "'s friends chat");
				metaRow.add(fcLogo);
			}
			boolean inFc = sameRsn(data.getFriendsChatOwner(), hostName);
			JLabel fcIcon = new JLabel(inFc ? StatusIcons.CHECK : StatusIcons.CROSS);
			fcIcon.setToolTipText(inFc
				? "In " + hostName + "'s friends chat"
				: "Not in " + hostName + "'s friends chat");
			metaRow.add(fcIcon);
			anyMeta = true;
		}
		if (anyMeta)
		{
			stack.add(metaRow);
		}

		// ---- action buttons ----
		JComponent actions = buildActionsRow(activity, member, host, hostName);
		if (actions != null)
		{
			JPanel actionRow = cappedPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
			actionRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			actionRow.setAlignmentX(Component.LEFT_ALIGNMENT);
			actionRow.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 0));
			actionRow.add(actions);
			stack.add(actionRow);
		}

		entry.add(stack, BorderLayout.NORTH);

		if (isExpanded)
		{
			entry.add(buildDetail(activity, member), BorderLayout.CENTER);
		}

		return entry;
	}

	/** A favorite-toggle star for any roster member (point 34), keyed by name. */
	private JButton memberStar(RosterMember member)
	{
		// No star on yourself — you can't favorite your own account.
		if (favoritesService == null || member.getName() == null || member.isLocal())
		{
			return null;
		}
		final String rsn = member.getName();
		final long hash = memberHash(member);
		JButton star = new JButton();
		star.setFocusPainted(false);
		star.setContentAreaFilled(false);
		star.setBorderPainted(false);
		star.setMargin(new Insets(0, 2, 0, 2));
		boolean fav = favoritesService.isFavorite(hash, rsn);
		star.setIcon(fav ? StatusIcons.STAR_FILLED : StatusIcons.STAR_OUTLINE);
		star.setToolTipText(fav ? "Remove " + rsn + " from Favorites" : "Add " + rsn + " to Favorites");
		star.addActionListener(e -> {
			favoritesService.toggle(hash, rsn);
			boolean nowFav = favoritesService.isFavorite(hash, rsn);
			star.setIcon(nowFav ? StatusIcons.STAR_FILLED : StatusIcons.STAR_OUTLINE);
			star.setToolTipText(nowFav ? "Remove " + rsn + " from Favorites" : "Add " + rsn + " to Favorites");
		});
		return star;
	}

	/** A block-toggle button for any roster member (keyed by accountHash when known). */
	private JButton memberBlock(RosterMember member)
	{
		// You can't block your own account.
		if (blockListService == null || member.getName() == null || member.isLocal())
		{
			return null;
		}
		final String rsn = member.getName();
		final long hash = memberHash(member);
		JButton block = new JButton();
		block.setFocusPainted(false);
		block.setContentAreaFilled(false);
		block.setBorderPainted(false);
		block.setMargin(new Insets(0, 2, 0, 2));
		boolean blocked = blockListService.isBlocked(hash, rsn);
		block.setIcon(blocked ? StatusIcons.BLOCK_ON : StatusIcons.BLOCK_OFF);
		block.setToolTipText(blocked ? "Unblock " + rsn : "Block " + rsn);
		block.addActionListener(e -> {
			boolean wasBlocked = blockListService.isBlocked(hash, rsn);
			blockListService.toggle(hash, rsn);
			boolean nowBlocked = !wasBlocked;
			if (nowBlocked && favoritesService != null && favoritesService.isFavorite(hash, rsn))
			{
				favoritesService.toggle(hash, rsn);
			}
			block.setIcon(nowBlocked ? StatusIcons.BLOCK_ON : StatusIcons.BLOCK_OFF);
			block.setToolTipText(nowBlocked ? "Unblock " + rsn : "Block " + rsn);
			refresh();
		});
		return block;
	}

	/** The member's self-reported accountHash, or {@code 0} until they've synced. */
	private static long memberHash(RosterMember member)
	{
		return member.getData() != null ? member.getData().getAccountHash() : 0L;
	}

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
				setStatus("Hopping to world " + world + "…");
			});
			wrap.add(hop);
			any = true;
		}

		// Per-activity "how to join the raid" prompt the host can send a member:
		// CoX = join the host's friends chat; ToB = notice board; ToA = Grouping Obelisk.
		if (host && data != null && activity != null)
		{
			String kind = null;
			String label = null;
			String tip = null;
			if (activity == Activity.CHAMBERS_OF_XERIC)
			{
				// CoX coordinates via the host's friends chat: only when the host has one
				// open and the member isn't already in it.
				if (hostName != null && !sameRsn(data.getFriendsChatOwner(), hostName)
					&& sameRsn(friendsChatOwnerSupplier.get(), hostName))
				{
					kind = "FC";
					label = "Request FC";
					tip = "Ask " + member.getName() + " to join your friends chat";
				}
			}
			else if (activity == Activity.THEATRE_OF_BLOOD)
			{
				kind = "NOTICE_BOARD";
				label = "Remind board";
				tip = "Remind " + member.getName() + " to apply on the notice board";
			}
			else if (activity == Activity.TOMBS_OF_AMASCUT)
			{
				kind = "OBELISK";
				label = "Remind obelisk";
				tip = "Remind " + member.getName() + " to apply on the Grouping Obelisk";
			}
			if (kind != null)
			{
				long remaining = fcRequestCooldown.getOrDefault(member.getMemberId(), 0L) - System.currentTimeMillis();
				boolean ready = remaining <= 0;
				JButton prompt = smallButton(ready ? label : "Sent");
				prompt.setEnabled(ready);
				prompt.setToolTipText(ready ? tip : "Wait a few seconds before asking again");
				final String k = kind;
				if (ready)
				{
					prompt.addActionListener(e -> sendJoinPrompt(member, k, hostName));
				}
				wrap.add(prompt);
				any = true;
			}
		}

		return any ? wrap : null;
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

	private JComponent buildDetail(Activity activity, RosterMember member)
	{
		PlayerUpdate data = member.getData();
		if (data == null)
		{
			JPanel waiting = new JPanel(new BorderLayout());
			waiting.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			waiting.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
			waiting.add(detailLeft("Waiting for live data…"), BorderLayout.CENTER);
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
			detail.add(detailRight("looking up…"));
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
		applicant.setMemberId(update.getMemberId());
		applicant.setName(update.getName());
		applicant.setCombatLevel(update.getCombatLevel());
		applicant.setStats(update.getStats());
		applicant.setEquipment(update.getEquipment());
		applicant.setInventory(update.getInventory());
		applicant.setKillCount(update.getKillCount());
		applicant.setHardModeKillCount(update.getHardModeKillCount());
		applicant.setPbSeconds(update.getPbSeconds());
		applicant.setAccountType(update.getAccountType());
		applicant.setRole(update.getRole());
		applicant.setLearner(update.isLearner());
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

	private void sendJoinPrompt(RosterMember member, String kind, String hostFc)
	{
		if ("FC".equals(kind))
		{
			liveParty.sendJoinPrompt(member.getMemberId(), "FC", hostFc);
			setStatus("Asked " + member.getName() + " to join friends chat \"" + hostFc + "\".");
		}
		else
		{
			liveParty.sendJoinPrompt(member.getMemberId(), kind, null);
			String where = "OBELISK".equals(kind) ? "the Grouping Obelisk" : "the notice board";
			setStatus("Reminded " + member.getName() + " to apply on " + where + ".");
		}
		// Throttle: keep the button disabled for a few seconds, then re-enable.
		fcRequestCooldown.put(member.getMemberId(), System.currentTimeMillis() + FC_REQUEST_COOLDOWN_MS);
		Timer reEnable = new Timer((int) FC_REQUEST_COOLDOWN_MS, e -> refresh());
		reEnable.setRepeats(false);
		reEnable.start();
		refresh();
	}

	private JComponent buildReadyCheck()
	{
		LiveParty.ReadyCheckStatus status = liveParty.readyCheck();
		JPanel row = cappedPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		if (status == null)
		{
			JButton start = new JButton("Start ready check");
			start.setFocusPainted(false);
			start.addActionListener(e -> {
				liveParty.startReadyCheck();
				setStatus("Ready check started.");
				refresh();
			});
			row.add(start, BorderLayout.CENTER);
			return row;
		}

		String counts = status.getReady() + "/" + status.getTotal();
		if (!status.isLocalReady())
		{
			JButton ready = new JButton("Ready up (" + counts + ")");
			ready.setFocusPainted(false);
			ready.addActionListener(e -> {
				liveParty.markReady();
				refresh();
			});
			row.add(ready, BorderLayout.CENTER);
		}
		else
		{
			JLabel waiting = new JLabel("Ready " + counts + " - " + status.getSecondsLeft() + "s left");
			waiting.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
			waiting.setFont(FontManager.getRunescapeSmallFont());
			waiting.setHorizontalAlignment(SwingConstants.CENTER);
			row.add(waiting, BorderLayout.CENTER);
		}
		return row;
	}

	private JPanel buildActions(Party party, boolean host)
	{
		JPanel actions = cappedPanel(new BorderLayout(0, 4));
		actions.setBackground(ColorScheme.DARK_GRAY_COLOR);
		actions.setAlignmentX(Component.LEFT_ALIGNMENT);

		// Host-only: edit the advertised settings of this party.
		if (host && onEditParty != null)
		{
			JButton edit = new JButton("Edit party");
			edit.setFocusPainted(false);
			edit.addActionListener(e -> onEditParty.run());
			actions.add(edit, BorderLayout.NORTH);
		}

		JButton button = new JButton(host ? "Disband party" : "Leave party");
		button.setFocusPainted(false);
		button.addActionListener(e -> {
			if (host)
			{
				confirmDisband(party, button);
			}
			else
			{
				leave(button);
			}
		});
		actions.add(button, BorderLayout.CENTER);
		return actions;
	}

	/** Wire the host-only "Edit party" button to the owning panel's edit flow. */
	void setOnEditParty(Runnable onEditParty)
	{
		this.onEditParty = onEditParty;
	}

	/**
	 * Confirm before destroying a party you host (point 23), with a "Don't ask me
	 * again" option persisted to config. Leaving (non-host) is not gated.
	 */
	private void confirmDisband(Party party, JButton button)
	{
		if (config != null && config.skipDisbandConfirm())
		{
			disband(party, button);
			return;
		}
		JPanel msg = new JPanel(new BorderLayout(0, 6));
		msg.add(new JLabel("Disband this party? All members will be removed."), BorderLayout.NORTH);
		JCheckBox dontAsk = new JCheckBox("Don't ask me again");
		msg.add(dontAsk, BorderLayout.CENTER);
		int result = JOptionPane.showConfirmDialog(this, msg, "Disband party",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (result != JOptionPane.OK_OPTION)
		{
			return;
		}
		if (dontAsk.isSelected() && configManager != null)
		{
			configManager.setConfiguration(OSPartyConfig.GROUP, "skipDisbandConfirm", true);
		}
		disband(party, button);
	}

	private void disband(Party party, JButton button)
	{
		button.setEnabled(false);
		setStatus("Disbanding party…");
		// Remove the ad (fire-and-forget) and close the live room. Read the host key
		// before clear() wipes it.
		partyService.disbandParty(party.getId(), party.getHost(), partyState.getHostKey(), ignored -> { }, error -> { });
		liveParty.leave();
		partyState.clear();
	}

	private void leave(JButton button)
	{
		button.setEnabled(false);
		setStatus("Leaving party…");
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

	/** A label followed by an OS-safe copy-to-clipboard button (point 27). */
	private JPanel copyRow(String labelText, String copyValue, String tooltip, String statusMsg, Color fg)
	{
		// BorderLayout so the copy button stays pinned at the right and a long passphrase
		// truncates (with the full value in the tooltip) instead of pushing the button off.
		JPanel row = cappedPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel label = new JLabel(labelText);
		label.setForeground(fg);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setToolTipText(copyValue);

		JButton copy = new JButton(StatusIcons.COPY);
		copy.setFocusPainted(false);
		copy.setContentAreaFilled(false);
		copy.setBorderPainted(false);
		copy.setMargin(new Insets(0, 2, 0, 2));
		copy.setToolTipText(tooltip);
		copy.addActionListener(e -> {
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(copyValue), null);
			setStatus(statusMsg);
			// Brief green "Copied!" confirmation on the label, then revert.
			label.setText("Copied!");
			label.setForeground(new Color(0x4C, 0xD1, 0x37));
			Timer revert = new Timer(1200, ev -> {
				label.setText(labelText);
				label.setForeground(fg);
			});
			revert.setRepeats(false);
			revert.start();
		});

		row.add(label, BorderLayout.CENTER);
		row.add(copy, BorderLayout.EAST);
		return row;
	}

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

	private JLabel runeWatchBadge(RuneWatchCase flagged)
	{
		String reason = flagged.getReason() == null || flagged.getReason().isEmpty()
			? "listed" : flagged.getReason();
		// HTML width-caps the label so a long reason wraps instead of widening the card.
		JLabel label = new JLabel("<html><div style='width:150px'>"
			+ flagged.sourceName() + ": " + escape(reason) + "</div></html>");
		label.setIcon(StatusIcons.RUNEWATCH);
		label.setIconTextGap(4);
		label.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());

		StringBuilder tip = new StringBuilder("<html>").append(flagged.sourceName()).append(" case");
		if (flagged.getCode() != null)
		{
			tip.append(' ').append(escape(flagged.getCode()));
		}
		if (flagged.getRating() != null)
		{
			tip.append(" — evidence rating ").append(escape(flagged.getRating()));
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

	/** A column that fills the scroll viewport's width so rows never clip horizontally. */
	private static class ScrollableColumn extends JPanel implements Scrollable
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
	}

	private void setStatus(String text)
	{
		statusLabel.setText(text);
	}
}
