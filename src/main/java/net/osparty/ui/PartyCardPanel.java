package net.osparty.ui;

import net.osparty.service.FavoritesService;
import net.osparty.service.KillcountService;
import net.osparty.api.BoardService;
import net.osparty.model.AccountTypes;
import net.osparty.model.Activity;
import net.osparty.model.LootRule;
import net.osparty.model.Advertisement;
import net.osparty.model.Role;
import net.osparty.party.LivePartyBackend;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.text.DefaultCaret;

import net.osparty.service.BlockListService;
import net.osparty.tools.WorldPinger;
import net.runelite.api.vars.AccountType;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.http.api.worlds.WorldRegion;

/** Abstract base for party-card panels (Search and Faves): apply/cancel, cooldowns, {@link #buildPartyCard}. */
abstract class PartyCardPanel extends JPanel
{
	protected static final long COOLDOWN_MS = 30_000;
	/** Ads still searching past this many minutes are dimmed and flagged as stale. */
	protected static final long STALE_MINUTES = 60;

	// ---- shared dependencies -----------------------------------------------
	protected final BoardService boardService;
	protected final Supplier<String> playerNameSupplier;
	protected final PartyState partyState;
	protected final LivePartyBackend liveParty;
	protected final Supplier<AccountType> accountTypeSupplier;
	protected final KillcountService killcountService;
	protected final WorldPinger worldPinger;
	protected final IntFunction<WorldRegion> worldRegionResolver;
	protected final IntFunction<String> worldAddressResolver;
	protected final FavoritesService favoritesService;
	protected final BlockListService blockListService;
	protected final Supplier<Set<String>> friendNamesSupplier;
	protected final net.osparty.OSPartyConfig config;

	private Runnable onFavoriteChanged = () -> {};
	private Runnable onBlockChanged = () -> {};

	// ---- mutable apply state ------------------------------------------------
	protected final Map<String, JButton> applyButtons = new HashMap<>();
	protected final Map<String, Advertisement> adsById = new HashMap<>();
	/** Per-card inline reason line and inline role picker. */
	protected final Map<String, JLabel> reasonLabels = new HashMap<>();
	protected final Map<String, JPanel> rolePickers = new HashMap<>();
	private final Map<String, Long> cooldownExpiry = new HashMap<>();
	private final Set<String> reportedAdIds = new HashSet<>();
	private Timer uiTimer;

	// ---- KC status ----------------------------------------------------------

	protected enum KcStatus
	{
		/** Meets the requirement (or there is none / it can't be checked). */
		OK,
		/** Hiscore lookup in progress; not yet known. */
		PENDING,
		/** Known to be below the required killcount. */
		BELOW
	}

	// ---- constructor --------------------------------------------------------

	protected PartyCardPanel(
		BoardService boardService,
		Supplier<String> playerNameSupplier,
		PartyState partyState,
		LivePartyBackend liveParty,
		Supplier<AccountType> accountTypeSupplier,
		KillcountService killcountService,
		WorldPinger worldPinger,
		IntFunction<WorldRegion> worldRegionResolver,
		IntFunction<String> worldAddressResolver,
		FavoritesService favoritesService,
		BlockListService blockListService,
		Supplier<Set<String>> friendNamesSupplier,
		SpriteManager spriteManager,
		net.osparty.OSPartyConfig config)
	{
		this.config = config;
		this.boardService = boardService;
		this.playerNameSupplier = playerNameSupplier;
		this.partyState = partyState;
		this.liveParty = liveParty;
		this.accountTypeSupplier = accountTypeSupplier;
		this.killcountService = killcountService;
		this.worldPinger = worldPinger;
		this.worldRegionResolver = worldRegionResolver;
		this.worldAddressResolver = worldAddressResolver;
		this.favoritesService = favoritesService;
		this.blockListService = blockListService;
		this.friendNamesSupplier = friendNamesSupplier;
		if (spriteManager != null)
		{
			BadgeIcons.preload(spriteManager);
		}
	}

	// ---- abstract hooks for subclasses -------------------------------------

	/** Called by apply/cancel to surface a message to the user. */
	protected abstract void setStatus(String text);

	/**
	 * Why this party is in the list, shown under the host name — a tab that only lists some parties has
	 * to say what put each one there, since the card itself gives no hint. Null (the default) omits the
	 * line, which is right for the Search tab: everything is listed, so there is nothing to explain.
	 */
	protected String cardNote(Advertisement ad)
	{
		return null;
	}

