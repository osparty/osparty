package net.osparty.ui;

import net.osparty.service.FavoritesService;
import net.osparty.tools.HostApplicationHandler;
import net.osparty.service.KillcountService;
import net.osparty.enums.BlockedApplicantAction;
import net.osparty.OSPartyConfig;
import net.osparty.tools.PersonalBests;
import net.osparty.api.BoardService;
import net.osparty.model.AccountTypes;
import net.osparty.model.Activity;
import net.osparty.model.Applicant;
import net.osparty.model.Applicant.EquipmentSlot;
import net.osparty.model.LootRule;
import net.osparty.model.Member;
import net.osparty.model.Advertisement;
import net.osparty.model.PartyMeta;
import net.osparty.model.Role;
import net.osparty.party.LivePartyBackend;
import net.osparty.party.RosterMember;
import net.osparty.party.PartyStatus;
import net.osparty.party.PlayerUpdate;
import net.osparty.party.ReadyCheckStatus;
import net.osparty.model.RuneWatchCase;
import net.osparty.service.RuneWatchService;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import net.osparty.service.BlockListService;
import net.runelite.api.vars.AccountType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.api.Skill;
import net.runelite.api.SpriteID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

/** "Party" tab: the live party the player is in; roster/gear/stats come from {@link LivePartyBackend}. */
@lombok.extern.slf4j.Slf4j
class PartyPanel extends JPanel
{
	private static final int TAB_SKILLS = 0;
	private static final int TAB_GEAR = 1;
	private static final int TAB_INVENTORY = 2;

	private static final Dimension SLOT_SIZE = new Dimension(36, 32);

	/** Discord "blurple", matching the plugin's discord.png accent, for the voice buttons. */
	private static final Color DISCORD_BLURPLE = new Color(0x58, 0x65, 0xF2);
	private static final ImageIcon DISCORD_ICON = loadDiscordIcon();

	private static ImageIcon loadDiscordIcon()
	{
		BufferedImage img = ImageUtil.loadImageResource(PartyPanel.class, "/net/osparty/icons/discord.png");
		return img == null ? null : new ImageIcon(ImageUtil.resizeImage(img, 14, 14));
	}

	private final BoardService boardService;
	private final HostApplicationHandler hostApplicationHandler;
	private final PartyState partyState;
	private final ItemManager itemManager;
	private final LivePartyBackend liveParty;
	private final RuneWatchService runeWatch;
	private final KillcountService killcounts;
	private final SkillIconManager skillIcons;
	private final SpriteManager spriteManager;
	private final IntSupplier currentWorld;
	private final Supplier<String> friendsChatOwnerSupplier;
	private final Supplier<String> coxLayoutSupplier;
	private final OSPartyConfig config;
	private final ConfigManager configManager;
	private final FavoritesService favoritesService;
	private final BlockListService blockListService;
	/** Whether the local account is Discord-linked (gates the voice buttons), and the action to start linking. */
	private final java.util.function.BooleanSupplier discordLinkedSupplier;
	private final Runnable onAuthorizeDiscord;
	private final java.util.function.LongSupplier accountHashSupplier;
	private final HostTransferHandler hostTransferHandler;

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
	/** Applicants we've already auto-declined for being on the block list, so we reject them once. */
	private final Set<Long> autoDeclinedBlocked = new HashSet<>();
	private int lastReportedSize = -1;
	private String lastReportedLayout;
	/** Invoked when the host clicks "Edit party"; wired by the owning panel to open the edit form. */
	private Runnable onEditParty;
	/**
	 * Ready-check countdown label plus the ticker that retexts it. The panel as a whole only
	 * re-renders on roster/websocket events, which is far too coarse for a per-second countdown,
	 * so the label updates itself instead of waiting for the next refresh.
	 */
	private JLabel readyCheckCountdown;
	private final Timer readyCheckTicker = new Timer(200, e -> tickReadyCheck());
	/** memberId -> epoch millis until which the "Request FC" button is on cooldown. */
	private final Map<Long, Long> joinPromptCooldown = new HashMap<>();
	private static final long JOIN_PROMPT_COOLDOWN_MS = 10_000;

	/**
	 * The freshest ad member list (with server-asserted Discord badges), refetched whenever the roster
	 * changes: {@code currentAd} is a join-time snapshot that never gains later joiners' badges.
	 */
	private volatile List<Member> liveAdMembers;
	private String liveAdBadgeSig = "";
	/** Roster membership last seen; a change (join/leave/admit/hash resolve) triggers an ad refetch. */
	private String lastRosterKey;

	/** Reused member rows, keyed by memberId, rebuilt only when {@link #memberSignature} changes. */
	private final Map<Long, JPanel> memberEntryCache = new HashMap<>();
	private final Map<Long, String> memberEntrySig = new HashMap<>();
	/** Memoised orb sprites so a reused/rebuilt vitals row sets its icon synchronously (no flicker). */
	private final Map<Integer, ImageIcon> orbIconCache = new HashMap<>();

	/** Keep-alive for our hosted ad, and the faster poll that pushes a changed CoX layout. */
	private final Timer adHeartbeatTimer;
	private final Timer coxLayoutTimer;

	PartyPanel(BoardService boardService,
               HostApplicationHandler hostApplicationHandler, PartyState partyState, ItemManager itemManager,
               LivePartyBackend liveParty, RuneWatchService runeWatch, KillcountService killcounts,
               SkillIconManager skillIcons, IntSupplier currentWorld,
               Supplier<String> friendsChatOwnerSupplier, Supplier<String> coxLayoutSupplier,
               OSPartyConfig config, ConfigManager configManager, FavoritesService favoritesService,
               BlockListService blockListService, SpriteManager spriteManager,
               java.util.function.BooleanSupplier discordLinkedSupplier, Runnable onAuthorizeDiscord,
               java.util.function.LongSupplier accountHashSupplier, HostTransferHandler hostTransferHandler)
	{
		this.boardService = boardService;
		this.hostTransferHandler = hostTransferHandler;
		this.discordLinkedSupplier = discordLinkedSupplier;
		this.onAuthorizeDiscord = onAuthorizeDiscord;
		this.accountHashSupplier = accountHashSupplier;
		this.hostApplicationHandler = hostApplicationHandler;
		this.partyState = partyState;
		this.itemManager = itemManager;
		this.liveParty = liveParty;
		this.runeWatch = runeWatch;
		this.killcounts = killcounts;
		this.skillIcons = skillIcons;
		this.spriteManager = spriteManager;
		this.currentWorld = currentWorld;
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

		// Track the viewport width so rows never clip and the chevron stays visible.
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
		// Our hosted ad was removed server-side (stale purge / manual cleanup): fold the tab and
		// close the room, which in turn clears every member's tab via the roster logic above.
		boardService.setOnHostedAdGone(id -> SwingUtilities.invokeLater(() -> onHostedAdGone(id)));

		adHeartbeatTimer = new Timer(30_000, e -> {
			if (partyState.isHost() && partyState.getCurrentAd() != null)
			{
				boardService.heartbeat(partyState.getCurrentAd().getId(), currentPartySize(),
					currentWorld.getAsInt(), currentLayout(), currentNeededRolesParam(), liveParty.rosterMembers(),
					partyState.getHostKey(), ok -> { }, err -> { });
				verifyAdStillExists();
			}
		});
		adHeartbeatTimer.start();

		// Push the CoX layout promptly when it changes, not on the 30s keep-alive.
		coxLayoutTimer = new Timer(3_000, e -> {
			if (!partyState.isHost() || partyState.getCurrentAd() == null)
			{
				return;
			}
			String layout = currentLayout();
			if (layout != null && !layout.equals(lastReportedLayout))
			{
				lastReportedLayout = layout;
				boardService.heartbeat(partyState.getCurrentAd().getId(), currentPartySize(),
					currentWorld.getAsInt(), layout, currentNeededRolesParam(), liveParty.rosterMembers(),
					partyState.getHostKey(), ok -> { }, err -> { });
			}
		});
		coxLayoutTimer.start();

		refresh();
	}

