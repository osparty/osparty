package net.osparty.ui;

import net.osparty.service.FavoritesService;
import net.osparty.service.KillcountService;
import net.osparty.service.PlayerFlagService;
import net.osparty.tools.WorldPinger;
import net.osparty.api.BoardService;
import net.osparty.api.BoardSubscription;
import net.osparty.model.Activity;
import net.osparty.model.Advertisement;
import net.osparty.party.LivePartyBackend;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;

import net.osparty.service.BlockListService;
import net.runelite.api.vars.AccountType;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.http.api.worlds.WorldRegion;

/**
 * The "Favorites" tab: two collapsible sections, Favorites (parties with a starred player) and
 * Friends (parties hosted by an in-game friend), rendered as Search-tab cards via {@link PartyCardPanel}.
 */
class FavoritesPanel extends PartyCardPanel
{
	private List<Advertisement> lastAll = new ArrayList<>();
	private BoardSubscription subscription;

	private final JLabel statusLabel;
	private final JPanel favoritesContent;
	private final JPanel friendsContent;
	private final SectionHeader.Collapsible favoritesHeader;
	private final SectionHeader.Collapsible friendsHeader;
	private boolean favoritesExpanded = true;
	private boolean friendsExpanded = true;

	FavoritesPanel(BoardService boardService, Supplier<String> playerNameSupplier,
		PartyState partyState, LivePartyBackend liveParty,
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
		super(boardService, playerNameSupplier, partyState, liveParty, accountTypeSupplier,
			killcountService, worldPinger, worldRegionResolver, worldAddressResolver,
			favoritesService, blockListService, friendNamesSupplier, spriteManager, config);

		setLayout(new BorderLayout(0, 0));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		JPanel sections = new JPanel();
		sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
		sections.setBackground(ColorScheme.DARK_GRAY_COLOR);

		friendsHeader = SectionHeader.collapsible("Friends",
			() -> { friendsExpanded = !friendsExpanded; render(); });
		friendsContent = sectionBody();

		favoritesHeader = SectionHeader.collapsible("Favorites",
			() -> { favoritesExpanded = !favoritesExpanded; render(); });
		favoritesContent = sectionBody();

		sections.add(friendsHeader.panel);
		sections.add(friendsContent);
		sections.add(Box.createVerticalStrut(6));
		sections.add(favoritesHeader.panel);
		sections.add(favoritesContent);
		sections.add(Box.createVerticalStrut(6));
		sections.add(Box.createVerticalGlue());

		loadHeaderSprite(spriteManager, friendsHeader, 782);   // TAB_FRIENDS
		loadHeaderSprite(spriteManager, favoritesHeader, 1131); // WORLD_SWITCHER_STAR_MEMBERS

		JScrollPane scroll = new JScrollPane(sections);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// ---- status line at the bottom (same insets/placement as the Search tab) ----
		statusLabel = new JLabel("Fetching parties…");
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

		add(scroll, BorderLayout.CENTER);
		add(statusLabel, BorderLayout.SOUTH);

		// Subscribe to the live party list only while this tab is visible (socket push, no polling).
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

	private static JPanel sectionBody()
	{
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);
		body.setAlignmentX(Component.LEFT_ALIGNMENT);
		return body;
	}

	/** getSpriteAsync calls back on the client thread when the sprite isn't cached, so hop to the EDT. */
	private static void loadHeaderSprite(SpriteManager spriteManager, SectionHeader.Collapsible header, int spriteId)
	{
		if (spriteManager == null)
		{
			return;
		}
		spriteManager.getSpriteAsync(spriteId, 0, img -> {
			if (img == null)
			{
				return;
			}
			ImageIcon icon = new ImageIcon(ImageUtil.resizeImage(img, 12, 12));
			SwingUtilities.invokeLater(() -> header.setIcon(icon));
		});
	}

	@Override
	protected void setStatus(String text)
	{
		statusLabel.setText(text);
	}

	/** When a star is toggled in this panel, re-render (unfavouriting removes a card). */
	@Override
	protected void onFavoriteToggled(Advertisement ad)
	{
		SwingUtilities.invokeLater(this::render);
	}

	/** When a host is blocked/unblocked here, re-render (updates the Blocked list and favourite cards). */
	@Override
	protected void onBlockToggled(Advertisement ad)
	{
		SwingUtilities.invokeLater(this::render);
	}

	private void startSubscription()
	{
		if (subscription != null)
		{
			return;
		}
		subscription = boardService.subscribeAds(
			ads -> SwingUtilities.invokeLater(() -> acceptAds(ads)),
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

	private void acceptAds(List<Advertisement> ads)
	{
		lastAll = ads != null ? ads : new ArrayList<>();
		render();
	}

	void render()
	{
		applyButtons.clear();
		adsById.clear();
		reasonLabels.clear();
		rolePickers.clear();

		Set<String> friends = friendNamesSupplier != null ? friendNamesSupplier.get() : null;

		List<Advertisement> favorites = new ArrayList<>();
		List<Advertisement> friendParties = new ArrayList<>();

		for (Advertisement p : lastAll)
		{
			// Keep favourite/block entries' names current as we see these accounts live.
			favoritesService.observeAd(p);
			blockListService.observeAd(p);
			if (p.isFull())
			{
				continue;
			}
			boolean isFave = favoritesService.hasAnyFavorite(p);
			boolean isFriend = friends != null && p.getHost() != null
				&& friends.contains(PlayerFlagService.normalize(p.getHost()));

			if (isFave)
			{
				favorites.add(p);
			}
			// Friends section: only show if NOT already in favorites (avoid duplication)
			if (isFriend && !isFave)
			{
				friendParties.add(p);
			}
		}

		// Sort newest first inside each section
		favorites.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));
		friendParties.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));

		populateSection(favoritesContent, favorites, favoritesExpanded,
			favorites.isEmpty() ? "No open parties with favorited players." : null);
		populateSection(friendsContent, friendParties, friendsExpanded,
			friendParties.isEmpty() ? "No open parties from OSRS friends." : null);

		favoritesHeader.setCount(favorites.size());
		friendsHeader.setCount(friendParties.size());
		favoritesHeader.setExpanded(favoritesExpanded);
		friendsHeader.setExpanded(friendsExpanded);

		// Counts live in the per-section badges; the status line only carries the empty state.
		int total = favorites.size() + friendParties.size();
		setStatus(total == 0 ? "No parties to show." : "");

		updateAllButtons();
	}

	private void populateSection(JPanel content, List<Advertisement> ads, boolean expanded, String emptyMsg)
	{
		content.removeAll();
		if (!expanded)
		{
			content.revalidate();
			content.repaint();
			return;
		}
		if (ads.isEmpty() && emptyMsg != null)
		{
			JLabel empty = new JLabel(emptyMsg);
			empty.setFont(FontManager.getRunescapeSmallFont());
			empty.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
			empty.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
			empty.setAlignmentX(Component.LEFT_ALIGNMENT);
			content.add(empty);
		}
		for (Advertisement ad : ads)
		{
			Activity activity = Activity.fromId(ad.getActivity());
			JPanel card = buildPartyCard(activity, ad);
			card.setAlignmentX(Component.LEFT_ALIGNMENT);
			content.add(card);
			content.add(Box.createVerticalStrut(4));
		}
		content.revalidate();
		content.repaint();
	}

}