	/** Rebuild per-card Apply buttons after party state changes. */
	protected void updateAllButtons()
	{
		for (Map.Entry<String, JButton> entry : applyButtons.entrySet())
		{
			Advertisement ad = adsById.get(entry.getKey());
			if (ad != null)
			{
				updateApplyButton(entry.getValue(), ad);
			}
		}
	}

	// ---- eligibility helpers -----------------------------------------------

	protected boolean meetsIronmanRule(Advertisement ad)
	{
		return !ad.isIronmanOnly() || net.osparty.model.AccountTypes.isIronman(accountTypeSupplier.get());
	}

	protected KcStatus kcStatus(Advertisement ad)
	{
		Activity activity = Activity.fromId(ad.getActivity());
		int minKc = ad.getMinKillCount();
		int minHard = activity != null && activity.hasHardMode() ? ad.getMinHardModeKillCount() : 0;
		if ((minKc <= 0 && minHard <= 0) || activity == null)
		{
			return KcStatus.OK;
		}
		String me = playerNameSupplier.get();
		if (me == null)
		{
			return KcStatus.OK;
		}
		KillcountService.Killcount kc = killcountService.cached(me, activity);
		if (kc == null)
		{
			killcountService.lookup(me, activity, this::updateAllButtons);
			return KcStatus.PENDING;
		}
		boolean below = (minKc > 0 && kc.killCount >= 0 && kc.killCount < minKc)
			|| (minHard > 0 && kc.hardModeKillCount >= 0 && kc.hardModeKillCount < minHard);
		return below ? KcStatus.BELOW : KcStatus.OK;
	}

	protected boolean isOwnParty(Advertisement ad)
	{
		return AdText.sameName(playerNameSupplier.get(), ad.getHost());
	}

	protected boolean isActive(Advertisement ad)
	{
		return partyState.isInParty() && !partyState.isHost()
			&& partyState.getCurrentAd().getId().equals(ad.getId());
	}

	protected boolean isMemberInParty()
	{
		return partyState.isInParty() && !partyState.isHost();
	}

	// ---- cooldown -----------------------------------------------------------

	protected long cooldownRemainingSeconds(String adId)
	{
		Long expiry = cooldownExpiry.get(adId);
		if (expiry == null)
		{
			return 0;
		}
		long remainingMs = expiry - System.currentTimeMillis();
		if (remainingMs <= 0)
		{
			cooldownExpiry.remove(adId);
			return 0;
		}
		return (remainingMs + 999) / 1000;
	}

	protected boolean hasActiveCooldowns()
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

	protected void maybeStartTimer()
	{
		if (hasActiveCooldowns() || isMemberInParty())
		{
			ensureTimer();
		}
	}

	protected void ensureTimer()
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

	/** Stop the cooldown ticker so it can't outlive the panel. Call when the plugin unloads. */
	void dispose()
	{
		if (uiTimer != null)
		{
			uiTimer.stop();
		}
	}

	// ---- apply / cancel / leave --------------------------------------------

	protected void apply(Advertisement ad)
	{
		String player = playerNameSupplier.get();
		if (player == null)
		{
			setStatus("Log in before applying to a party.");
			return;
		}
		if (isOwnParty(ad))
		{
			setStatus("You can't apply to your own party.");
			return;
		}
		if (!meetsIronmanRule(ad))
		{
			setStatus("This party is for ironman accounts.");
			return;
		}
		if (kcStatus(ad) == KcStatus.BELOW)
		{
			setStatus("You don't meet this party's minimum killcount.");
			updateAllButtons();
			return;
		}
		if (cooldownRemainingSeconds(ad.getId()) > 0)
		{
			setStatus("On cooldown for this party.");
			return;
		}

		Activity activity = Activity.fromId(ad.getActivity());
		// Learner mark is a raid-only application choice, unless disabled in config.
		boolean askLearner = activity != null && activity.isRaid() && config.learnerRaidToggle();
		if (activity != null && activity.hasRoles())
		{
			List<Role> opts = roleOptionsFor(ad, activity);
			if (opts.size() > 1 || askLearner)
			{
				// Inline picker (role and/or learner); join fires from its button callback.
				showApplyPicker(ad, opts, askLearner);
				return;
			}
			beginApply(ad, opts.isEmpty() ? null : opts.get(0).getId(), false);
			return;
		}
		if (askLearner)
		{
			// A raid without role selection (ToA): still offer the learner checkbox.
			showApplyPicker(ad, java.util.Collections.emptyList(), true);
			return;
		}
		beginApply(ad, null, false);
	}