	/** Stop the panel's timers so they can't outlive the plugin. Call when the plugin unloads. */
	void dispose()
	{
		adHeartbeatTimer.stop();
		coxLayoutTimer.stop();
		readyCheckTicker.stop();
	}

	private int currentPartySize()
	{
		if (!liveParty.isInParty())
		{
			return 1;
		}
		int count = (int) liveParty.roster().stream()
			.filter(m -> m.getStatus() != PartyStatus.PENDING).count();
		return Math.max(1, count);
	}

	private String currentLayout()
	{
		Advertisement ad = partyState.getCurrentAd();
		if (ad == null || !partyState.isHost() || !partyState.isAdvertiseLayout()
			|| !"cox".equals(ad.getActivity()))
		{
			return null;
		}
		return coxLayoutSupplier.get();
	}

	private String currentNeededRolesParam()
	{
		Advertisement ad = partyState.getCurrentAd();
		if (ad == null || !partyState.isHost())
		{
			return null;
		}
		Activity activity = Activity.fromId(ad.getActivity());
		if (activity == null || !activity.hasRoles())
		{
			return null;
		}
		List<String> needed = liveParty.neededRoles(ad.getRequiredRoles());
		if (needed == null || needed.isEmpty())
		{
			return null;
		}
		return String.join(",", needed);
	}

