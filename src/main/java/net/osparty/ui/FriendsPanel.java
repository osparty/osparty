package net.osparty.ui;

import net.osparty.FavoritesService;
import net.osparty.KillcountService;
import net.osparty.WorldPinger;
import net.osparty.api.PartyService;
import net.osparty.api.PartySubscription;
import net.osparty.model.Activity;
import net.osparty.model.Party;
import net.osparty.party.LiveParty;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import net.runelite.api.vars.AccountType;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.http.api.worlds.WorldRegion;

/**
 * The "Favorites" tab. Shows two collapsible sections:
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
	private List<Party> lastAll = new ArrayList<>();
	private PartySubscription subscription;

	// ---- UI ----------------------------------------------------------------
	private final JLabel statusLabel;
	private final JPanel favoritesContent;
	private final JPanel friendsContent;
	private final JLabel favoritesCount;
	private final JLabel friendsCount;
	private final JLabel favoritesCaret;
	private final JLabel friendsCaret;
	private final JPanel blockedContent;
	private final JLabel blockedCount;
	private final JLabel blockedCaret;
	private boolean favExpanded = true;
	private boolean friendsExpanded = true;
	private boolean blockedExpanded = false;

	/** RuneLite's config-section caret (grey): points right when collapsed, down when expanded. */
	private static final ImageIcon CARET_COLLAPSED = caret(0);
	private static final ImageIcon CARET_EXPANDED = caret(Math.PI / 2);

	private static ImageIcon caret(double rotation)
	{
		BufferedImage arrow = ImageUtil.loadImageResource(FriendsPanel.class, "/util/arrow_right.png");
		if (arrow == null)
		{
			return null;
		}
		BufferedImage grey = ImageUtil.luminanceOffset(arrow, -121);
		if (rotation != 0)
		{
			grey = ImageUtil.rotateImage(grey, rotation);
		}
		return new ImageIcon(grey);
	}

	// ---- constructor -------------------------------------------------------

	FriendsPanel(PartyService partyService, Supplier<String> playerNameSupplier,
		PartyState partyState, LiveParty liveParty,
		Supplier<AccountType> accountTypeSupplier,
		KillcountService killcountService,
		WorldPinger worldPinger,
		IntFunction<WorldRegion> worldRegionResolver,
		IntFunction<String> worldAddressResolver,
		FavoritesService favoritesService,
		net.osparty.BlockListService blockListService,
		Supplier<Set<String>> friendNamesSupplier,
		SpriteManager spriteManager)
	{
		super(partyService, playerNameSupplier, partyState, liveParty, accountTypeSupplier,
			killcountService, worldPinger, worldRegionResolver, worldAddressResolver,
			favoritesService, blockListService, friendNamesSupplier, spriteManager);

		setLayout(new BorderLayout(0, 0));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		// ---- top sections (Favorites + Friends) in a scrollable column -----
		JPanel sections = new JPanel();
		sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
		sections.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Friends section
		JPanel friendsHeader = buildSectionHeader("Friends",
			() -> { friendsExpanded = !friendsExpanded; render(); });
		friendsCount = (JLabel) friendsHeader.getClientProperty("count");
		friendsCaret = (JLabel) friendsHeader.getClientProperty("caret");
		JLabel friendsTitleLabel = (JLabel) friendsHeader.getClientProperty("title");
		friendsContent = new JPanel();
		friendsContent.setLayout(new BoxLayout(friendsContent, BoxLayout.Y_AXIS));
		friendsContent.setBackground(ColorScheme.DARK_GRAY_COLOR);
		friendsContent.setAlignmentX(Component.LEFT_ALIGNMENT);

		// Favorites section
		JPanel favHeader = buildSectionHeader("Favorites",
			() -> { favExpanded = !favExpanded; render(); });
		favoritesCount = (JLabel) favHeader.getClientProperty("count");
		favoritesCaret = (JLabel) favHeader.getClientProperty("caret");
		JLabel favTitleLabel = (JLabel) favHeader.getClientProperty("title");
		favoritesContent = new JPanel();
		favoritesContent.setLayout(new BoxLayout(favoritesContent, BoxLayout.Y_AXIS));
		favoritesContent.setBackground(ColorScheme.DARK_GRAY_COLOR);
		favoritesContent.setAlignmentX(Component.LEFT_ALIGNMENT);

		// Blocked section: a management list of blocked players (name + unblock), independent
		// of the live party list. Collapsed by default.
		JPanel blockedHeader = buildSectionHeader("Blocked",
			() -> { blockedExpanded = !blockedExpanded; render(); });
		blockedCount = (JLabel) blockedHeader.getClientProperty("count");
		blockedCaret = (JLabel) blockedHeader.getClientProperty("caret");
		JLabel blockedTitleLabel = (JLabel) blockedHeader.getClientProperty("title");
		if (blockedTitleLabel != null)
		{
			// Drawn icon (no sprite fetch needed), sized to match the Friends/Favorites glyphs.
			blockedTitleLabel.setIcon(StatusIcons.BLOCK_ON);
			blockedTitleLabel.setText("  Blocked");
		}
		blockedContent = new JPanel();
		blockedContent.setLayout(new BoxLayout(blockedContent, BoxLayout.Y_AXIS));
		blockedContent.setBackground(ColorScheme.DARK_GRAY_COLOR);
		blockedContent.setAlignmentX(Component.LEFT_ALIGNMENT);

		sections.add(friendsHeader);
		sections.add(friendsContent);
		sections.add(Box.createVerticalStrut(6));
		sections.add(favHeader);
		sections.add(favoritesContent);
		sections.add(Box.createVerticalStrut(6));
		sections.add(blockedHeader);
		sections.add(blockedContent);
		sections.add(Box.createVerticalStrut(6));
		sections.add(Box.createVerticalGlue());

		if (spriteManager != null)
		{
			// 782 = TAB_FRIENDS
			if (friendsTitleLabel != null)
			{
				spriteManager.getSpriteAsync(782, 0, img -> {
					if (img != null)
					{
						java.awt.image.BufferedImage scaled = ImageUtil.resizeImage(img, 12, 12);
						friendsTitleLabel.setIcon(new javax.swing.ImageIcon(scaled));
						friendsTitleLabel.setText("  Friends");
					}
				});
			}
			// 1131 = WORLD_SWITCHER_STAR_MEMBERS
			if (favTitleLabel != null)
			{
				spriteManager.getSpriteAsync(1131, 0, img -> {
					if (img != null)
					{
						java.awt.image.BufferedImage scaled = ImageUtil.resizeImage(img, 12, 12);
						favTitleLabel.setIcon(new javax.swing.ImageIcon(scaled));
						favTitleLabel.setText("  Favorites");
					}
				});
			}
		}

		JScrollPane scroll = new JScrollPane(sections);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// ---- status + refresh row at the bottom ----------------------------
		statusLabel = new JLabel("Fetching parties…");
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

		JPanel bottom = new JPanel(new BorderLayout(4, 0));
		bottom.setBackground(ColorScheme.DARK_GRAY_COLOR);
		bottom.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		bottom.add(statusLabel, BorderLayout.CENTER);

		add(scroll, BorderLayout.CENTER);
		add(bottom, BorderLayout.SOUTH);

		// Subscribe to the live party list only while this tab is visible; the socket
		// pushes the full list and every change, so there is no polling.
		addAncestorListener(new AncestorListener()
		{
			@Override
			public void ancestorAdded(AncestorEvent event)
			{
				startSubscription();
			}

			@Override
			public void ancestorRemoved(AncestorEvent event)
			{
				stopSubscription();
			}

			@Override
			public void ancestorMoved(AncestorEvent event)
			{
			}
		});
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

	/** When a host is blocked/unblocked here, re-render (updates the Blocked list and favourite cards). */
	@Override
	protected void onBlockToggled(Party party)
	{
		SwingUtilities.invokeLater(this::render);
	}

	// ---- data --------------------------------------------------------------

	private void startSubscription()
	{
		if (subscription != null)
		{
			return;
		}
		subscription = partyService.subscribeParties(
			parties -> SwingUtilities.invokeLater(() -> acceptParties(parties)),
			error -> { /* transient socket drop; a reconnect re-subscribes and re-snapshots */ });
	}

	private void stopSubscription()
	{
		if (subscription != null)
		{
			subscription.close();
			subscription = null;
		}
	}

	private void acceptParties(List<Party> parties)
	{
		lastAll = parties != null ? parties : new ArrayList<>();
		render();
	}

	void render()
	{
		applyButtons.clear();
		partiesById.clear();
		reasonLabels.clear();
		rolePickers.clear();

		Set<String> friends = friendNamesSupplier != null ? friendNamesSupplier.get() : null;

		List<Party> faves = new ArrayList<>();
		List<Party> friendParties = new ArrayList<>();

		for (Party p : lastAll)
		{
			// Keep favourite/block entries' names current as we see these accounts live.
			favoritesService.observeParty(p);
			if (blockListService != null)
			{
				blockListService.observeParty(p);
			}
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
			faves.isEmpty() ? "No open parties with favorited players." : null);
		populateSection(friendsContent, friendParties, friendsExpanded,
			friendParties.isEmpty() ? "No open parties from OSRS friends." : null);

		renderBlocked();

		updateCountBadge(favoritesCount, faves.size());
		updateCountBadge(friendsCount, friendParties.size());

		updateCaret(favoritesCaret, favExpanded);
		updateCaret(friendsCaret, friendsExpanded);
		updateCaret(blockedCaret, blockedExpanded);

		// Counts live in the per-section badges; the status line only carries the empty state.
		int total = faves.size() + friendParties.size();
		setStatus(total == 0 ? "No matching parties right now." : "");

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

	/** Populate the Blocked section: one row per blocked player, each with an Unblock button. */
	private void renderBlocked()
	{
		blockedContent.removeAll();
		List<net.osparty.store.PlayerFlag> blocked = blockListService == null
			? new ArrayList<>() : blockListService.entries();
		blocked.sort((a, b) -> a.getUsername().compareToIgnoreCase(b.getUsername()));
		updateCountBadge(blockedCount, blocked.size());

		if (!blockedExpanded)
		{
			blockedContent.revalidate();
			blockedContent.repaint();
			return;
		}
		if (blocked.isEmpty())
		{
			JLabel empty = new JLabel("No blocked players.");
			empty.setFont(FontManager.getRunescapeSmallFont());
			empty.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
			empty.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
			empty.setAlignmentX(Component.LEFT_ALIGNMENT);
			blockedContent.add(empty);
		}
		for (net.osparty.store.PlayerFlag flag : blocked)
		{
			JPanel row = new JPanel(new BorderLayout(4, 0));
			row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			row.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 6));
			row.setAlignmentX(Component.LEFT_ALIGNMENT);

			JLabel name = new JLabel(flag.getUsername());
			name.setFont(FontManager.getRunescapeSmallFont());
			name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

			JButton unblock = new JButton("Unblock");
			unblock.setFocusPainted(false);
			unblock.setFont(FontManager.getRunescapeSmallFont());
			unblock.setMargin(new java.awt.Insets(1, 6, 1, 6));
			unblock.addActionListener(e -> {
				blockListService.toggle(flag.getAccountHash(), flag.getUsername());
				SwingUtilities.invokeLater(this::render);
			});

			row.add(name, BorderLayout.CENTER);
			row.add(unblock, BorderLayout.EAST);
			// Cap the height so the Y-axis BoxLayout doesn't stretch each row to fill the panel.
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
			blockedContent.add(row);
			blockedContent.add(Box.createVerticalStrut(4));
		}
		blockedContent.revalidate();
		blockedContent.repaint();
	}

	// ---- section header builder -------------------------------------------

	/**
	 * Builds a collapsible section header. Stores the count badge label as a
	 * client property keyed {@code "count"} so the caller can update it.
	 */
	private static JPanel buildSectionHeader(String title, Runnable onToggle)
	{
		JPanel header = new JPanel(new BorderLayout(6, 0));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(5, 8, 5, 8)));
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));
		header.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel titleLabel = new JLabel(title);
		titleLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		titleLabel.setForeground(ColorScheme.BRAND_ORANGE);

		JLabel countLabel = new JLabel("0");
		countLabel.setFont(FontManager.getRunescapeSmallFont());
		countLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);

		// Expand/collapse caret (down = expanded). render() flips it per state.
		JLabel caretLabel = new JLabel(CARET_EXPANDED);
		caretLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));

		header.add(caretLabel, BorderLayout.WEST);
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
		header.putClientProperty("title", titleLabel);
		header.putClientProperty("caret", caretLabel);
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

	private static void updateCaret(JLabel caret, boolean expanded)
	{
		if (caret == null)
		{
			return;
		}
		caret.setIcon(expanded ? CARET_EXPANDED : CARET_COLLAPSED);
	}
}