	/** Disable the Apply button, show "Applying…", and join (leaving any current party first). */
	private void beginApply(Advertisement ad, String role, boolean learner)
	{
		JButton button = applyButtons.get(ad.getId());
		if (button != null)
		{
			button.setEnabled(false);
			button.setText("Applying…");
		}
		leaveCurrentThen(() -> doApply(ad, role, learner));
	}

	/** Roles the player may pick when applying: the needed roles, else all activity roles. */
	private List<Role> roleOptionsFor(Advertisement ad, Activity activity)
	{
		List<Role> options = new ArrayList<>();
		List<String> needed = neededRolesOf(ad);
		if (needed != null)
		{
			for (String id : needed)
			{
				Role role = Role.fromId(id);
				if (role != null && !options.contains(role))
				{
					options.add(role);
				}
			}
		}
		// Fixed-composition activities (ToB/HMT): constrain picks to the size's exact role make-up.
		List<Role> composition = activity.fixedComposition(ad.getCapacity(), ad.isHardMode());
		if (composition != null && !composition.isEmpty())
		{
			options.retainAll(composition);
			if (options.isEmpty())
			{
				for (Role role : composition)
				{
					if (!options.contains(role))
					{
						options.add(role);
					}
				}
			}
			return options;
		}
		// An open "Fill / Any" slot (Chambers of Xeric / CM) means the host welcomes any role,
		// so let the applicant apply with the concrete role they want rather than only "Fill /
		// Any" — offer the activity's full role set (which still includes Fill/Any for someone
		// who genuinely doesn't mind). ToB has no Fill slot, so it stays constrained above.
		Role fill = activity.fillRole(ad.isHardMode());
		if (fill != null && options.contains(fill))
		{
			return new ArrayList<>(activity.roles(ad.isHardMode()));
		}
		if (options.isEmpty())
		{
			options.addAll(activity.roles(ad.isHardMode()));
		}
		return options;
	}

	/**
	 * Inline application picker: optional "I'm a learner" checkbox (raids) plus a role button per
	 * {@code options}, or a single Apply button when there are no roles (ToA). Feeds {@link #beginApply}.
	 */
	private void showApplyPicker(Advertisement ad, List<Role> options, boolean askLearner)
	{
		JPanel picker = rolePickers.get(ad.getId());
		if (picker == null)
		{
			// No card on screen to host the picker; say so rather than swallowing the click.
			setStatus("Couldn't show this party's role options. Refresh the list and try again.");
			return;
		}
		picker.removeAll();

		final JCheckBox learnerCheck;
		if (askLearner)
		{
			learnerCheck = new JCheckBox("I'm a learner");
			learnerCheck.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			learnerCheck.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			learnerCheck.setFont(FontManager.getRunescapeSmallFont());
			learnerCheck.setFocusPainted(false);
			learnerCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
			picker.add(learnerCheck);
		}
		else
		{
			learnerCheck = null;
		}

		if (!options.isEmpty())
		{
			JLabel prompt = new JLabel("Pick a role:");
			prompt.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			prompt.setFont(FontManager.getRunescapeSmallFont());
			prompt.setAlignmentX(Component.LEFT_ALIGNMENT);
			picker.add(prompt);
			for (Role role : options)
			{
				JButton b = new JButton(role.getDisplayName());
				b.setFocusPainted(false);
				b.setFont(FontManager.getRunescapeSmallFont());
				b.setAlignmentX(Component.LEFT_ALIGNMENT);
				b.addActionListener(e -> {
					picker.setVisible(false);
					beginApply(ad, role.getId(), learnerCheck != null && learnerCheck.isSelected());
				});
				picker.add(b);
			}
		}
		else
		{
			JButton applyBtn = new JButton("Apply");
			applyBtn.setFocusPainted(false);
			applyBtn.setFont(FontManager.getRunescapeSmallFont());
			applyBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
			applyBtn.addActionListener(e -> {
				picker.setVisible(false);
				beginApply(ad, null, learnerCheck != null && learnerCheck.isSelected());
			});
			picker.add(applyBtn);
		}

		JButton cancelPick = new JButton("Cancel");
		cancelPick.setFocusPainted(false);
		cancelPick.setFont(FontManager.getRunescapeSmallFont());
		cancelPick.setAlignmentX(Component.LEFT_ALIGNMENT);
		cancelPick.addActionListener(e -> {
			picker.setVisible(false);
			revalidate();
			repaint();
		});
		picker.add(cancelPick);
		picker.setVisible(true);
		revalidate();
		repaint();
	}

