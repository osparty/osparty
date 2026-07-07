package net.osparty.ui;

import net.osparty.FavoritesService;
import net.osparty.KillcountService;
import net.osparty.api.PartyService;
import net.osparty.model.AccountTypes;
import net.osparty.model.Activity;
import net.osparty.model.LootRule;
import net.osparty.model.Party;
import net.osparty.model.Role;
import net.osparty.party.LiveParty;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
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
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.text.DefaultCaret;
import net.runelite.api.vars.AccountType;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.http.api.worlds.WorldRegion;

/**
 * Abstract base for panels that display party cards (Search and Faves).
 * Holds all apply/cancel logic, cooldown tracking, and the shared
 * {@link #buildPartyCard} method so neither subclass duplicates it.
 */
abstract class PartyCardPanel extends JPanel
{
	protected static final long COOLDOWN_MS = 30_000;
	/** Ads still searching past this many minutes are dimmed/flagged as stale (point 40). */
	protected static final long STALE_MINUTES = 60;

	// ---- shared dependencies -----------------------------------------------
	protected final PartyService partyService;
	protected final Supplier<String> playerNameSupplier;
	protected final PartyState partyState;
	protected final LiveParty liveParty;
	protected final Supplier<AccountType> accountTypeSupplier;
	protected final KillcountService killcountService;
	protected final net.osparty.WorldPinger worldPinger;
	protected final IntFunction<WorldRegion> worldRegionResolver;
	protected final IntFunction<String> worldAddressResolver;
	protected final FavoritesService favoritesService;
	protected final net.osparty.BlockListService blockListService;
	protected final Supplier<Set<String>> friendNamesSupplier;
	protected final net.osparty.OSPartyConfig config;

	private volatile BufferedImage memberStarImg;
	private volatile BufferedImage freeStarImg;

	private Runnable onFavoriteChanged = () -> {};
	private Runnable onBlockChanged = () -> {};

