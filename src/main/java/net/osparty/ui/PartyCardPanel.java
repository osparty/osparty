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
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.Timer;
import net.runelite.api.vars.AccountType;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.http.api.worlds.WorldRegion;

/**
 * Abstract base for panels that display party cards (Search and Faves).
 * Holds all apply/cancel logic, cooldown tracking, and the shared
 * {@link #buildPartyCard} method so neither subclass duplicates it.
 */
abstract class PartyCardPanel extends JPanel
{
	protected static final long COOLDOWN_MS = 30_000;

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
	protected final Supplier<Set<String>> friendNamesSupplier;

	// ---- mutable apply state ------------------------------------------------
	protected final Map<String, JButton> applyButtons = new HashMap<>();
	protected final Map<String, Party> partiesById = new HashMap<>();
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
		Supplier<Set<String>> friendNamesSupplier)
	{
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
		this.friendNamesSupplier = friendNamesSupplier;
	}

	// ---- abstract hooks for subclasses -------------------------------------

	/** Called by apply/cancel to surface a message to the user. */
	protected abstract void setStatus(String text);

	/** Whether the local player is marking themselves as a learner when applying. */
	protected abstract boolean isLocalLearner();

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
		String role = null;
		if (activity != null && activity.hasRoles())
		{
			role = promptForRole(party, activity);
			if (role == null)
			{
				return;
			}
		}

		JButton button = applyButtons.get(party.getId());
		if (button != null)
		{
			button.setEnabled(false);
			button.setText("Applying...");
		}
		final String chosenRole = role;
		leaveCurrentThen(() -> doApply(party, chosenRole));
	}

	protected void cancel(Party party)
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
		liveParty.leave();
		partyState.clear();
		next.run();
	}

	protected void doApply(Party party, String role)
	{
		String passphrase = party.getPassphrase();
		if (passphrase == null || passphrase.isEmpty())
		{
			setStatus("This party has no live room to join.");
			updateAllButtons();
			return;
		}
		boolean learner = isLocalLearner();
		liveParty.joinParty(passphrase, party.getActivity(), party.getCapacity(), role, learner);
		partyState.setMember(party);
		String roleSuffix = role != null ? " as " + Role.displayNameOf(role) : "";
		String learnerSuffix = learner ? " (learner)" : "";
		setStatus("Joined " + party.getHost() + "'s room" + roleSuffix + learnerSuffix
			+ " - awaiting host approval.");
		updateAllButtons();
	}

	protected void updateApplyButton(JButton button, Party party)
	{
		if (playerNameSupplier.get() == null)
		{
			button.setText("Log in");
			button.setEnabled(false);
			button.setToolTipText("Log in to apply to a party");
			return;
		}
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
		KcStatus kc = kcStatus(party);
		if (kc == KcStatus.BELOW)
		{
			button.setText("Need KC");
			button.setEnabled(false);
			button.setToolTipText("You don't meet this party's minimum killcount");
			return;
		}
		if (kc == KcStatus.PENDING)
		{
			button.setText("Checking KC...");
			button.setEnabled(false);
			button.setToolTipText("Looking up your killcount on the hiscores");
			return;
		}
		button.setText("Apply");
		button.setEnabled(true);
		button.setToolTipText(partyState.isInParty() ? "Applying will leave your current party" : null);
	}

	// ---- role prompt -------------------------------------------------------

	protected String promptForRole(Party party, Activity activity)
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
		if (options.isEmpty())
		{
			options.addAll(activity.roles(party.isHardMode()));
		}
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

		JPanel info = new JPanel(new GridLayout(0, 1));
		info.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Activity title
		JLabel activityLabel = new JLabel(activity != null
			? activity.displayName(party.isHardMode(), party.getInvocation())
			: party.getActivity());
		activityLabel.setForeground(Color.WHITE);

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

		// Star (favourite) button: ★ orange if host is fav, ★ grey if a member is fav, ☆ otherwise.
		boolean hostFav = favoritesService != null && favoritesService.isFavorite(party.getHost());
		boolean anyFav = favoritesService != null && favoritesService.hasAnyFavorite(party);

		JButton starBtn = new JButton(anyFav ? "★" : "☆");
		starBtn.setFont(FontManager.getRunescapeSmallFont());
		starBtn.setFocusPainted(false);
		starBtn.setContentAreaFilled(false);
		starBtn.setBorderPainted(false);
		starBtn.setMargin(new Insets(0, 2, 0, 2));
		starBtn.setForeground(hostFav ? ColorScheme.BRAND_ORANGE
			: anyFav ? ColorScheme.MEDIUM_GRAY_COLOR
			: Color.DARK_GRAY);
		starBtn.setToolTipText(hostFav ? "Remove host from Favorites" : "Add host to Favorites");
		starBtn.addActionListener(e -> {
			if (favoritesService != null && party.getHost() != null)
			{
				favoritesService.toggle(party.getHost());
				boolean nowHostFav = favoritesService.isFavorite(party.getHost());
				boolean nowAnyFav = favoritesService.hasAnyFavorite(party);
				starBtn.setText(nowAnyFav ? "★" : "☆");
				starBtn.setForeground(nowHostFav ? ColorScheme.BRAND_ORANGE
					: nowAnyFav ? ColorScheme.MEDIUM_GRAY_COLOR
					: Color.DARK_GRAY);
				starBtn.setToolTipText(nowHostFav ? "Remove host from Favorites" : "Add host to Favorites");
				onFavoriteToggled(party);
			}
		});

		// Host row: star | name
		JPanel hostRow = new JPanel(new BorderLayout(2, 0));
		hostRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		hostRow.add(starBtn, BorderLayout.WEST);
		hostRow.add(hostLabel, BorderLayout.CENTER);

		String capacity = party.getCapacity() > 0
			? party.getSize() + "/" + party.getCapacity()
			: String.valueOf(party.getSize());
		StringBuilder sub = new StringBuilder(capacity).append(" players");
		String age = formatAge(party.getCreatedAt());
		if (age != null)
		{
			sub.append(", searching ").append(age);
		}
		JLabel meta = new JLabel(sub.toString());
		meta.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		meta.setFont(FontManager.getRunescapeSmallFont());

		info.add(hostRow);
		JLabel worldLabel = buildWorldLabel(party);
		if (worldLabel != null)
		{
			info.add(worldLabel);
		}
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

		String needs = neededRolesText(activity, party);
		if (needs != null)
		{
			for (String line : wrapByComma(needs, 30))
			{
				JLabel roles = new JLabel(line);
				roles.setForeground(ColorScheme.BRAND_ORANGE);
				roles.setFont(FontManager.getRunescapeSmallFont());
				info.add(roles);
			}
		}

		if (party.getDescription() != null && !party.getDescription().isEmpty())
		{
			JLabel desc = new JLabel(party.getDescription());
			desc.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			desc.setFont(FontManager.getRunescapeSmallFont());
			info.add(desc);
		}

		if (party.getLayout() != null && !party.getLayout().isEmpty())
		{
			for (String line : wrapByComma("Layout: " + party.getLayout(), 30))
			{
				JLabel layout = new JLabel(line);
				layout.setForeground(ColorScheme.PROGRESS_INPROGRESS_COLOR);
				layout.setFont(FontManager.getRunescapeSmallFont());
				info.add(layout);
			}
		}

		JButton applyButton = new JButton("Apply");
		applyButton.setFocusPainted(false);
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
		partiesById.put(party.getId(), party);

		JPanel buttonWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		buttonWrap.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		buttonWrap.add(applyButton);

		JPanel header = new JPanel(new BorderLayout(6, 0));
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.add(activityLabel, BorderLayout.CENTER);
		header.add(buttonWrap, BorderLayout.EAST);

		card.add(header, BorderLayout.NORTH);
		card.add(info, BorderLayout.CENTER);

		return card;
	}

	/**
	 * Called when the star button is clicked. Subclasses can override to refresh
	 * their results (e.g. the Faves panel removes the party when its host is un-starred).
	 */
	protected void onFavoriteToggled(Party party)
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

	protected static List<String> wrapByComma(String text, int max)
	{
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String part : text.split(", "))
		{
			if (line.length() == 0)
			{
				line.append(part);
			}
			else if (line.length() + 2 + part.length() <= max)
			{
				line.append(", ").append(part);
			}
			else
			{
				lines.add(line + ",");
				line = new StringBuilder(part);
			}
		}
		if (line.length() > 0)
		{
			lines.add(line.toString());
		}
		return lines;
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