	protected void cancel(Advertisement ad)
	{
		JButton button = applyButtons.get(ad.getId());
		if (button != null)
		{
			button.setEnabled(false);
			button.setText("Leaving…");
		}
		liveParty.leave();
		partyState.clear();
		cooldownExpiry.put(ad.getId(), System.currentTimeMillis() + COOLDOWN_MS);
		setStatus("Left. You can re-apply to this party in " + (COOLDOWN_MS / 1000) + "s.");
		maybeStartTimer();
		updateAllButtons();
	}

	protected void leaveCurrentThen(Runnable next)
	{
		if (!partyState.isInParty())
		{
			next.run();
			return;
		}
		Advertisement current = partyState.getCurrentAd();
		if (partyState.isHost())
		{
			boardService.removeAd(current.getId(), playerNameSupplier.get(), partyState.getHostKey(),
				p -> { }, e -> { });
		}
		// leaveForSwitch() keeps the socket open so joinParty() can switch rooms without a close-reopen race.
		liveParty.leaveForSwitch();
		partyState.clear();
		next.run();
	}

	protected void doApply(Advertisement ad, String role, boolean learner)
	{
		doApply(ad, role, learner, false);
	}

	/** {@code invited} joiners are auto-admitted by the host instead of waiting for approval. */
	protected void doApply(Advertisement ad, String role, boolean learner, boolean invited)
	{
		String passphrase = ad.getPassphrase();
		if (passphrase == null || passphrase.isEmpty())
		{
			// No room to switch into; exit the old room cleanly now.
			liveParty.leave();
			setStatus("This party has no live room to join.");
			updateAllButtons();
			return;
		}
		// Before joining, not after: the room lives on one pod, and moving the connection there first turns
		// a redirect-and-reconnect into an ordinary join. Silently skipped when the ad does not say.
		liveParty.hintLiveNode(ad.getNode());
		liveParty.joinParty(passphrase, ad.getActivity(), ad.getCapacity(), role, learner, invited);
		partyState.setMember(ad);
		String roleSuffix = role != null ? " as " + Role.displayNameOf(role) : "";
		String learnerSuffix = learner ? " (learner)" : "";
		setStatus("Joined " + ad.getHost() + "'s room" + roleSuffix + learnerSuffix
			+ " — awaiting host approval.");
		updateAllButtons();
	}

	protected void updateApplyButton(JButton button, Advertisement ad)
	{
		ApplyState state = applyState(ad);
		button.setText(state.text);
		button.setEnabled(state.enabled);
		button.setToolTipText(state.tooltip);
		setReason(ad, state.reason, state.reasonColor);
	}

	/** How the Apply button should read for {@code ad}: the first guard that matches wins. */
	private ApplyState applyState(Advertisement ad)
	{
		if (playerNameSupplier.get() == null)
		{
			return new ApplyState("Log in", false, "Log in to apply to a party");
		}
		if (isOwnParty(ad))
		{
			return new ApplyState("Your party", false, "You host this party — manage it on the Party tab");
		}
		if (isActive(ad))
		{
			// Only an applicant still waiting on the host has something to withdraw. Once admitted you're
			// a member, so say so and leave from the Party tab rather than offering to cancel.
			return liveParty.isLocalAdmitted()
				? new ApplyState("In this party", false, "You're in this party — manage it on the Party tab")
				: new ApplyState("Cancel", true, "Withdraw your application");
		}
		if (!meetsIronmanRule(ad))
		{
			return new ApplyState("Iron only", false, "This party is for ironman accounts",
				"Ironman accounts only", ColorScheme.PROGRESS_ERROR_COLOR);
		}
		if (ad.isFull())
		{
			return new ApplyState("Full", false, null, "Party is full", ColorScheme.MEDIUM_GRAY_COLOR);
		}
		long remaining = cooldownRemainingSeconds(ad.getId());
		if (remaining > 0)
		{
			return new ApplyState("Wait " + remaining + "s", false, "Recently applied to this party",
				"Recently applied — wait " + remaining + "s", ColorScheme.MEDIUM_GRAY_COLOR);
		}
		KcStatus kc = kcStatus(ad);
		if (kc == KcStatus.BELOW)
		{
			return new ApplyState("Need KC", false, "You don't meet this party's minimum killcount",
				"Below the required killcount", ColorScheme.PROGRESS_ERROR_COLOR);
		}
		if (kc == KcStatus.PENDING)
		{
			return new ApplyState("Checking KC…", false, "Looking up your killcount on the hiscores",
				"Checking your killcount…", ColorScheme.MEDIUM_GRAY_COLOR);
		}
		return new ApplyState("Apply", true,
			partyState.isInParty() ? "Applying will leave your current party" : null);
	}