	// ---- mutable apply state ------------------------------------------------
	protected final Map<String, JButton> applyButtons = new HashMap<>();
	protected final Map<String, Party> partiesById = new HashMap<>();
	/** Per-card inline reason line (point 37) and inline role picker (point 15). */
	protected final Map<String, JLabel> reasonLabels = new HashMap<>();
	protected final Map<String, JPanel> rolePickers = new HashMap<>();
	private final Map<String, Long> cooldownExpiry = new HashMap<>();
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
		PartyService partyService,
		Supplier<String> playerNameSupplier,
		PartyState partyState,
		LiveParty liveParty,
		Supplier<AccountType> accountTypeSupplier,
		KillcountService killcountService,
		net.osparty.WorldPinger worldPinger,
		IntFunction<WorldRegion> worldRegionResolver,
		IntFunction<String> worldAddressResolver,
		FavoritesService favoritesService,
		net.osparty.BlockListService blockListService,
		Supplier<Set<String>> friendNamesSupplier,
		SpriteManager spriteManager,
		net.osparty.OSPartyConfig config)
	{
		this.config = config;
		this.partyService = partyService;
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
			// 1131 = WORLD_SWITCHER_STAR_MEMBERS, 1130 = WORLD_SWITCHER_STAR_FREE
			spriteManager.getSpriteAsync(1131, 0,
				img -> { if (img != null) memberStarImg = ImageUtil.resizeImage(img, 14, 14); });
			spriteManager.getSpriteAsync(1130, 0,
				img -> { if (img != null) freeStarImg = ImageUtil.resizeImage(img, 14, 14); });
		}
	}

	// ---- abstract hooks for subclasses -------------------------------------

	/** Called by apply/cancel to surface a message to the user. */
	protected abstract void setStatus(String text);

	/** Rebuild per-card Apply buttons after party state changes. */
	protected void updateAllButtons()
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

	// ---- eligibility helpers -----------------------------------------------

	protected boolean meetsIronmanRule(Party party)
	{
		return !party.isIronmanOnly() || net.osparty.model.AccountTypes.isIronman(accountTypeSupplier.get());
	}

	protected KcStatus kcStatus(Party party)
	{
		Activity activity = Activity.fromId(party.getActivity());
		int minKc = party.getMinKillCount();
		int minHard = activity != null && activity.hasHardMode() ? party.getMinHardModeKillCount() : 0;
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

	protected boolean isOwnParty(Party party)
	{
		String me = playerNameSupplier.get();
		return me != null && party.getHost() != null
			&& normalize(me).equalsIgnoreCase(normalize(party.getHost()));
	}

	protected boolean isActive(Party party)
	{
		return partyState.isInParty() && !partyState.isHost()
			&& partyState.getCurrentParty().getId().equals(party.getId());
	}

	protected boolean isMemberInParty()
	{
		return partyState.isInParty() && !partyState.isHost();
	}

	// ---- cooldown -----------------------------------------------------------

	protected long cooldownRemainingSeconds(String partyId)
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

	// ---- apply / cancel / leave --------------------------------------------

	protected void apply(Party party)
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
		if (kcStatus(party) == KcStatus.BELOW)
		{
			setStatus("You don't meet this party's minimum killcount.");
			updateAllButtons();
			return;
		}
		if (cooldownRemainingSeconds(party.getId()) > 0)
		{
			setStatus("On cooldown for this party.");
			return;
		}

		Activity activity = Activity.fromId(party.getActivity());
		// The learner mark is a raid-only choice made as part of the application (ToA/ToB/CoX),
		// shown unless the user disabled the toggle in config.
		boolean askLearner = activity != null && activity.isRaid() && config.learnerRaidToggle();
		if (activity != null && activity.hasRoles())
		{
			List<Role> opts = roleOptionsFor(party, activity);
			if (opts.size() > 1 || askLearner)
			{
				// Show the inline picker (role choice and/or the learner checkbox); the join
				// fires from the picker's button callback (point 15).
				showApplyPicker(party, opts, askLearner);
				return;
			}
			beginApply(party, opts.isEmpty() ? null : opts.get(0).getId(), false);
			return;
		}
		if (askLearner)
		{
			// A raid without role selection (ToA): still offer the learner checkbox.
			showApplyPicker(party, java.util.Collections.emptyList(), true);
			return;
		}
		beginApply(party, null, false);
	}

	/** Disable the Apply button, show "Applying…", and join (leaving any current party first). */
	private void beginApply(Party party, String role, boolean learner)
	{
		JButton button = applyButtons.get(party.getId());
		if (button != null)
		{
			button.setEnabled(false);
			button.setText("Applying…");
		}
		leaveCurrentThen(() -> doApply(party, role, learner));
	}

	/** Roles the player may pick when applying: the needed roles, else all activity roles. */
	private List<Role> roleOptionsFor(Party party, Activity activity)
	{
		List<Role> options = new ArrayList<>();
		List<String> needed = neededRolesOf(party);
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
		// Fixed-composition activities (ToB) have an exact role make-up for the party size, so
		// never offer a slot outside it — e.g. a 3-man only needs a North freeze, so South must
		// not be pickable. Derive the composition from capacity (north-first) and constrain to
		// it; if we couldn't read the still-needed roles, offer the whole composition instead of
		// every role the activity supports.
		List<Role> composition = activity.fixedComposition(party.getCapacity());
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
		if (options.isEmpty())
		{
			options.addAll(activity.roles(party.isHardMode()));
		}
		return options;
	}

	/** Populate and reveal the card's inline role picker (themed, non-modal). */
	/**
	 * Inline application picker: an optional "I'm a learner" checkbox (raids only) plus either a
	 * role button per {@code options} or, when there are no roles (ToA), a single Apply button.
	 * The chosen role and the learner state are carried into {@link #beginApply}.
	 */
	private void showApplyPicker(Party party, List<Role> options, boolean askLearner)
	{
		JPanel picker = rolePickers.get(party.getId());
		if (picker == null)
		{
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
					beginApply(party, role.getId(), learnerCheck != null && learnerCheck.isSelected());
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
				beginApply(party, null, learnerCheck != null && learnerCheck.isSelected());
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

	protected void cancel(Party party)
	{
		JButton button = applyButtons.get(party.getId());
		if (button != null)
		{
			button.setEnabled(false);
			button.setText("Leaving…");
		}
		liveParty.leave();
		partyState.clear();
		cooldownExpiry.put(party.getId(), System.currentTimeMillis() + COOLDOWN_MS);
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
		Party current = partyState.getCurrentParty();
		if (partyState.isHost())
		{
			partyService.disbandParty(current.getId(), playerNameSupplier.get(), partyState.getHostKey(),
				p -> { }, e -> { });
		}
		// Switch rooms without tearing down the party socket: leaveForSwitch() keeps it open
		// so the follow-up joinParty() parts the old room and joins the new on the same
		// connection. A full leave() here would close-then-reopen and lose our applicant
		// broadcast to the target host (party disbands, but no request arrives).
		liveParty.leaveForSwitch();
		partyState.clear();
		next.run();
	}

	/** Join-by-code and other entry points that don't collect a learner mark. */
	protected void doApply(Party party, String role)
	{
		doApply(party, role, false);
	}

	protected void doApply(Party party, String role, boolean learner)
	{
		String passphrase = party.getPassphrase();
		if (passphrase == null || passphrase.isEmpty())
		{
			// No room to switch into — leaveForSwitch() left us still in the old room, so
			// exit it cleanly now (a lone changeParty(null) doesn't hit the reopen race).
			liveParty.leave();
			setStatus("This party has no live room to join.");
			updateAllButtons();
			return;
		}
		liveParty.joinParty(passphrase, party.getActivity(), party.getCapacity(), role, learner);
		partyState.setMember(party);
		String roleSuffix = role != null ? " as " + Role.displayNameOf(role) : "";
		String learnerSuffix = learner ? " (learner)" : "";
		setStatus("Joined " + party.getHost() + "'s room" + roleSuffix + learnerSuffix
			+ " — awaiting host approval.");
		updateAllButtons();
	}

	protected void updateApplyButton(JButton button, Party party)
	{
		if (playerNameSupplier.get() == null)
		{
			button.setText("Log in");
			button.setEnabled(false);
			button.setToolTipText("Log in to apply to a party");
			setReason(party, "", ColorScheme.MEDIUM_GRAY_COLOR);
			return;
		}
		if (isOwnParty(party))
		{
			button.setText("Your party");
			button.setEnabled(false);
			button.setToolTipText("You host this party — manage it on the Party tab");
			setReason(party, "", ColorScheme.MEDIUM_GRAY_COLOR);
			return;
		}
		if (isActive(party))
		{
			button.setText("Cancel");
			button.setEnabled(true);
			button.setToolTipText("Withdraw your application");
			setReason(party, "", ColorScheme.MEDIUM_GRAY_COLOR);
			return;
		}
		if (!meetsIronmanRule(party))
		{
			button.setText("Iron only");
			button.setEnabled(false);
			button.setToolTipText("This party is for ironman accounts");
			setReason(party, "Ironman accounts only", ColorScheme.PROGRESS_ERROR_COLOR);
			return;
		}
		if (party.isFull())
		{
			button.setText("Full");
			button.setEnabled(false);
			button.setToolTipText(null);
			setReason(party, "Party is full", ColorScheme.MEDIUM_GRAY_COLOR);
			return;
		}
		long remaining = cooldownRemainingSeconds(party.getId());
		if (remaining > 0)
		{
			button.setText("Wait " + remaining + "s");
			button.setEnabled(false);
			button.setToolTipText("Recently applied to this party");
			setReason(party, "Recently applied — wait " + remaining + "s", ColorScheme.MEDIUM_GRAY_COLOR);
			return;
		}
		KcStatus kc = kcStatus(party);
		if (kc == KcStatus.BELOW)
		{
			button.setText("Need KC");
			button.setEnabled(false);
			button.setToolTipText("You don't meet this party's minimum killcount");
			setReason(party, "Below the required killcount", ColorScheme.PROGRESS_ERROR_COLOR);
			return;
		}
		if (kc == KcStatus.PENDING)
		{
			button.setText("Checking KC…");
			button.setEnabled(false);
			button.setToolTipText("Looking up your killcount on the hiscores");
			setReason(party, "Checking your killcount…", ColorScheme.MEDIUM_GRAY_COLOR);
			return;
		}
		button.setText("Apply");
		button.setEnabled(true);
		button.setToolTipText(partyState.isInParty() ? "Applying will leave your current party" : null);
		setReason(party, "", ColorScheme.MEDIUM_GRAY_COLOR);
	}

	/** Set (or clear) the inline reason line beneath a card's Apply button. */
	private void setReason(Party party, String text, Color color)
	{
		JLabel label = reasonLabels.get(party.getId());
		if (label == null)
		{
			return;
		}
		label.setText(text);
		label.setForeground(color);
		label.setVisible(text != null && !text.isEmpty());
	}

	// ---- role prompt -------------------------------------------------------

	protected String promptForRole(Party party, Activity activity)
	{
		List<Role> options = roleOptionsFor(party, activity);
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

	protected JPanel buildPartyCard(Activity activity, Party party)
	{
		JPanel card = new JPanel(new BorderLayout(0, 4))
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

		JPanel info = new JPanel();
		info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
		info.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Activity title (Tier-1 heading: bold)
		JLabel activityLabel = new JLabel(activity != null
			? activity.displayName(party.isHardMode(), party.getInvocation())
			: party.getActivity());
		activityLabel.setForeground(Color.WHITE);
		activityLabel.setFont(FontManager.getRunescapeBoldFont());

		// Host name (with account-type icon)
		JLabel hostLabel = new JLabel(party.getHost() == null ? "Unknown host" : party.getHost());
		hostLabel.setForeground(ColorScheme.BRAND_ORANGE);
		hostLabel.setFont(FontManager.getRunescapeSmallFont());
		ImageIcon hostIcon = AccountIcons.forType(AccountTypes.fromName(party.getHostAccountType()));
		if (hostIcon != null)
		{
			hostLabel.setIcon(hostIcon);
			hostLabel.setIconTextGap(4);
		}

		// Friend badge: show a small indicator when the host is an OSRS friend.
		Set<String> friends = friendNamesSupplier != null ? friendNamesSupplier.get() : null;
		boolean isFriend = friends != null && party.getHost() != null
			&& friends.contains(normalize(party.getHost()).toLowerCase());
		if (isFriend)
		{
			hostLabel.setToolTipText("OSRS Friend");
		}

		// Favorite button: member-world star when favourited, free-world star when not.
		long hostHash = party.getHostAccountHash();
		boolean hostFav = favoritesService != null && favoritesService.isFavorite(hostHash, party.getHost());
		boolean anyFav = favoritesService != null && favoritesService.hasAnyFavorite(party);

		JButton starBtn = new JButton();
		starBtn.setFocusPainted(false);
		starBtn.setContentAreaFilled(false);
		starBtn.setBorderPainted(false);
		starBtn.setMargin(new Insets(0, 2, 0, 2));
		if (memberStarImg != null && freeStarImg != null)
		{
			starBtn.setIcon(new ImageIcon(anyFav ? memberStarImg : freeStarImg));
		}
		else
		{
			starBtn.setIcon(anyFav ? StatusIcons.STAR_FILLED : StatusIcons.STAR_OUTLINE);
		}
		starBtn.setToolTipText(hostFav ? "Remove host from Favorites" : "Add host to Favorites");
		starBtn.addActionListener(e -> {
			if (favoritesService != null && party.getHost() != null)
			{
				favoritesService.toggle(hostHash, party.getHost());
				boolean nowHostFav = favoritesService.isFavorite(hostHash, party.getHost());
				boolean nowAnyFav = favoritesService.hasAnyFavorite(party);
				if (memberStarImg != null && freeStarImg != null)
				{
					starBtn.setIcon(new ImageIcon(nowAnyFav ? memberStarImg : freeStarImg));
				}
				else
				{
					starBtn.setIcon(nowAnyFav ? StatusIcons.STAR_FILLED : StatusIcons.STAR_OUTLINE);
				}
				starBtn.setToolTipText(nowHostFav ? "Remove host from Favorites" : "Add host to Favorites");
				onFavoriteToggled(party);
				onFavoriteChanged.run();
			}
		});

		// Block button: a small "no entry" toggle. Blocking a host hides their ads from
		// search (unless "Show blocked parties" is on); favouriting and blocking are mutually
		// exclusive, so blocking clears any favourite on the same host.
		boolean hostBlocked = blockListService != null && blockListService.isBlocked(hostHash, party.getHost());
		if (hostBlocked)
		{
			// Only reachable when "Show blocked parties" is on; grey the name to mark it.
			hostLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
			hostLabel.setToolTipText("Blocked host");
		}
		JButton blockBtn = new JButton(hostBlocked ? StatusIcons.BLOCK_ON : StatusIcons.BLOCK_OFF);
		blockBtn.setFocusPainted(false);
		blockBtn.setContentAreaFilled(false);
		blockBtn.setBorderPainted(false);
		blockBtn.setMargin(new Insets(0, 2, 0, 2));
		blockBtn.setToolTipText(hostBlocked ? "Unblock host" : "Block host");
		blockBtn.addActionListener(e -> {
			if (blockListService != null && party.getHost() != null)
			{
				boolean wasBlocked = blockListService.isBlocked(hostHash, party.getHost());
				// Confirm the consequences before blocking (but let unblocking happen instantly).
				if (!wasBlocked && !BlockConfirm.confirm(blockBtn, party.getHost()))
				{
					return;
				}
				blockListService.toggle(hostHash, party.getHost());
				boolean nowBlocked = !wasBlocked;
				// Favouriting and blocking the same host are mutually exclusive.
				if (nowBlocked && favoritesService != null && favoritesService.isFavorite(hostHash, party.getHost()))
				{
					favoritesService.toggle(hostHash, party.getHost());
					onFavoriteChanged.run();
				}
				blockBtn.setIcon(nowBlocked ? StatusIcons.BLOCK_ON : StatusIcons.BLOCK_OFF);
				blockBtn.setToolTipText(nowBlocked ? "Unblock host" : "Block host");
				onBlockToggled(party);
				onBlockChanged.run();
			}
		});

		// Host row: star | name | block. The favourite (left) and block (right) toggles are
		// kept apart — one common action, one rare/destructive — so they can't be misclicked.
		JPanel hostRow = new JPanel(new BorderLayout(2, 0));
		hostRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		hostRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		hostRow.add(starBtn, BorderLayout.WEST);
		hostRow.add(hostLabel, BorderLayout.CENTER);
		// You can't block yourself, so don't offer the toggle on your own ad.
		if (blockListService == null || !blockListService.isSelf(hostHash, party.getHost()))
		{
			hostRow.add(blockBtn, BorderLayout.EAST);
		}

		String capacity = party.getCapacity() > 0
			? party.getSize() + "/" + party.getCapacity()
			: String.valueOf(party.getSize());
		StringBuilder sub = new StringBuilder(capacity).append(" players");
		long ageMins = ageMinutes(System.currentTimeMillis(), party.getCreatedAt());
		String age = formatAge(party.getCreatedAt());
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
		meta.setForeground(stale ? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		meta.setFont(FontManager.getRunescapeSmallFont());
		meta.setAlignmentX(Component.LEFT_ALIGNMENT);

		info.add(hostRow);
		JLabel worldLabel = buildWorldLabel(party);
		if (worldLabel != null)
		{
			worldLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			info.add(worldLabel);
		}
		info.add(meta);

		String tagLine = tagLine(party);
		if (tagLine != null)
		{
			JLabel tags = new JLabel(tagLine);
			tags.setForeground(ColorScheme.BRAND_ORANGE);
			tags.setFont(FontManager.getRunescapeSmallFont());
			tags.setAlignmentX(Component.LEFT_ALIGNMENT);
			info.add(tags);
		}

		String requirement = requirementText(activity, party);
		if (requirement != null)
		{
			JLabel req = new JLabel(requirement);
			req.setForeground(ColorScheme.PROGRESS_INPROGRESS_COLOR);
			req.setFont(FontManager.getRunescapeSmallFont());
			req.setAlignmentX(Component.LEFT_ALIGNMENT);
			info.add(req);
		}

		String needs = neededRolesText(activity, party);
		if (needs != null)
		{
			info.add(wrappedLabel(needs, ColorScheme.BRAND_ORANGE));
		}

		if (party.getDescription() != null && !party.getDescription().isEmpty())
		{
			info.add(wrappedLabel(party.getDescription(), ColorScheme.LIGHT_GRAY_COLOR));
		}

		if (party.getLayout() != null && !party.getLayout().isEmpty())
		{
			info.add(wrappedLabel("Layout: " + party.getLayout(), ColorScheme.PROGRESS_INPROGRESS_COLOR));
		}

		// ---- bottom action panel: reason line + inline role picker + full-width Apply ----
		JLabel reasonLabel = new JLabel();
		reasonLabel.setFont(FontManager.getRunescapeSmallFont());
		reasonLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		reasonLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		reasonLabel.setVisible(false);
		reasonLabels.put(party.getId(), reasonLabel);

		JPanel rolePicker = new JPanel();
		rolePicker.setLayout(new BoxLayout(rolePicker, BoxLayout.Y_AXIS));
		rolePicker.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		rolePicker.setAlignmentX(Component.LEFT_ALIGNMENT);
		rolePicker.setVisible(false);
		rolePickers.put(party.getId(), rolePicker);

		JButton applyButton = new JButton("Apply");
		applyButton.setFocusPainted(false);
		applyButton.addActionListener(e -> {
			// Cards can be reused across refreshes; act on the freshest party data.
			Party current = partiesById.getOrDefault(party.getId(), party);
			if (isActive(current))
			{
				cancel(current);
			}
			else
			{
				apply(current);
			}
		});
		applyButtons.put(party.getId(), applyButton);
		partiesById.put(party.getId(), party);

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

		card.add(activityLabel, BorderLayout.NORTH);
		card.add(info, BorderLayout.CENTER);
		card.add(actionPanel, BorderLayout.SOUTH);

		return card;
	}

	/** A read-only, layout-wrapping text component (replaces manual char-count wrapping). */
	private static JComponent wrappedLabel(String text, Color fg)
	{
		JTextArea area = new JTextArea();
		// Never let the caret drive scrolling: inserting text moves the caret, and DefaultCaret
		// schedules a deferred scrollRectToVisible that yanks the results viewport to whichever
		// card was (re)built last. Must be disabled before the text goes in — the scroll is
		// queued from the insert itself.
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

	/** Notifies the sibling panel (Search↔Favorites) that a favorite was toggled. */
	void setOnFavoriteChanged(Runnable r)
	{
		this.onFavoriteChanged = r;
	}

	/** Notifies the panel that a host was blocked/unblocked (Search re-filters, Favorites refreshes). */
	void setOnBlockChanged(Runnable r)
	{
		this.onBlockChanged = r;
	}

	/**
	 * Called when the star button is clicked. Subclasses can override to refresh
	 * their results (e.g. the Favorites panel removes the party when its host is un-starred).
	 */
	protected void onFavoriteToggled(Party party)
	{
	}

	/**
	 * Called when the block button is clicked. Subclasses can override to refresh
	 * their results (e.g. Search hides the card once its host is blocked).
	 */
	protected void onBlockToggled(Party party)
	{
	}

	protected JLabel buildWorldLabel(Party party)
	{
		String raw = party.getWorld();
		if (raw == null || raw.trim().isEmpty())
		{
			return null;
		}
		String digits = raw.replaceAll("\\D", "");
		int worldNum = (!digits.isEmpty() && digits.length() <= 5) ? Integer.parseInt(digits) : -1;

		StringBuilder labelText = new StringBuilder("World ").append(digits.isEmpty() ? raw.trim() : digits);
		if (worldNum > 0 && worldPinger != null)
		{
			Integer ping = worldPinger.getCachedPing(worldNum);
			if (ping != null)
			{
				labelText.append(ping >= 0 ? "  ~" + ping + "ms" : "  (timeout)");
			}
		}

		JLabel label = new JLabel(labelText.toString());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());

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

	protected static String tagLine(Party party)
	{
		List<String> tags = new ArrayList<>();
		if (party.isLearnerRaid())
		{
			tags.add(party.learnerLabel());
		}
		LootRule loot = LootRule.fromName(party.getLootRule());
		if (loot != LootRule.UNSPECIFIED)
		{
			tags.add(loot.getDisplayName());
		}
		if (party.isIronmanOnly())
		{
			tags.add("Ironman only");
		}
		return tags.isEmpty() ? null : String.join(", ", tags);
	}

	protected String requirementText(Activity activity, Party party)
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
			req.append(party.getMinHardModeKillCount()).append(' ').append(activity.getHardModeLabel())
				.append(" KC");
		}
		return req.toString();
	}

	protected static String neededRolesText(Activity activity, Party party)
	{
		if (activity == null || !activity.hasRoles())
		{
			return null;
		}
		List<String> needed = neededRolesOf(party);
		if (needed == null || needed.isEmpty())
		{
			return null;
		}
		java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
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

	protected static List<String> neededRolesOf(Party party)
	{
		if (party.getNeededRoles() != null && !party.getNeededRoles().isEmpty())
		{
			return party.getNeededRoles();
		}
		return party.getRequiredRoles();
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

	/** Normalise a player name for comparison (RuneLite uses nbsp in names). */
	protected static String normalize(String name)
	{
		return name == null ? "" : name.replace('\u00A0', ' ').trim();
	}

	/** Parse the world number from a party's world string, or null if not parseable. */
	protected static Integer parseWorldNum(Party party)
	{
		String raw = party.getWorld();
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
