package net.osparty.ui;

import net.osparty.FavoritesService;
import net.osparty.KillcountService;
import net.osparty.WorldPinger;
import net.osparty.api.PartyService;
import net.osparty.model.Activity;
import net.osparty.model.Party;
import net.osparty.party.LiveParty;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.api.vars.AccountType;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.http.api.worlds.WorldRegion;

/**
 * The "Faves" tab. Shows two collapsible sections:
 * <ol>
 *   <li><b>Favorites</b> — open parties where the host or any member is in the
 *       player's local favourites list (starred from the Search tab).
 *   <li><b>Friends</b> — open parties hosted by someone in the in-game friends list.
 * </ol>
 * Each section renders the same party cards as the Search tab (via the shared
 * {@link PartyCardPanel} base) so Apply / Cancel / cooldown all work identically.
 */
class FriendsPanel extends PartyCardPanel
{
	private static final int REFRESH_MS = 10_000;

	private List<Party> lastAll = new ArrayList<>();
	private Timer refreshTimer;

	// ---- UI ----------------------------------------------------------------
	private final JLabel statusLabel;
	private final JPanel favoritesContent;
	private final JPanel friendsContent;
	private final JLabel favoritesCount;
	private final JLabel friendsCount;
	private boolean favExpanded = true;
	private boolean friendsExpanded = true;

	// ---- constructor -------------------------------------------------------

	FriendsPanel(PartyService partyService, Supplier<String> playerNameSupplier,
		PartyState partyState, LiveParty liveParty,
		Supplier<AccountType> accountTypeSupplier,
		KillcountService killcountService,
		WorldPinger worldPinger,
		IntFunction<WorldRegion> worldRegionResolver,
		IntFunction<String> worldAddressResolver,
		FavoritesService favoritesService,
		Supplier<Set<String>> friendNamesSupplier)
	{
		super(partyService, playerNameSupplier, partyState, liveParty, accountTypeSupplier,
			killcountService, worldPinger, worldRegionResolver, worldAddressResolver,
			favoritesService, friendNamesSupplier);

		setLayout(new BorderLayout(0, 0));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		// ---- top sections (Favorites + Friends) in a scrollable column -----
		JPanel sections = new JPanel();
		sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
		sections.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Favorites section
		JPanel favHeader = buildSectionHeader("★  Favorites", new Color(0xFF8C00),
			() -> { favExpanded = !favExpanded; render(); });
		favoritesCount = (JLabel) favHeader.getClientProperty("count");
		favoritesContent = new JPanel();
		favoritesContent.setLayout(new BoxLayout(favoritesContent, BoxLayout.Y_AXIS));
		favoritesContent.setBackground(ColorScheme.DARK_GRAY_COLOR);
		favoritesContent.setAlignmentX(Component.LEFT_ALIGNMENT);

		// Friends section
		JPanel friendsHeader = buildSectionHeader("♥  Friends", new Color(0x56A0D3),
			() -> { friendsExpanded = !friendsExpanded; render(); });
		friendsCount = (JLabel) friendsHeader.getClientProperty("count");
		friendsContent = new JPanel();
		friendsContent.setLayout(new BoxLayout(friendsContent, BoxLayout.Y_AXIS));
		friendsContent.setBackground(ColorScheme.DARK_GRAY_COLOR);
		friendsContent.setAlignmentX(Component.LEFT_ALIGNMENT);

		sections.add(favHeader);
		sections.add(favoritesContent);
		sections.add(Box.createVerticalStrut(6));
		sections.add(friendsHeader);
		sections.add(friendsContent);
		sections.add(Box.createVerticalStrut(6));
		sections.add(Box.createVerticalGlue());

		JScrollPane scroll = new JScrollPane(sections);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// ---- status + refresh row at the bottom ----------------------------
		statusLabel = new JLabel("Fetching parties…");
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

		JButton refreshBtn = new JButton("↺");
		refreshBtn.setFocusPainted(false);
		refreshBtn.setToolTipText("Refresh now");
		refreshBtn.addActionListener(e -> fetchAndRender());

		JPanel bottom = new JPanel(new BorderLayout(4, 0));
		bottom.setBackground(ColorScheme.DARK_GRAY_COLOR);
		bottom.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		bottom.add(statusLabel, BorderLayout.CENTER);
		bottom.add(refreshBtn, BorderLayout.EAST);

		add(scroll, BorderLayout.CENTER);
		add(bottom, BorderLayout.SOUTH);

		// ---- auto-refresh every 10 s ---------------------------------------
		refreshTimer = new Timer(REFRESH_MS, e -> fetchAndRender());
		refreshTimer.start();
		fetchAndRender();
	}