	/** One Apply-button appearance: the button itself plus the inline reason line beneath it. */
	private static final class ApplyState
	{
		final String text;
		final boolean enabled;
		final String tooltip;
		final String reason;
		final Color reasonColor;

		ApplyState(String text, boolean enabled, String tooltip)
		{
			this(text, enabled, tooltip, "", ColorScheme.MEDIUM_GRAY_COLOR);
		}

		ApplyState(String text, boolean enabled, String tooltip, String reason, Color reasonColor)
		{
			this.text = text;
			this.enabled = enabled;
			this.tooltip = tooltip;
			this.reason = reason;
			this.reasonColor = reasonColor;
		}
	}

	/** Set (or clear) the inline reason line beneath a card's Apply button. */
	private void setReason(Advertisement ad, String text, Color color)
	{
		JLabel label = reasonLabels.get(ad.getId());
		if (label == null)
		{
			return;
		}
		label.setText(text);
		label.setForeground(color);
		label.setVisible(text != null && !text.isEmpty());
	}

	// ---- role prompt -------------------------------------------------------

	protected String promptForRole(Advertisement ad, Activity activity)
	{
		List<Role> options = roleOptionsFor(ad, activity);
		if (options.isEmpty())
		{
			return null;
		}
		Role[] choices = options.toArray(new Role[0]);
		Role pick = (Role) JOptionPane.showInputDialog(this, "Which role will you fill?",
			"Choose a role", JOptionPane.QUESTION_MESSAGE, null, choices, choices[0]);
		return pick != null ? pick.getId() : null;
	}

	// ---- card building -----------------------------------------------------