	void refresh()
	{
		content.removeAll();

		Advertisement ad = partyState.getCurrentAd();
		if (ad == null)
		{
			expanded.clear();
			detailTab.clear();
			notifiedPending.clear();
			autoDeclinedBlocked.clear();
			memberEntryCache.clear();
			memberEntrySig.clear();
			joinPromptCooldown.clear();
			readyCheckCountdown = null;
			liveAdMembers = null;
			liveAdBadgeSig = "";
			lastRosterKey = null;
			lastReportedSize = -1;
			lastReportedLayout = null;
			hostApplicationHandler.setPendingApplicants(java.util.Collections.emptyList(), null);
			// Clear leftover status so a new party doesn't show the last one's message.
			setStatus("");
			content.revalidate();
			content.repaint();
			return;
		}

		boolean host = partyState.isHost();
		syncPartyMeta(ad, host);
		Activity activity = Activity.fromId(ad.getActivity());
		String activityName = (activity != null
			? activity.displayName(ad.isHardMode(), ad.getInvocation())
			: ad.getActivity()) + PartyCardPanel.coxScaleSuffix(ad);

		JLabel header = new JLabel(host
			? "Your " + activityName + " party"
			: ad.getHost() + "'s " + activityName + " party");
		header.setForeground(Color.WHITE);
		header.setFont(FontManager.getRunescapeBoldFont());
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(0, 0, 4, 0)));
		content.add(header);
		content.add(Box.createVerticalStrut(4));

		// Un-admitted applicants see only a waiting notice, not the party internals.
		if (!host && !liveParty.isLocalAdmitted())
		{
			content.add(PanelWidgets.smallLabelLeft("Waiting for the host to accept you…",
				ColorScheme.LIGHT_GRAY_COLOR));
			content.add(Box.createVerticalStrut(8));
			content.add(buildActions(ad, false));
			content.revalidate();
			content.repaint();
			return;
		}

		List<RosterMember> roster = liveParty.isInParty() ? liveParty.roster() : null;

		// Only fetch the live ad (for badges) when the roster actually changes — a member joins,
		// leaves, is admitted, or their accountHash finally resolves — not on every vitals update.
		String rosterKey = rosterKey(ad.getId(), roster);
		if (!rosterKey.equals(lastRosterKey))
		{
			lastRosterKey = rosterKey;
			refreshAdBadges();
		}

		// Applicant intake runs before any row is drawn: it rejects blocklisted applicants and admits
		// invited ones, so neither is ever painted with a live Accept button.
		if (host && roster != null)
		{
			updatePendingApplicants(roster, activity);
		}
		else
		{
			hostApplicationHandler.setPendingApplicants(java.util.Collections.emptyList(), null);
		}

		int admitted = roster == null ? 0
			: (int) roster.stream().filter(m -> m.getStatus() != PartyStatus.PENDING).count();

		if (host && admitted > 0 && admitted != lastReportedSize)
		{
			lastReportedSize = admitted;
			boardService.heartbeat(ad.getId(), admitted, currentWorld.getAsInt(), currentLayout(),
				currentNeededRolesParam(), liveParty.rosterMembers(), partyState.getHostKey(), ok -> { }, err -> { });
		}

		StringBuilder spots = new StringBuilder();
		spots.append(ad.getCapacity() > 0 ? admitted + "/" + ad.getCapacity() + " players" : admitted + " players");
		if (ad.getWorld() != null && !ad.getWorld().isEmpty())
		{
			spots.append(", W").append(ad.getWorld());
		}
		content.add(PanelWidgets.smallLabelLeft(spots.toString(), ColorScheme.LIGHT_GRAY_COLOR));

		String req = AdText.requirementText(activity, ad);
		if (req != null)
		{
			content.add(PanelWidgets.smallLabelLeft(req, ColorScheme.PROGRESS_INPROGRESS_COLOR));
		}

		// Needed roles come from the live roster, so a member who picked a role after joining
		// isn't stuck in "Needs".
		String needs = AdText.neededRolesText(activity, liveParty.neededRoles(ad.getRequiredRoles()));
		if (needs != null)
		{
			content.add(PanelWidgets.smallLabelLeft(needs, ColorScheme.BRAND_ORANGE));
		}

		List<String> tags = new ArrayList<>();
		if (ad.isLearnerRaid())
		{
			tags.add(ad.learnerLabel());
		}
		LootRule loot = LootRule.fromName(ad.getLootRule());
		if (loot != LootRule.UNSPECIFIED)
		{
			tags.add("Loot: " + loot.getDisplayName());
		}
		if (ad.isIronmanOnly())
		{
			tags.add("Ironman only");
		}
		if (ad.isPrivateAd())
		{
			tags.add("Private");
		}
		if (!tags.isEmpty())
		{
			content.add(PanelWidgets.smallLabelLeft(String.join(", ", tags), ColorScheme.LIGHT_GRAY_COLOR));
		}

		if (host && ad.getInviteCode() != null)
		{
			content.add(copyRow("Invite code: " + ad.getInviteCode(), ad.getInviteCode(),
				"Copy invite code", "Invite code copied to clipboard.", ColorScheme.LIGHT_GRAY_COLOR));
		}
		if (host && liveParty.passphrase() != null)
		{
			content.add(copyRow("Passphrase: " + liveParty.passphrase(), liveParty.passphrase(),
				"Copy passphrase", "Passphrase copied to clipboard.", ColorScheme.LIGHT_GRAY_COLOR));
		}

		JComponent voiceRow = buildVoiceRow(ad, host);
		if (voiceRow != null)
		{
			content.add(Box.createVerticalStrut(6));
			content.add(voiceRow);
		}

		// Ready check at the top (anyone can start; everyone readies up).
		if (liveParty.isInParty())
		{
			content.add(Box.createVerticalStrut(8));
			content.add(buildReadyCheck());
		}

		content.add(Box.createVerticalStrut(6));

		if (roster == null || roster.isEmpty())
		{
			content.add(PanelWidgets.smallLabelLeft("Connecting to live room…", ColorScheme.LIGHT_GRAY_COLOR));
		}
		else
		{
			// "In the friends chat" means the host's own FC, matched by host name.
			String hostName = ad.getHost();
			boolean anyPending = false;
			Set<Long> seenIds = new HashSet<>();
			for (RosterMember member : roster)
			{
				// Real synced applicants go in their own section below; ignore data-less ghosts and
				// anyone the intake above already rejected (the roster frame confirming it lags).
				if (member.getStatus() == PartyStatus.PENDING && !member.isLocal())
				{
					if (member.getData() != null && !autoDeclinedBlocked.contains(member.getMemberId()))
					{
						anyPending = true;
					}
					continue;
				}
				seenIds.add(member.getMemberId());
				content.add(memberEntry(ad, activity, member, host, hostName));
				content.add(Box.createVerticalStrut(4));
			}

			if (anyPending && host)
			{
				content.add(Box.createVerticalStrut(4));
				content.add(PanelWidgets.smallLabelLeft("Pending applicants", ColorScheme.BRAND_ORANGE));
				for (RosterMember member : roster)
				{
					if (member.getStatus() == PartyStatus.PENDING && !member.isLocal() && member.getData() != null
						&& !autoDeclinedBlocked.contains(member.getMemberId()))
					{
						seenIds.add(member.getMemberId());
						content.add(memberEntry(ad, activity, member, true, hostName));
						content.add(Box.createVerticalStrut(4));
					}
				}
			}

			// Evict rows for members who have left so the caches can't grow without bound.
			memberEntryCache.keySet().retainAll(seenIds);
			memberEntrySig.keySet().retainAll(seenIds);
			expanded.retainAll(seenIds);
			detailTab.keySet().retainAll(seenIds);
			joinPromptCooldown.keySet().retainAll(seenIds);
		}

		content.add(Box.createVerticalStrut(8));
		content.add(buildActions(ad, host));

		content.revalidate();
		content.repaint();
	}

	private void updatePendingApplicants(List<RosterMember> roster, Activity activity)
	{
		// Forget anyone no longer in the room: a member id is stable for the life of that player's
		// connection, so without this a withdraw-and-reapply would be announced only once.
		Set<Long> present = new HashSet<>();
		for (RosterMember member : roster)
		{
			present.add(member.getMemberId());
		}
		notifiedPending.retainAll(present);
		autoDeclinedBlocked.retainAll(present);

		List<Applicant> pending = new ArrayList<>();
		for (RosterMember member : roster)
		{
			if (member.getStatus() != PartyStatus.PENDING || member.getData() == null)
			{
				continue;
			}
			Applicant applicant = toApplicant(member.getData());

			// Block-list handling: warn (flag + still show), or auto-decline (once).
			boolean blocked = blockListService != null
				&& blockListService.isBlocked(applicant.getAccountHash(), applicant.getName());
			if (blocked)
			{
				BlockedApplicantAction action = config.blockedApplicantAction();
				if (action != null && action.rejects())
				{
					if (autoDeclinedBlocked.add(member.getMemberId()))
					{
						liveParty.reject(member.getMemberId());
						if (action == BlockedApplicantAction.REJECT_NOTIFY)
						{
							hostApplicationHandler.announceAutoDeclinedBlocked(applicant, activity);
						}
					}
					continue; // don't surface an auto-declined applicant
				}
				applicant.setBlocked(true); // WARN: show it, flagged
			}

			// Invited players skip host approval: auto-admit them (unless blocked or the party is full).
			if (!blocked && member.getData().isInvited()
				&& liveParty.admit(member.getMemberId(), member.getName()))
			{
				hostApplicationHandler.announceInvitedAdmitted(applicant, activity);
				continue;
			}

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

	/**
	 * A member row, reused from {@link #memberEntryCache} when nothing it renders has changed. Every
	 * refresh tears down and re-adds {@code content}; handing back the same panel instances (whose
	 * icons are already loaded) is what stops the roster flickering as live updates stream in.
	 */
	private JPanel memberEntry(Advertisement ad, Activity activity, RosterMember member, boolean host,
		String hostName)
	{
		long id = member.getMemberId();
		String sig = memberSignature(ad, activity, member, host, hostName);
		JPanel cached = memberEntryCache.get(id);
		if (cached != null && sig.equals(memberEntrySig.get(id)))
		{
			return cached;
		}
		JPanel entry = buildMemberEntry(ad, activity, member, host, hostName);
		memberEntryCache.put(id, entry);
		memberEntrySig.put(id, sig);
		return entry;
	}

	/** Everything {@link #buildMemberEntry} renders, so a matching signature means an identical row. */
	private String memberSignature(Advertisement ad, Activity activity, RosterMember member, boolean host,
		String hostName)
	{
		long id = member.getMemberId();
		PlayerUpdate data = member.getData();
		boolean isExpanded = expanded.contains(id);
		boolean fav = favoritesService != null && member.getName() != null && !member.isLocal()
			&& favoritesService.isFavorite(memberHash(member), member.getName());
		boolean blocked = blockListService != null && member.getName() != null
			&& blockListService.isBlocked(memberHash(member), member.getName());
		List<String> badges = adBadges(ad, member);
		boolean fcReady = joinPromptCooldown.getOrDefault(id, 0L) - System.currentTimeMillis() <= 0;
		// KC shows only in the expanded detail and arrives via an async lookup, so fold it in there.
		String kcSig = "";
		if (isExpanded && data != null && activity != null && data.getName() != null && data.getKillCount() < 0)
		{
			KillcountService.Killcount c = killcounts.cached(data.getName(), activity);
			kcSig = c == null ? "?" : c.killCount + "/" + c.hardModeKillCount;
		}
		return id + "|" + member.getStatus() + "|" + member.isOnline() + "|" + member.getName()
			+ "|" + member.isLocal() + "|" + isExpanded + "|" + detailTab.getOrDefault(id, TAB_SKILLS)
			+ "|" + host + "|" + currentWorld.getAsInt() + "|" + fav + "|" + blocked
			+ "|" + (runeWatch.get(member.getName()) != null)
			+ "|" + (badges == null ? "" : String.join(",", badges)) + "|" + fcReady
			+ "|" + (activity == null ? "" : activity.getId())
			+ "|" + liveParty.getLocalRole() + "|" + liveParty.isLocalLearner()
			// The host name and our own friends chat drive the row's FC icons and "Request FC" button.
			+ "|" + hostName + "|" + friendsChatOwnerSupplier.get()
			+ "|" + (data == null ? 0 : data.hashCode()) + "|" + kcSig;
	}

	private JPanel buildMemberEntry(Advertisement ad, Activity activity, RosterMember member, boolean host,
		String hostName)
	{
		PartyStatus status = member.getStatus();
		boolean isExpanded = expanded.contains(member.getMemberId());

		JPanel entry = new PanelWidgets.Capped(new BorderLayout(0, 4));
		entry.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		if (status == PartyStatus.HOST)
		{
			// Orange outline rather than a crown before the name: the crown pushed long names off the row.
			entry.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE, 1),
				BorderFactory.createEmptyBorder(5, 7, 5, 7)));
		}
		else
		{
			entry.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		}
		entry.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel stack = new JPanel();
		stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
		stack.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// ---- primary row: name ............ badges · chevron (the one disclosure trigger) ----
		JPanel topRow = new PanelWidgets.Capped(new BorderLayout(4, 0));
		topRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		topRow.setAlignmentX(Component.LEFT_ALIGNMENT);

		boolean online = member.isOnline();
		JLabel dot = new JLabel(online ? StatusIcons.ONLINE : StatusIcons.OFFLINE);
		dot.setToolTipText(online ? "Online" : "Offline");

		String tag = status == PartyStatus.PENDING ? " (pending)" : "";
		JLabel name = new JLabel(displayName(member) + tag);
		name.setForeground(status == PartyStatus.HOST ? ColorScheme.BRAND_ORANGE
			: status == PartyStatus.PENDING ? ColorScheme.PROGRESS_INPROGRESS_COLOR : Color.WHITE);
		applyAccountIcon(name, member.getData());

		JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		((FlowLayout) left.getLayout()).setAlignOnBaseline(true);
		left.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		// Nudge the name in so the account-type icon lines up with the dot on the status line below.
		left.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
		left.add(name);

		// RuneWatch warning icon, trailing the name.
		RuneWatchCase flagged = runeWatch.get(member.getName());
		if (flagged != null)
		{
			left.add(runeWatchBadge(flagged));
		}

		// Block-list warning on a pending applicant (WARN mode; auto-reject removes them instead).
		if (status == PartyStatus.PENDING && blockListService != null
			&& blockListService.isBlocked(memberHash(member), member.getName()))
		{
			JLabel blockedBadge = new JLabel(StatusIcons.BLOCK_ON);
			blockedBadge.setToolTipText("On your block list");
			left.add(blockedBadge);
		}

		JLabel chevron = new JLabel(isExpanded ? StatusIcons.CHEVRON_UP : StatusIcons.CHEVRON_DOWN);
		chevron.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		chevron.setToolTipText(isExpanded ? "Hide gear & stats" : "Show gear & stats");
		chevron.setHorizontalAlignment(SwingConstants.CENTER);
		chevron.setVerticalAlignment(SwingConstants.CENTER);
		chevron.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 2));
		chevron.addMouseListener(expandOnClick(member));

		// Same actions as the row's right-click menu; the host also gets a visible 3-dot button for them.
		JPopupMenu menu = memberMenu(activity, member, host);

		// Discord-role badges (server-asserted on the ad, matched by accountHash) sit left of the chevron.
		JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		east.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		for (DiscordBadge badge : DiscordBadge.fromWire(adBadges(ad, member)))
		{
			ImageIcon badgeIcon = BadgeIcons.get(badge);
			if (badgeIcon != null)
			{
				JLabel badgeLabel = new JLabel(badgeIcon);
				badgeLabel.setToolTipText(badge.getTooltip());
				badgeLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
				east.add(badgeLabel);
			}
		}
		east.add(chevron);
		if (host && menu != null)
		{
			JLabel kebab = PanelWidgets.kebab("Member actions", menu);
			kebab.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
			east.add(kebab);
		}

		topRow.add(left, BorderLayout.CENTER);
		topRow.add(east, BorderLayout.EAST);
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

		// Status line: the presence dot leads, left-aligned under the name above; the leading gap of 5
		// lines the dot up with the account-type icon.
		JPanel metaRow = new PanelWidgets.Capped(new FlowLayout(FlowLayout.LEFT, 5, 0));
		((FlowLayout) metaRow.getLayout()).setAlignOnBaseline(true);
		metaRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		metaRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		metaRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));
		metaRow.add(dot);
		if (!bits.isEmpty())
		{
			metaRow.add(PanelWidgets.smallLabel(String.join("  ·  ", bits), ColorScheme.LIGHT_GRAY_COLOR));
		}
		// FC presence only matters for CoX (raid formed via host's FC); hidden elsewhere.
		boolean showFc = hostName != null && data != null && activity == Activity.CHAMBERS_OF_XERIC;
		if (showFc)
		{
			if (StatusIcons.FRIENDS_CHAT != null)
			{
				JLabel fcLogo = new JLabel(StatusIcons.FRIENDS_CHAT);
				fcLogo.setToolTipText(hostName + "'s friends chat");
				metaRow.add(fcLogo);
			}
			boolean inFc = AdText.sameName(data.getFriendsChatOwner(), hostName);
			JLabel fcIcon = new JLabel(inFc ? StatusIcons.CHECK : StatusIcons.CROSS);
			fcIcon.setToolTipText(inFc
				? "In " + hostName + "'s friends chat"
				: "Not in " + hostName + "'s friends chat");
			metaRow.add(fcIcon);
		}
		// Always shown: it carries the presence dot even when there's no role/world/FC.
		stack.add(metaRow);

		// ---- vitals line: HP · prayer · spec · run energy (always shown once live) ----
		JComponent vitals = buildVitalsRow(data);
		if (vitals != null)
		{
			stack.add(vitals);
		}

		// ---- action buttons (right-aligned to the same edge as the badges/chevron/kebab above) ----
		JComponent actions = buildActionsRow(activity, member, host, hostName);
		if (actions != null)
		{
			JPanel actionRow = new PanelWidgets.Capped(new BorderLayout());
			actionRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			actionRow.setAlignmentX(Component.LEFT_ALIGNMENT);
			actionRow.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 2));
			actionRow.add(actions, BorderLayout.EAST);
			stack.add(actionRow);
		}

		entry.add(stack, BorderLayout.NORTH);

		if (isExpanded)
		{
			entry.add(buildDetail(activity, member), BorderLayout.CENTER);
		}

		// Right-click anywhere on the row opens the same actions (kept alongside the host's 3-dot button).
		if (menu != null)
		{
			entry.setComponentPopupMenu(menu);
			PanelWidgets.inheritPopupMenu(entry);
		}

		return entry;
	}

	/**
	 * Right-click actions for a roster member: favourite and block toggles, and (host only, on an
	 * admitted member) kick / kick-and-block. {@code null} when nothing applies.
	 */
	private JPopupMenu memberMenu(Activity activity, RosterMember member, boolean host)
	{
		if (member.getName() == null)
		{
			return null;
		}
		final String rsn = member.getName();
		final long hash = memberHash(member);
		// Favouriting/blocking/kicking yourself makes no sense, so your own row has no menu at all.
		final boolean self = member.isLocal();
		JPopupMenu menu = new JPopupMenu();
		boolean any = false;

		if (!self && favoritesService != null)
		{
			boolean fav = favoritesService.isFavorite(hash, rsn);
			JMenuItem favItem = new JMenuItem(fav ? "Remove from Favorites" : "Add to Favorites");
			favItem.addActionListener(e -> {
				favoritesService.toggle(hash, rsn);
				refresh();
			});
			menu.add(favItem);
			any = true;
		}

		if (!self && blockListService != null)
		{
			boolean blocked = blockListService.isBlocked(hash, rsn);
			JMenuItem blockItem = new JMenuItem(blocked ? "Remove from blocklist" : "Add to blocklist");
			blockItem.addActionListener(e -> {
				if (BlockConfirm.toggle(this, blockListService, favoritesService, hash, rsn))
				{
					refresh();
				}
			});
			menu.add(blockItem);
			any = true;
		}

		if (!self && host && member.getStatus() == PartyStatus.MEMBER)
		{
			menu.addSeparator();
			JMenuItem kickItem = new JMenuItem("Kick player");
			kickItem.addActionListener(e -> kick(activity, member));
			menu.add(kickItem);
			JMenuItem kickBlockItem = new JMenuItem("Kick and block player");
			kickBlockItem.addActionListener(e -> kickAndBlock(activity, member, hash, rsn));
			menu.add(kickBlockItem);
			any = true;
		}

		return any ? menu : null;
	}

	/** Kick a member and add them to the block list (host only), confirming the block first. */
	private void kickAndBlock(Activity activity, RosterMember member, long hash, String rsn)
	{
		if (blockListService.isBlocked(hash, rsn))
		{
			kick(activity, member); // refreshes and sets the status line
		}
		else if (BlockConfirm.toggle(this, blockListService, favoritesService, hash, rsn))
		{
			kick(activity, member);
		}
	}

	/** The member's name, or a placeholder while the live room still reports it as unresolved. */
	private static String displayName(RosterMember member)
	{
		String name = member.getName();
		return name == null || name.trim().isEmpty() || "<unknown>".equalsIgnoreCase(name.trim())
			? "Joining…" : name;
	}

	/** The member's self-reported accountHash, or {@code 0} until they've synced. */
	private static long memberHash(RosterMember member)
	{
		return member.getData() != null ? member.getData().getAccountHash() : 0L;
	}

	/**
	 * Discord-role badges the API asserted for this member on the ad, or {@code null} when hidden or
	 * none. Prefers the freshly-polled ad ({@link #liveAdMembers}) so later joiners' badges appear;
	 * {@code currentAd} is a join-time snapshot that only ever reliably carries the host's badge.
	 */
	private List<String> adBadges(Advertisement ad, RosterMember member)
	{
		if (config != null && !config.showDiscordBadges())
		{
			return null;
		}
		long hash = memberHash(member);
		String name = member.getName();
		List<String> live = AdText.badgesFor(liveAdMembers, hash, name);
		if (live != null)
		{
			return live;
		}
		return ad == null ? null : AdText.badgesFor(ad.getMembers(), hash, name);
	}

	/** Membership key over (partyId, each member's id·status·accountHash); changes on join/leave/admit/hash-resolve. */
	private static String rosterKey(String partyId, List<RosterMember> roster)
	{
		StringBuilder sb = new StringBuilder(partyId == null ? "" : partyId).append('|');
		if (roster != null)
		{
			for (RosterMember m : roster)
			{
				sb.append(m.getMemberId()).append(':').append(m.getStatus()).append(':')
					.append(memberHash(m)).append(';');
			}
		}
		return sb.toString();
	}

	/**
	 * Keep the ad in step with the live room: the host publishes its settings, everyone else adopts them.
	 * Our {@link Advertisement} is a never-refetched join-time copy, so without this a member would keep
	 * rendering the party (and after a transfer, the host name) as it was when they joined.
	 */
	private void syncPartyMeta(Advertisement ad, boolean host)
	{
		if (!liveParty.isInParty())
		{
			return;
		}
		if (host)
		{
			liveParty.setPartyMeta(PartyMeta.from(ad));
			return;
		}
		PartyMeta meta = liveParty.partyMeta();
		if (meta != null)
		{
			// Mutated in place rather than through partyState: the object identity is unchanged, and this
			// runs inside the render it feeds, so re-firing the state listener would only re-enter refresh().
			meta.applyTo(ad);
		}
	}

	/** Fetch the current party's live ad and, if its badges changed, adopt them and re-render. */
	private void refreshAdBadges()
	{
		Advertisement ad = partyState.getCurrentAd();
		if (ad == null || ad.getHost() == null
			|| (config != null && !config.showDiscordBadges()))
		{
			return;
		}
		boardService.fetchAdByHost(ad.getHost(),
			fresh -> SwingUtilities.invokeLater(() -> onAdBadgesFetched(fresh)),
			err -> { /* no ad for this host right now — keep the last known badges */ });
	}

	private void onAdBadgesFetched(Advertisement fresh)
	{
		// Ignore a response that arrived after we left the party.
		if (fresh == null || !partyState.isInParty())
		{
			return;
		}
		String sig = badgeSignature(fresh.getMembers());
		if (sig.equals(liveAdBadgeSig))
		{
			return; // unchanged — don't churn the roster
		}
		liveAdBadgeSig = sig;
		liveAdMembers = fresh.getMembers();
		refresh();
	}

	/** A stable string over each ad member's (hash, name, badges), for change detection. */
	private static String badgeSignature(List<Member> members)
	{
		if (members == null || members.isEmpty())
		{
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (Member m : members)
		{
			if (m == null)
			{
				continue;
			}
			sb.append(m.getAccountHash()).append(':').append(m.getName()).append('=')
				.append(m.getBadges() == null ? "" : String.join(",", m.getBadges())).append(';');
		}
		return sb.toString();
	}

	private MouseAdapter expandOnClick(RosterMember member)
	{
		return new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				// Left-click toggles; let right-click fall through to the row's popup menu.
				if (!SwingUtilities.isLeftMouseButton(e))
				{
					return;
				}
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
		if (host && member.getStatus() == PartyStatus.PENDING)
		{
			JButton admit = smallButton("Accept");
			admit.addActionListener(e -> admit(activity, member));
			JButton decline = smallButton("Decline");
			decline.addActionListener(e -> decline(activity, member));
			wrap.add(admit);
			wrap.add(decline);
			any = true;
		}
		PlayerUpdate data = member.getData();

		// Per-activity join prompt: CoX = host's FC, ToB = notice board, ToA = Grouping Obelisk.
		// Never for a pending applicant — they aren't in the party yet, so there's nothing to join.
		if (host && data != null && activity != null && member.getStatus() != PartyStatus.PENDING)
		{
			String kind = null;
			String label = null;
			String tip = null;
			if (activity == Activity.CHAMBERS_OF_XERIC)
			{
				// Only when the host has an FC open and the member isn't already in it.
				if (hostName != null && !AdText.sameName(data.getFriendsChatOwner(), hostName)
					&& AdText.sameName(friendsChatOwnerSupplier.get(), hostName))
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
				long remaining = joinPromptCooldown.getOrDefault(member.getMemberId(), 0L) - System.currentTimeMillis();
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

	private static final Dimension ORB_ICON = new Dimension(14, 14);

	/**
	 * A compact vitals line (HP, prayer, spec, run energy) behind orb icons; {@code null} until
	 * the first live snapshot. Fixed 4-column grid, not FlowLayout, so run energy never wraps.
	 */
	private JComponent buildVitalsRow(PlayerUpdate data)
	{
		if (data == null || data.getCurrentHp() < 0)
		{
			return null;
		}
		JPanel grid = new JPanel(new GridLayout(1, 4, 1, 0));
		grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		grid.add(vitalCell(SpriteID.MINIMAP_ORB_HITPOINTS_ICON, Integer.toString(data.getCurrentHp()),
			"Hitpoints " + data.getCurrentHp() + "/" + data.getMaxHp()));
		grid.add(vitalCell(SpriteID.MINIMAP_ORB_PRAYER_ICON, Integer.toString(data.getCurrentPrayer()),
			"Prayer " + data.getCurrentPrayer() + "/" + data.getMaxPrayer()));
		grid.add(vitalCell(SpriteID.MINIMAP_ORB_SPECIAL_ICON, data.getSpecialPercent() + "%",
			"Special attack energy"));
		grid.add(vitalCell(SpriteID.MINIMAP_ORB_RUN_ICON, data.getRunEnergy() + "%",
			"Run energy"));

		JPanel row = new PanelWidgets.Capped(new BorderLayout(2, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 0));
		row.add(grid, BorderLayout.CENTER);
		// Spellbook symbol trailing the run-energy orb (icon only, no value).
		JComponent spellbook = spellbookIcon(data.getSpellbook());
		if (spellbook != null)
		{
			row.add(spellbook, BorderLayout.EAST);
		}
		return row;
	}

	/** @return an icon of the member's active spellbook, or {@code null} when unknown. */
	private JComponent spellbookIcon(int spellbook)
	{
		int spriteId;
		String name;
		switch (spellbook)
		{
			case 0:
				spriteId = SpriteID.TAB_MAGIC;
				name = "Standard spellbook";
				break;
			case 1:
				spriteId = SpriteID.TAB_MAGIC_SPELLBOOK_ANCIENT_MAGICKS;
				name = "Ancient Magicks";
				break;
			case 2:
				spriteId = SpriteID.TAB_MAGIC_SPELLBOOK_LUNAR;
				name = "Lunar spellbook";
				break;
			case 3:
				spriteId = SpriteID.TAB_MAGIC_SPELLBOOK_ARCEUUS;
				name = "Arceuus spellbook";
				break;
			default:
				return null;
		}
		JLabel icon = new JLabel();
		icon.setPreferredSize(ORB_ICON);
		icon.setToolTipText(name);
		loadOrbIcon(icon, spriteId);
		return icon;
	}

	private JComponent vitalCell(int spriteId, String value, String tip)
	{
		// BorderLayout so the value sits beside the icon and never wraps beneath it.
		JPanel cell = new JPanel(new BorderLayout(1, 0));
		cell.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JLabel icon = new JLabel();
		icon.setPreferredSize(ORB_ICON);
		icon.setToolTipText(tip);
		loadOrbIcon(icon, spriteId);
		JLabel val = new JLabel(value);
		val.setForeground(Color.WHITE);
		val.setFont(FontManager.getRunescapeSmallFont());
		val.setToolTipText(tip);
		cell.add(icon, BorderLayout.WEST);
		cell.add(val, BorderLayout.CENTER);
		return cell;
	}

	/** Load an orb sprite scaled to fit {@link #ORB_ICON} so wider orbs don't overflow the label. */
	private void loadOrbIcon(JLabel label, int spriteId)
	{
		// Memoised: once resolved, a rebuilt/reused vitals row sets its orb synchronously (no blank flash).
		ImageIcon ready = orbIconCache.get(spriteId);
		if (ready != null)
		{
			label.setIcon(ready);
			return;
		}
		if (spriteManager == null)
		{
			return;
		}
		spriteManager.getSpriteAsync(spriteId, 0, img -> {
			if (img == null)
			{
				return;
			}
			double scale = Math.min((double) ORB_ICON.width / img.getWidth(),
				(double) ORB_ICON.height / img.getHeight());
			int w = Math.max(1, (int) Math.round(img.getWidth() * scale));
			int h = Math.max(1, (int) Math.round(img.getHeight() * scale));
			BufferedImage scaled = ImageUtil.resizeImage(img, w, h);
			SwingUtilities.invokeLater(() -> {
				ImageIcon icon = new ImageIcon(scaled);
				orbIconCache.put(spriteId, icon);
				label.setIcon(icon);
				label.repaint();
			});
		});
	}

	private JComponent buildDetail(Activity activity, RosterMember member)
	{
		PlayerUpdate data = member.getData();
		if (data == null)
		{
			JPanel waiting = new JPanel(new BorderLayout());
			waiting.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			waiting.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
			waiting.add(PanelWidgets.smallLabel("Waiting for live data…", ColorScheme.LIGHT_GRAY_COLOR),
				BorderLayout.CENTER);
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
		boolean active = tab == selected;
		Color baseBg = active ? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.DARK_GRAY_COLOR;
		Color border = active ? ColorScheme.BRAND_ORANGE : ColorScheme.MEDIUM_GRAY_COLOR;

		JButton button = new JButton(text);
		button.setFocusPainted(false);
		button.setMargin(new Insets(2, 4, 2, 4));
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setForeground(active ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
		button.setBackground(baseBg);
		button.setOpaque(true);
		button.setContentAreaFilled(true);
		button.setCursor(new Cursor(Cursor.HAND_CURSOR));
		button.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(border, 1),
			BorderFactory.createEmptyBorder(2, 4, 2, 4)));
		button.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				button.setBackground(baseBg.brighter());
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				button.setBackground(baseBg);
			}
		});
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

		detail.add(PanelWidgets.smallLabel("Combat", ColorScheme.LIGHT_GRAY_COLOR));
		detail.add(PanelWidgets.smallLabel(String.valueOf(stats.getCombatLevel()), Color.WHITE));

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
			detail.add(PanelWidgets.smallLabel(activityName + " KC", ColorScheme.LIGHT_GRAY_COLOR));
			detail.add(PanelWidgets.smallLabel(String.valueOf(kc), Color.WHITE));

			if (activity != null && activity.hasHardMode() && hardKc >= 0)
			{
				detail.add(PanelWidgets.smallLabel(activity.getHardModeLabel() + " KC", ColorScheme.LIGHT_GRAY_COLOR));
				detail.add(PanelWidgets.smallLabel(String.valueOf(hardKc), Color.WHITE));
			}
		}
		else if (lookingUp)
		{
			detail.add(PanelWidgets.smallLabel(activityName + " KC", ColorScheme.LIGHT_GRAY_COLOR));
			detail.add(PanelWidgets.smallLabel("looking up…", Color.WHITE));
		}

		// Personal best (broadcast by the applicant's own client) for timed activities.
		if (activity != null && PersonalBests.isPbActivity(activity.getId()))
		{
			detail.add(PanelWidgets.smallLabel(activityName + " PB", ColorScheme.LIGHT_GRAY_COLOR));
			detail.add(PanelWidgets.smallLabel(stats.getPbSeconds() >= 0
				? PersonalBests.format(stats.getPbSeconds()) : "n/a", Color.WHITE));
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
			empty.add(PanelWidgets.smallLabel("No gear data.", ColorScheme.LIGHT_GRAY_COLOR),
				BorderLayout.CENTER);
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
			empty.add(PanelWidgets.smallLabel("No inventory data.", ColorScheme.LIGHT_GRAY_COLOR),
				BorderLayout.CENTER);
			return empty;
		}

		int[] qty = stats.getInventoryQuantities();
		int[] pouchRunes = stats.getRunePouch();
		int[] pouchAmounts = stats.getRunePouchAmounts();
		boolean hasPouch = pouchRunes != null && pouchRunes.length > 0;
		JPanel grid = new JPanel(new GridLayout(7, 4, 2, 2));
		grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		for (int i = 0; i < 28; i++)
		{
			int id = i < inv.length ? inv[i] : -1;
			int count = qty != null && i < qty.length ? qty[i] : 1;
			if (hasPouch && id > 0 && isRunePouch(id))
			{
				// Overlay the pouch's runes on the pouch slot, like RuneLite's Rune Pouch plugin.
				grid.add(runePouchSlot(id, pouchRunes, pouchAmounts, stats.getRunePouchNames()));
			}
			else
			{
				grid.add(itemSlot(id, count));
			}
		}
		return center(grid);
	}

	private static boolean isRunePouch(int itemId)
	{
		return itemId == ItemID.BH_RUNE_POUCH || itemId == ItemID.BH_RUNE_POUCH_TROUVER
			|| itemId == ItemID.DIVINE_RUNE_POUCH || itemId == ItemID.DIVINE_RUNE_POUCH_TROUVER;
	}

	/** Pixel size of a rune icon painted over the pouch, matching RuneLite's overlay. */
	private static final int RUNE_ICON = 11;

	/** A rune-pouch inventory slot: pouch icon with runes overlaid, like RuneLite's Rune Pouch plugin. */
	private JComponent runePouchSlot(int pouchItemId, int[] runes, int[] amounts, String[] names)
	{
		AsyncBufferedImage pouchImg = itemManager.getImage(pouchItemId);
		AsyncBufferedImage[] runeImgs = new AsyncBufferedImage[runes.length];
		for (int i = 0; i < runes.length; i++)
		{
			runeImgs[i] = itemManager.getImage(runes[i]);
		}

		JComponent slot = new JComponent()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				super.paintComponent(g);
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setColor(ColorScheme.DARK_GRAY_COLOR);
				g2.fillRect(0, 0, getWidth(), getHeight());
				if (pouchImg != null)
				{
					g2.drawImage(pouchImg, (getWidth() - pouchImg.getWidth()) / 2,
						(getHeight() - pouchImg.getHeight()) / 2, null);
				}
				g2.setFont(FontManager.getRunescapeSmallFont());
				if (runeImgs.length < 4)
				{
					paintRuneList(g2, runeImgs, amounts);
				}
				else
				{
					paintRuneGrid(g2, runeImgs, amounts);
				}
				g2.dispose();
			}
		};
		slot.setPreferredSize(SLOT_SIZE);
		slot.setMinimumSize(SLOT_SIZE);
		slot.setOpaque(true);
		slot.setBackground(ColorScheme.DARK_GRAY_COLOR);
		slot.setToolTipText(runePouchTooltip(names, amounts));

		Runnable repaint = () -> SwingUtilities.invokeLater(slot::repaint);
		pouchImg.onLoaded(repaint);
		for (AsyncBufferedImage img : runeImgs)
		{
			img.onLoaded(repaint);
		}
		return slot;
	}

	/** &lt;4 runes: a small icon per rune with its amount text beside it (RuneLite's list mode). */
	private void paintRuneList(Graphics2D g, AsyncBufferedImage[] imgs, int[] amounts)
	{
		FontMetrics fm = g.getFontMetrics();
		for (int i = 0; i < imgs.length; i++)
		{
			int y = fm.getHeight() * i - 1;
			if (imgs[i] != null)
			{
				g.drawImage(imgs[i], -1, y, RUNE_ICON, RUNE_ICON, null);
			}
			String text = formatRuneAmount(amounts[i]);
			int textY = 12 + (fm.getHeight() - 1) * i;
			g.setColor(Color.BLACK);
			g.drawString(text, 12, textY + 1);
			g.setColor(Color.YELLOW);
			g.drawString(text, 11, textY);
		}
	}

	/** &gt;=4 runes: a 2-column grid of icons, each with a coloured fill bar for its amount. */
	private void paintRuneGrid(Graphics2D g, AsyncBufferedImage[] imgs, int[] amounts)
	{
		int num = imgs.length;
		for (int c = 0; c < num; c++)
		{
			int iconX = 2 + (c % 2 == 1 ? RUNE_ICON + 4 : 0);
			int iconY = num > 4
				? -1 + (c / 2) * RUNE_ICON
				: 5 + (c >= 2 ? RUNE_ICON + 2 : 0);
			if (imgs[c] != null)
			{
				g.drawImage(imgs[c], iconX, iconY, RUNE_ICON, RUNE_ICON, null);
			}
			int amount = amounts[c];
			int height;
			Color color;
			if (amount < 1000)
			{
				height = amount / 100;
				color = Color.RED;
			}
			else
			{
				height = Math.min(10, amount / 1000);
				color = Color.GREEN;
			}
			g.setColor(color);
			g.fillRect(iconX + RUNE_ICON, iconY + 1 + (10 - height), 2, height);
		}
	}

	private static String formatRuneAmount(int amount)
	{
		return amount < 1000 ? String.valueOf(amount) : amount / 1000 + "K";
	}

	private static String runePouchTooltip(String[] names, int[] amounts)
	{
		if (names == null || names.length == 0)
		{
			return null;
		}
		StringBuilder sb = new StringBuilder("<html>");
		for (int i = 0; i < names.length; i++)
		{
			int amount = amounts != null && i < amounts.length ? amounts[i] : 0;
			sb.append(amount).append(' ').append(names[i]).append("<br>");
		}
		return sb.append("</html>").toString();
	}

	private JLabel itemSlot(int itemId)
	{
		return itemSlot(itemId, 1);
	}

	/** A single item cell; draws the stack count when {@code quantity > 1}. */
	private JLabel itemSlot(int itemId, int quantity)
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
			// Async load; three-arg form stamps the stack-size for quantities above one.
			int q = Math.max(1, quantity);
			itemManager.getImage(itemId, q, q > 1).addTo(slot);
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
		// From the roster, not the snapshot: the snapshot stopped carrying an account hash because it is
		// relayed to everyone attached to the party. The host still sees the whole roster, so the applicant
		// it is deciding about can still be matched against the block list by account rather than by name.
		applicant.setAccountHash(liveParty.accountHashForMember(update.getMemberId()));
		applicant.setCombatLevel(update.getCombatLevel());
		applicant.setStats(update.getStats());
		applicant.setEquipment(update.getEquipment());
		applicant.setInventory(update.getInventory());
		applicant.setInventoryQuantities(update.getInventoryQuantities());
		applicant.setRunePouch(update.getRunePouch());
		applicant.setRunePouchAmounts(update.getRunePouchAmounts());
		applicant.setRunePouchNames(update.getRunePouchNames());
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
			setStatus("Party is full - can't accept " + member.getName() + ".");
			return;
		}
		notifiedPending.remove(member.getMemberId());
		if (activity != null && member.getData() != null)
		{
			hostApplicationHandler.announceResolved(toApplicant(member.getData()), activity, true);
		}
		setStatus("Accepted " + member.getName() + ".");
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
		// Also boot the kicked member from the Discord voice channel (backend no-ops if not applicable).
		Advertisement ad = partyState.getCurrentAd();
		if (partyState.isHost() && ad != null && liveParty.discordInviteUrl() != null)
		{
			long accountHash = liveParty.accountHashForMember(member.getMemberId());
			if (accountHash != 0)
			{
				boardService.kickVoiceMember(ad.getId(), partyState.getHostKey(), accountHash);
			}
		}
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
		joinPromptCooldown.put(member.getMemberId(), System.currentTimeMillis() + JOIN_PROMPT_COOLDOWN_MS);
		Timer reEnable = new Timer((int) JOIN_PROMPT_COOLDOWN_MS, e -> refresh());
		reEnable.setRepeats(false);
		reEnable.start();
		refresh();
	}

	private JComponent buildReadyCheck()
	{
		ReadyCheckStatus status = liveParty.readyCheck();
		JPanel row = new PanelWidgets.Capped(new BorderLayout());
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		readyCheckCountdown = null;
		if (status == null)
		{
			readyCheckTicker.stop();
			JButton start = new JButton("Start ready check");
			start.setFocusPainted(false);
			// Nobody in a raid is answering one, ourselves included: the check belongs to the lobby.
			if (liveParty.insideRaid())
			{
				start.setEnabled(false);
				start.setToolTipText("Ready checks are off while you're inside the raid.");
			}
			// Starting counts you as ready, so it's world-gated exactly like readying up.
			else if (liveParty.onDifferentWorldThanHost())
			{
				start.setEnabled(false);
				int hostWorld = liveParty.hostWorld();
				start.setToolTipText("Hop to the host's world"
					+ (hostWorld > 0 ? " (W" + hostWorld + ")" : "") + " to start a ready check.");
			}
			else
			{
				start.addActionListener(e -> {
					liveParty.startReadyCheck();
					setStatus("Ready check started.");
					refresh();
				});
			}
			row.add(start, BorderLayout.CENTER);
			return row;
		}

		String counts = status.getReady() + "/" + status.getTotal();
		if (!status.isLocalReady())
		{
			JButton ready = new JButton("Ready up (" + counts + ")");
			ready.setFocusPainted(false);
			// Readying up implies being where the party is: greyed out until you're on the host's world.
			if (liveParty.onDifferentWorldThanHost())
			{
				ready.setEnabled(false);
				int hostWorld = liveParty.hostWorld();
				ready.setToolTipText("Hop to the host's world"
					+ (hostWorld > 0 ? " (W" + hostWorld + ")" : "") + " to ready up.");
			}
			else
			{
				ready.addActionListener(e -> {
					liveParty.markReady();
					refresh();
				});
			}
			row.add(ready, BorderLayout.CENTER);
		}
		else
		{
			JLabel waiting = new JLabel(readyCheckText(status));
			waiting.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
			waiting.setFont(FontManager.getRunescapeSmallFont());
			waiting.setHorizontalAlignment(SwingConstants.CENTER);
			row.add(waiting, BorderLayout.CENTER);
			readyCheckCountdown = waiting;
		}
		readyCheckTicker.start();
		return row;
	}

	private static String readyCheckText(ReadyCheckStatus status)
	{
		return "Ready " + status.getReady() + "/" + status.getTotal()
			+ " - " + status.getSecondsLeft() + "s left";
	}

	/** Retexts the countdown between refreshes; a full rebuild only when the check ends. */
	private void tickReadyCheck()
	{
		ReadyCheckStatus status = liveParty.readyCheck();
		if (status == null)
		{
			readyCheckTicker.stop();
			refresh();
			return;
		}
		if (readyCheckCountdown != null)
		{
			readyCheckCountdown.setText(readyCheckText(status));
		}
	}

	private JPanel buildActions(Advertisement ad, boolean host)
	{
		JPanel actions = new PanelWidgets.Capped(new BorderLayout(0, 4));
		actions.setBackground(ColorScheme.DARK_GRAY_COLOR);
		actions.setAlignmentX(Component.LEFT_ALIGNMENT);

		// Host-only buttons above disband/leave: edit, and transfer host when there's a candidate.
		if (host)
		{
			JPanel hostButtons = new JPanel(new GridLayout(0, 1, 0, 4));
			hostButtons.setBackground(ColorScheme.DARK_GRAY_COLOR);
			if (onEditParty != null)
			{
				JButton edit = new JButton("Edit party");
				edit.setFocusPainted(false);
				edit.addActionListener(e -> onEditParty.run());
				hostButtons.add(edit);
			}
			if (hostTransferHandler != null && !transferCandidates().isEmpty())
			{
				JButton transfer = new JButton("Transfer host");
				transfer.setFocusPainted(false);
				transfer.addActionListener(e -> promptTransferHost(true));
				hostButtons.add(transfer);
			}
			if (hostButtons.getComponentCount() > 0)
			{
				actions.add(hostButtons, BorderLayout.NORTH);
			}
		}

		JButton button = new JButton(host ? "Disband party" : "Leave party");
		button.setFocusPainted(false);
		button.addActionListener(e -> {
			if (host)
			{
				confirmDisband(ad, button);
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

	/** Confirm before disbanding a hosted ad, with a persisted "Don't ask again" option. */
	private void confirmDisband(Advertisement ad, JButton button)
	{
		if (config != null && config.skipDisbandConfirm())
		{
			disband(ad, button);
			return;
		}
		// With other members still present, offer to hand the party over instead of destroying it.
		if (hostTransferHandler != null && !transferCandidates().isEmpty())
		{
			Object[] options = {"Transfer to a member & leave", "Disband for everyone", "Cancel"};
			int choice = JOptionPane.showOptionDialog(this,
				"Other members are still in this party. Transfer it to a member, or disband it for everyone?",
				"Disband party", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
			if (choice == 0)
			{
				promptTransferHost(false);
			}
			else if (choice == 1)
			{
				disband(ad, button);
			}
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
		disband(ad, button);
	}

	/** The admitted, online members (excluding us) the host could hand the party to. */
	private List<RosterMember> transferCandidates()
	{
		List<RosterMember> out = new ArrayList<>();
		for (RosterMember member : liveParty.roster())
		{
			if (member.getStatus() == PartyStatus.MEMBER && member.isOnline() && !member.isLocal())
			{
				out.add(member);
			}
		}
		return out;
	}

	/**
	 * Ask which member to hand the party to, then start the transfer. {@code hostStays} true keeps
	 * the old host as a member (Transfer button), false makes them leave (disband path).
	 */
	private void promptTransferHost(boolean hostStays)
	{
		List<RosterMember> candidates = transferCandidates();
		if (candidates.isEmpty())
		{
			return;
		}
		String tail = hostStays ? " and you'll stay in the party." : " and you'll leave the party.";
		RosterMember target;
		if (candidates.size() == 1)
		{
			target = candidates.get(0);
			int ok = JOptionPane.showConfirmDialog(this,
				"Make " + target.getName() + " the host" + tail, "Transfer host",
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (ok != JOptionPane.OK_OPTION)
			{
				return;
			}
		}
		else
		{
			String[] names = candidates.stream().map(RosterMember::getName).toArray(String[]::new);
			JComboBox<String> combo = new JComboBox<>(names);
			JPanel msg = new JPanel(new BorderLayout(0, 6));
			msg.add(new JLabel("Make which member the host?" + tail), BorderLayout.NORTH);
			msg.add(combo, BorderLayout.CENTER);
			int ok = JOptionPane.showConfirmDialog(this, msg, "Transfer host",
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (ok != JOptionPane.OK_OPTION || combo.getSelectedIndex() < 0)
			{
				return;
			}
			target = candidates.get(combo.getSelectedIndex());
		}
		hostTransferHandler.offerTransfer(target.getMemberId(), hostStays);
	}

	/**
	 * Fallback for the host: look our own ad up by host name every heartbeat. Catches a
	 * server-side removal even when no {@code gone} frame reaches us. Only trusted while
	 * the socket is connected — a lookup while disconnected returns null for every host.
	 */
	private void verifyAdStillExists()
	{
		Advertisement ad = partyState.getCurrentAd();
		if (ad == null || ad.getHost() == null || !boardService.isApiConnected())
		{
			return;
		}
		String id = ad.getId();
		boardService.fetchAdByHost(ad.getHost(),
			fresh -> log.debug("Hosted ad {} still advertised", id),
			err -> SwingUtilities.invokeLater(() -> {
				if (boardService.isApiConnected())
				{
					log.info("Hosted ad {} no longer advertised on the server; folding the Party tab", id);
					onHostedAdGone(id);
				}
			}));
	}

	/** The server no longer has our hosted ad: leave the live room and clear the tab. */
	private void onHostedAdGone(String adId)
	{
		Advertisement ad = partyState.getCurrentAd();
		if (ad == null || !ad.getId().equals(adId) || !partyState.isHost())
		{
			return; // already left, handed the party away, or a different party by now
		}
		log.info("Hosted ad {} gone server-side: leaving live room and clearing the tab", adId);
		liveParty.leave();
		partyState.clear();
		setStatus("Your party was removed on the server.");
	}

	private void disband(Advertisement ad, JButton button)
	{
		button.setEnabled(false);
		setStatus("Disbanding party…");
		// Remove the ad and close the room; read the host key before clear() wipes it.
		boardService.removeAd(ad.getId(), ad.getHost(), partyState.getHostKey(), ignored -> { }, error -> { });
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

	private String requirementText(Activity activity, Advertisement ad)
	{
		if (ad.getMinKillCount() <= 0 && ad.getMinHardModeKillCount() <= 0)
		{
			return null;
		}
		StringBuilder req = new StringBuilder("Req: ");
		boolean any = false;
		if (ad.getMinKillCount() > 0)
		{
			req.append(ad.getMinKillCount()).append(" KC");
			any = true;
		}
		if (activity != null && activity.hasHardMode() && ad.getMinHardModeKillCount() > 0)
		{
			if (any)
			{
				req.append(", ");
			}
			req.append(ad.getMinHardModeKillCount()).append(' ').append(activity.getHardModeLabel()).append(" KC");
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

	/**
	 * Discord voice controls: "Join voice" once a channel exists, else host-only "Create voice
	 * channel"; {@code null} for a member with no channel yet.
	 */
	private JComponent buildVoiceRow(Advertisement ad, boolean host)
	{
		boolean linked = discordLinkedSupplier != null && discordLinkedSupplier.getAsBoolean();
		String url = liveParty.discordInviteUrl();

		// Voice needs a linked Discord account; show an Authorize button to run OAuth first.
		if (url != null)
		{
			if (!linked)
			{
				return authorizeRow();
			}
			JButton join = voiceButton("Join voice", "Open the party's Discord voice channel");
			join.addActionListener(e -> joinVoice(ad, url));
			return wrapVoiceButton(join);
		}
		if (!host)
		{
			return null; // members wait for the host to create the channel
		}
		if (!linked)
		{
			return authorizeRow();
		}
		JButton create = voiceButton("Create voice channel", "Create a Discord voice channel for this party");
		create.addActionListener(e -> {
			String adId = ad.getId();
			if (adId == null)
			{
				return;
			}
			create.setEnabled(false);
			create.setText("Creating channel…");
			setStatus("Creating Discord voice channel…");
			boardService.createVoiceChannel(adId, partyState.getHostKey(),
				channelUrl -> SwingUtilities.invokeLater(() -> {
					// Record and re-broadcast so members get a "Join voice" button.
					liveParty.setDiscordInviteUrl(channelUrl);
					setStatus("Voice channel created. Members can now join.");
				}),
				err -> SwingUtilities.invokeLater(() -> {
					create.setEnabled(true);
					create.setText("Create voice channel");
					setStatus("Couldn't create a voice channel. Please try again.");
				}));
		});
		return wrapVoiceButton(create);
	}

	private JButton voiceButton(String text, String tooltip)
	{
		JButton button = new JButton(text);
		button.setFocusPainted(false);
		button.setForeground(Color.WHITE);
		button.setBackground(DISCORD_BLURPLE);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setCursor(new Cursor(Cursor.HAND_CURSOR));
		button.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		button.setToolTipText(tooltip);
		if (DISCORD_ICON != null)
		{
			button.setIcon(DISCORD_ICON);
			button.setIconTextGap(6);
		}
		return button;
	}

	/** Request per-user voice access, then open the invite (falls back to just opening it). */
	private void joinVoice(Advertisement ad, String url)
	{
		long accountHash = accountHashSupplier != null ? accountHashSupplier.getAsLong() : 0;
		if (accountHash == 0 || accountHash == -1 || ad == null)
		{
			LinkBrowser.browse(url);
			return;
		}
		boardService.requestVoiceAccess(ad.getId(), accountHash,
			() -> SwingUtilities.invokeLater(() -> LinkBrowser.browse(url)),
			err -> SwingUtilities.invokeLater(() -> LinkBrowser.browse(url)));
	}

	/** The "authorize first" button shown in place of create/join when the local account isn't linked. */
	private JComponent authorizeRow()
	{
		JButton authorize = voiceButton("Authorize with Discord",
			"Link your Discord account to create or join party voice channels");
		authorize.addActionListener(e ->
		{
			if (onAuthorizeDiscord != null)
			{
				onAuthorizeDiscord.run();
			}
		});
		return wrapVoiceButton(authorize);
	}

	/** Full-width in a capped row (BorderLayout.CENTER stretches the button), matching "Start ready check". */
	private JPanel wrapVoiceButton(JButton button)
	{
		JPanel row = new PanelWidgets.Capped(new BorderLayout());
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.add(button, BorderLayout.CENTER);
		return row;
	}

	private JPanel copyRow(String labelText, String copyValue, String tooltip, String statusMsg, Color fg)
	{
		// BorderLayout keeps the copy button pinned right; long values truncate, not push it off.
		JPanel row = new PanelWidgets.Capped(new BorderLayout(4, 0));
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
			tip.append(", evidence rating ").append(escape(flagged.getRating()));
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

	private void setStatus(String text)
	{
		statusLabel.setText(text);
	}
}