	// ---- abstract impl ----------------------------------------------------

	@Override
	protected void setStatus(String text)
	{
		statusLabel.setText(text);
	}

	@Override
	protected boolean isLocalLearner()
	{
		return false;
	}

	/** When a star is toggled in this panel, re-render (unfavouriting removes a card). */
	@Override
	protected void onFavoriteToggled(Party party)
	{
		SwingUtilities.invokeLater(this::render);
	}

	// ---- data --------------------------------------------------------------

	private void fetchAndRender()
	{
		partyService.searchParties(null, playerNameSupplier.get(),
			parties -> {
				lastAll = parties != null ? parties : new ArrayList<>();
				SwingUtilities.invokeLater(this::render);
			},
			error -> SwingUtilities.invokeLater(() -> setStatus("Could not load parties.")));
	}

	private void render()
	{
		applyButtons.clear();
		partiesById.clear();

		Set<String> friends = friendNamesSupplier != null ? friendNamesSupplier.get() : null;

		List<Party> faves = new ArrayList<>();
		List<Party> friendParties = new ArrayList<>();

		for (Party p : lastAll)
		{
			if (p.isFull())
			{
				continue;
			}
			boolean isFave = favoritesService != null && favoritesService.hasAnyFavorite(p);
			boolean isFriend = friends != null && p.getHost() != null
				&& friends.contains(FavoritesService.normalize(p.getHost()).toLowerCase());

			if (isFave)
			{
				faves.add(p);
			}
			// Friends section: only show if NOT already in favorites (avoid duplication)
			if (isFriend && !isFave)
			{
				friendParties.add(p);
			}
		}

		// Sort newest first inside each section
		faves.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));
		friendParties.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));

		populateSection(favoritesContent, faves, favExpanded,
			faves.isEmpty() ? "No open parties with favourited players." : null);
		populateSection(friendsContent, friendParties, friendsExpanded,
			friendParties.isEmpty() ? "No open parties from OSRS friends." : null);

		updateCountBadge(favoritesCount, faves.size());
		updateCountBadge(friendsCount, friendParties.size());

		int total = faves.size() + friendParties.size();
		setStatus(total == 0 ? "No matching parties right now." :
			total + " open " + (total == 1 ? "party" : "parties") + " found.");

		updateAllButtons();
	}

	private void populateSection(JPanel content, List<Party> parties, boolean expanded, String emptyMsg)
	{
		content.removeAll();
		if (!expanded)
		{
			content.revalidate();
			content.repaint();
			return;
		}
		if (parties.isEmpty() && emptyMsg != null)
		{
			JLabel empty = new JLabel(emptyMsg);
			empty.setFont(FontManager.getRunescapeSmallFont());
			empty.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
			empty.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
			empty.setAlignmentX(Component.LEFT_ALIGNMENT);
			content.add(empty);
		}
		for (Party party : parties)
		{
			Activity activity = Activity.fromId(party.getActivity());
			JPanel card = buildPartyCard(activity, party);
			card.setAlignmentX(Component.LEFT_ALIGNMENT);
			content.add(card);
			content.add(Box.createVerticalStrut(4));
		}
		content.revalidate();
		content.repaint();
	}

	// ---- section header builder -------------------------------------------

	/**
	 * Builds a collapsible section header. Stores the count badge label as a
	 * client property keyed {@code "count"} so the caller can update it.
	 */
	private static JPanel buildSectionHeader(String title, Color titleColor, Runnable onToggle)
	{
		JPanel header = new JPanel(new BorderLayout(6, 0));
		header.setBackground(new Color(0x3A3A3A));
		header.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(5, 8, 5, 8)));
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));
		header.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel titleLabel = new JLabel(title);
		titleLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		titleLabel.setForeground(titleColor);

		JLabel countLabel = new JLabel("0");
		countLabel.setFont(FontManager.getRunescapeSmallFont());
		countLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);

		header.add(titleLabel, BorderLayout.CENTER);
		header.add(countLabel, BorderLayout.EAST);

		header.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				onToggle.run();
			}
		});
		header.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		header.putClientProperty("count", countLabel);
		return header;
	}

	private static void updateCountBadge(JLabel badge, int count)
	{
		if (badge == null)
		{
			return;
		}
		badge.setText(count == 0 ? "" : "(" + count + ")");
	}
}