	protected JPanel buildPartyCard(Activity activity, Advertisement ad)
	{
		JPanel card = new PanelWidgets.Capped(new BorderLayout(0, 4));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel info = new JPanel();
		info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
		info.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Activity title (Tier-1 heading: bold); CoX ads append their scale, e.g. "(3+4)".
		JLabel activityLabel = new JLabel((activity != null
			? activity.displayName(ad.isHardMode(), ad.getInvocation())
			: ad.getActivity()) + coxScaleSuffix(ad));
		activityLabel.setForeground(Color.WHITE);
		activityLabel.setFont(FontManager.getRunescapeBoldFont());

		// Host name (with account-type icon)
		JLabel hostLabel = new JLabel(ad.getHost() == null ? "Unknown host" : ad.getHost());
		hostLabel.setForeground(ColorScheme.BRAND_ORANGE);
		hostLabel.setFont(FontManager.getRunescapeSmallFont());
		ImageIcon hostIcon = AccountIcons.forType(AccountTypes.fromName(ad.getHostAccountType()));
		if (hostIcon != null)
		{
			hostLabel.setIcon(hostIcon);
			hostLabel.setIconTextGap(4);
		}

		// An OSRS friend hosting is marked by the tooltip only (a blocked host overwrites it below).
		Set<String> friends = friendNamesSupplier != null ? friendNamesSupplier.get() : null;
		boolean isFriend = friends != null && ad.getHost() != null
			&& friends.contains(AdText.normalizeName(ad.getHost()).toLowerCase());
		if (isFriend)
		{
			hostLabel.setToolTipText("OSRS Friend");
		}

		// Blocked hosts (only shown when "Show blocked parties" is on) are greyed to mark them.
		long hostHash = ad.getHostAccountHash();
		boolean hostBlocked = blockListService != null && blockListService.isBlocked(hostHash, ad.getHost());
		if (hostBlocked)
		{
			hostLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
			hostLabel.setToolTipText("Blocked host");
		}

		// Host row: the name only; favourite/block live on the card's right-click / 3-dot menu.
		JPanel hostRow = new JPanel(new BorderLayout(2, 0));
		hostRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		hostRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		hostRow.add(hostLabel, BorderLayout.CENTER);

		String capacity = ad.getCapacity() > 0
			? ad.getSize() + "/" + ad.getCapacity()
			: String.valueOf(ad.getSize());
		StringBuilder sub = new StringBuilder(capacity).append(" players");
		long ageMins = ageMinutes(System.currentTimeMillis(), ad.getCreatedAt());
		String age = formatAge(ad.getCreatedAt());
		if (age != null)
		{
			sub.append(", searching ").append(age);
		}
		boolean stale = ageMins >= STALE_MINUTES;
		if (stale)
		{
			sub.append(" · stale");
		}
		JLabel meta = new JLabel(sub.toString());
		meta.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		meta.setFont(FontManager.getRunescapeSmallFont());
		meta.setAlignmentX(Component.LEFT_ALIGNMENT);

		info.add(hostRow);

		String note = cardNote(ad);
		if (note != null)
		{
			info.add(wrappedLabel(note, ColorScheme.PROGRESS_COMPLETE_COLOR));
		}

		JLabel worldLabel = buildWorldLabel(ad);
		if (worldLabel != null)
		{
			worldLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			info.add(worldLabel);
		}
		info.add(meta);

		String tagLine = tagLine(ad);
		if (tagLine != null)
		{
			JLabel tags = new JLabel(tagLine);
			tags.setForeground(ColorScheme.BRAND_ORANGE);
			tags.setFont(FontManager.getRunescapeSmallFont());
			tags.setAlignmentX(Component.LEFT_ALIGNMENT);
			info.add(tags);
		}

		String requirement = AdText.requirementText(activity, ad);
		if (requirement != null)
		{
			JLabel req = new JLabel(requirement);
			req.setForeground(ColorScheme.PROGRESS_INPROGRESS_COLOR);
			req.setFont(FontManager.getRunescapeSmallFont());
			req.setAlignmentX(Component.LEFT_ALIGNMENT);
			info.add(req);
		}

		String needs = AdText.neededRolesText(activity, neededRolesOf(ad));
		if (needs != null)
		{
			info.add(wrappedLabel(needs, ColorScheme.BRAND_ORANGE));
		}

		if (ad.getDescription() != null && !ad.getDescription().isEmpty())
		{
			info.add(wrappedLabel(ad.getDescription(), ColorScheme.LIGHT_GRAY_COLOR));
		}

		if (ad.getLayout() != null && !ad.getLayout().isEmpty())
		{
			info.add(wrappedLabel("Layout: " + ad.getLayout(), ColorScheme.PROGRESS_INPROGRESS_COLOR));
		}

		// ---- bottom action panel: reason line + inline role picker + full-width Apply ----
		JLabel reasonLabel = new JLabel();
		reasonLabel.setFont(FontManager.getRunescapeSmallFont());
		reasonLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		reasonLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		reasonLabel.setVisible(false);
		reasonLabels.put(ad.getId(), reasonLabel);

		JPanel rolePicker = new JPanel();
		rolePicker.setLayout(new BoxLayout(rolePicker, BoxLayout.Y_AXIS));
		rolePicker.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		rolePicker.setAlignmentX(Component.LEFT_ALIGNMENT);
		rolePicker.setVisible(false);
		rolePickers.put(ad.getId(), rolePicker);

		JButton applyButton = new JButton("Apply");
		applyButton.setFocusPainted(false);
		applyButton.addActionListener(e -> {
			// Cards can be reused across refreshes; act on the freshest party data.
			Advertisement current = adsById.getOrDefault(ad.getId(), ad);
			if (isActive(current))
			{
				cancel(current);
			}
			else
			{
				apply(current);
			}
		});
		applyButtons.put(ad.getId(), applyButton);
		adsById.put(ad.getId(), ad);

		// Full-width Apply as the primary action.
		JPanel applyWrap = new JPanel(new BorderLayout());
		applyWrap.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		applyWrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		applyWrap.add(applyButton, BorderLayout.CENTER);

		JPanel actionPanel = new JPanel();
		actionPanel.setLayout(new BoxLayout(actionPanel, BoxLayout.Y_AXIS));
		actionPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		actionPanel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		actionPanel.add(reasonLabel);
		actionPanel.add(rolePicker);
		actionPanel.add(applyWrap);

		// Header: activity title left; host's Discord badges then a 3-dot menu top-right.
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.add(activityLabel, BorderLayout.CENTER);

		JPopupMenu menu = hostMenu(ad);
		JPanel headerEast = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		headerEast.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JPanel badgeRow = buildHostBadgeRow(ad);
		if (badgeRow != null)
		{
			headerEast.add(badgeRow);
		}
		if (menu != null)
		{
			headerEast.add(PanelWidgets.kebab("Host actions", menu));
		}
		header.add(headerEast, BorderLayout.EAST);

		card.add(header, BorderLayout.NORTH);
		card.add(info, BorderLayout.CENTER);
		card.add(actionPanel, BorderLayout.SOUTH);

		// Right-click anywhere on the card opens the same host actions as the 3-dot button.
		if (menu != null)
		{
			card.setComponentPopupMenu(menu);
			PanelWidgets.inheritPopupMenu(card);
		}

		return card;
	}

	/** Right-click / 3-dot actions for a card's host: favourite and block toggles. Null when none apply. */
	private JPopupMenu hostMenu(Advertisement ad)
	{
		final String host = ad.getHost();
		if (host == null)
		{
			return null;
		}
		final long hostHash = ad.getHostAccountHash();
		// Hash-based self-check: the name-based isOwnParty missed our own ad when the stored host name
		// differed slightly, which let us favourite ourselves. Fall back to it when there's no service.
		boolean self = blockListService != null ? blockListService.isSelf(hostHash, host) : isOwnParty(ad);
		JPopupMenu menu = new JPopupMenu();
		boolean any = false;

		if (favoritesService != null)
		{
			boolean fav = favoritesService.isFavorite(hostHash, host);
			// You can't favourite yourself; only offer the item to REMOVE a self-favourite you already have.
			if (!self || fav)
			{
				JMenuItem favItem = new JMenuItem(fav ? "Remove host from Favorites" : "Add host to Favorites");
				favItem.addActionListener(e -> {
					favoritesService.toggle(hostHash, host);
					onFavoriteToggled(ad);
					onFavoriteChanged.run();
				});
				menu.add(favItem);
				any = true;
			}
		}

		// You can't block yourself, so don't offer it on your own ad.
		if (blockListService != null && !self)
		{
			boolean blocked = blockListService.isBlocked(hostHash, host);
			JMenuItem blockItem = new JMenuItem(blocked ? "Unblock host" : "Block host");
			blockItem.addActionListener(e -> {
				if (!BlockConfirm.toggle(this, blockListService, favoritesService, hostHash, host))
				{
					return;
				}
				onFavoriteChanged.run(); // blocking may have dropped a conflicting favourite
				onBlockToggled(ad);
				onBlockChanged.run();
			});
			menu.add(blockItem);
			any = true;
		}

		if (!self)
		{
			if (any)
			{
				menu.addSeparator();
			}
			boolean reported = reportedAdIds.contains(ad.getId());
			JMenuItem reportItem = new JMenuItem(reported ? "Already reported" : "Report advertisement");
			reportItem.setEnabled(!reported);
			reportItem.addActionListener(e -> {
				if (reportedAdIds.contains(ad.getId()) || !ReportConfirm.confirm(this, host))
				{
					return;
				}
				reportedAdIds.add(ad.getId());
				boardService.reportAd(ad.getId());
				setStatus("Report sent. A moderator will review it.");
			});
			menu.add(reportItem);
			any = true;
		}

		return any ? menu : null;
	}

	/** The host's Discord-role badges as a right-aligned icon row, or {@code null} when none. */
	private JPanel buildHostBadgeRow(Advertisement ad)
	{
		if (config != null && !config.showDiscordBadges())
		{
			return null;
		}
		List<DiscordBadge> badges = DiscordBadge.fromWire(
			AdText.badgesFor(ad.getMembers(), ad.getHostAccountHash(), ad.getHost()));
		JPanel row = null;
		for (DiscordBadge badge : badges)
		{
			ImageIcon icon = BadgeIcons.get(badge);
			if (icon == null)
			{
				continue;
			}
			if (row == null)
			{
				row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
				row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
			JLabel label = new JLabel(icon);
			label.setToolTipText("Host is " + (badge == DiscordBadge.DEVELOPER ? "an " : "a ") + badge.getTooltip());
			row.add(label);
		}
		return row;
	}

	/** A read-only, layout-wrapping text component. */
	private static JComponent wrappedLabel(String text, Color fg)
	{
		JTextArea area = new JTextArea();
		// Disable caret-driven scrolling before setText, or a rebuild yanks the viewport to this card.
		if (area.getCaret() instanceof DefaultCaret)
		{
			((DefaultCaret) area.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
		}
		area.setText(text);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setEditable(false);
		area.setFocusable(false);
		area.setOpaque(false);
		area.setBorder(null);
		area.setFont(FontManager.getRunescapeSmallFont());
		area.setForeground(fg);
		area.setAlignmentX(Component.LEFT_ALIGNMENT);
		return area;
	}

	void setOnFavoriteChanged(Runnable r)
	{
		this.onFavoriteChanged = r;
	}

	void setOnBlockChanged(Runnable r)
	{
		this.onBlockChanged = r;
	}

	/** Subclasses override to refresh their own results after this panel's favourite toggle. */
	protected void onFavoriteToggled(Advertisement ad)
	{
	}

	/** Subclasses override to refresh their own results after this panel's block toggle. */
	protected void onBlockToggled(Advertisement ad)
	{
	}

	protected JLabel buildWorldLabel(Advertisement ad)
	{
		String raw = ad.getWorld();
		if (raw == null || raw.trim().isEmpty())
		{
			return null;
		}
		Integer parsed = parseWorldNum(ad);
		int worldNum = parsed == null ? -1 : parsed;

		StringBuilder labelText = new StringBuilder("World ").append(parsed == null ? raw.trim() : parsed);
		if (worldNum > 0 && worldPinger != null)
		{
			Integer ping = worldPinger.getCachedPing(worldNum);
			if (ping != null)
			{
				labelText.append(ping >= 0 ? "  ~" + ping + "ms" : "  (timeout)");
			}
		}

		JLabel label = PanelWidgets.smallLabel(labelText.toString(), ColorScheme.LIGHT_GRAY_COLOR);

		if (worldNum > 0 && worldRegionResolver != null)
		{
			WorldRegion region = worldRegionResolver.apply(worldNum);
			ImageIcon flag = WorldFlags.forRegion(region);
			if (flag != null)
			{
				label.setIcon(flag);
				label.setIconTextGap(4);
			}
		}
		return label;
	}

	// ---- static text helpers -----------------------------------------------

	protected static String tagLine(Advertisement ad)
	{
		List<String> tags = new ArrayList<>();
		if (ad.isLearnerRaid())
		{
			tags.add(ad.learnerLabel());
		}
		LootRule loot = LootRule.fromName(ad.getLootRule());
		if (loot != LootRule.UNSPECIFIED)
		{
			tags.add(loot.getDisplayName());
		}
		if (ad.isIronmanOnly())
		{
			tags.add("Ironman only");
		}
		return tags.isEmpty() ? null : String.join(", ", tags);
	}

	/** The CoX scale a party advertises (e.g. "3+4"), or "" when unset or not a CoX ad. */
	static String coxScaleOf(Advertisement ad)
	{
		String scale = ad.getCoxScale();
		if (scale == null || scale.trim().isEmpty() || !"cox".equals(ad.getActivity()))
		{
			return "";
		}
		scale = scale.trim();
		// A bare scaling like "4" is shown combined with the party size, e.g. a 3-man → "3+4".
		if (!scale.contains("+") && ad.getCapacity() > 0)
		{
			return ad.getCapacity() + "+" + scale;
		}
		return scale;
	}

	/** A title suffix like " (3+4)" for a CoX ad's scale, or "" when none. */
	static String coxScaleSuffix(Advertisement ad)
	{
		String scale = coxScaleOf(ad);
		return scale.isEmpty() ? "" : " (" + scale + ")";
	}

	protected static List<String> neededRolesOf(Advertisement ad)
	{
		if (ad.getNeededRoles() != null && !ad.getNeededRoles().isEmpty())
		{
			return ad.getNeededRoles();
		}
		return ad.getRequiredRoles();
	}

	protected static long ageMinutes(long now, long createdAt)
	{
		return createdAt <= 0 ? -1 : Math.max(0, (now - createdAt) / 60_000);
	}

	protected static String formatAge(long createdAt)
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

	/** Parse the world number from a party's world string, or null if not parseable. */
	protected static Integer parseWorldNum(Advertisement ad)
	{
		String raw = ad.getWorld();
		if (raw == null)
		{
			return null;
		}
		String digits = raw.replaceAll("\\D", "");
		if (digits.isEmpty() || digits.length() > 5)
		{
			return null;
		}
		return Integer.parseInt(digits);
	}
}
