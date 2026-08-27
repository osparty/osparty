package net.osparty.ui;

import net.osparty.service.FavoritesService;
import net.osparty.service.KillcountService;
import net.osparty.service.PlayerFlagService;
import net.osparty.tools.WorldPinger;
import net.osparty.api.BoardService;
import net.osparty.api.BoardSubscription;
import net.osparty.model.Activity;
import net.osparty.model.Advertisement;
import net.osparty.model.Member;
import net.osparty.party.LivePartyBackend;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
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
 * The "Favorites" tab: two collapsible sections, Favorites (parties with a starred player) and Friends
 * (parties with an in-game friend), rendered as Search-tab cards via {@link PartyCardPanel}. Both match
 * the host and every listed member, and each card carries a note naming who put it there.
 */
class FavoritesPanel extends PartyCardPanel
{
	/** How many players a card's note names before it summarises the rest as "+N more". */
	private static final int MAX_NAMED = 2;

	private List<Advertisement> lastAll = new ArrayList<>();
	private BoardSubscription subscription;

	/** Ad id to the line saying who put it in the list, rebuilt per render and read by {@link #cardNote}. */
	private final Map<String, String> notes = new HashMap<>();

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
		notes.clear();

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
			// A party with both a favourite and a friend goes under Favorites only, so no card appears twice.
			List<String> favorited = matches(p, favoritesService::isFavorite);
			if (!favorited.isEmpty())
			{
				favorites.add(p);
				notes.put(p.getId(), note("Favorite", p, favorited));
				continue;
			}
			if (friends == null)
			{
				continue;
			}
			// Friends are only ever known by name: the list comes from the client, which has no player ids.
			List<String> friended = matches(p, (id, name) -> friends.contains(PlayerFlagService.normalize(name)));
			if (!friended.isEmpty())
			{
				friendParties.add(p);
				notes.put(p.getId(), note("Friend", p, friended));
			}
		}

		// Sort newest first inside each section
		favorites.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));
		friendParties.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));

		populateSection(favoritesContent, favorites, favoritesExpanded,
			favorites.isEmpty() ? "No open parties with favorited players." : null);
		populateSection(friendsContent, friendParties, friendsExpanded,
			friendParties.isEmpty() ? "No open parties with OSRS friends." : null);

		favoritesHeader.setCount(favorites.size());
		friendsHeader.setCount(friendParties.size());
		favoritesHeader.setExpanded(favoritesExpanded);
		friendsHeader.setExpanded(friendsExpanded);

		// Counts live in the per-section badges; the status line only carries the empty state.
		int total = favorites.size() + friendParties.size();
		setStatus(total == 0 ? "No parties to show." : "");

		updateAllButtons();
	}

	@Override
	protected String cardNote(Advertisement ad)
	{
		return notes.get(ad.getId());
	}

	/**
	 * The players in {@code ad} that {@code flagged} accepts, host first. Both sections match on the
	 * whole party rather than the host alone: a friend or favourite sitting in someone else's party is
	 * exactly as worth knowing about, and it is the only way to find a party they didn't advertise.
	 */
	static List<String> matches(Advertisement ad, BiPredicate<String, String> flagged)
	{
		List<String> names = new ArrayList<>();
		if (ad.getHost() != null && flagged.test(ad.getHostPlayerId(), ad.getHost()))
		{
			names.add(ad.getHost());
		}
		if (ad.getMembers() == null)
		{
			return names;
		}
		for (Member member : ad.getMembers())
		{
			// The host is listed among the members too, so skip them rather than name them twice.
			if (member == null || member.getName() == null || isHost(ad, member.getName()))
			{
				continue;
			}
			if (flagged.test(member.getPlayerId(), member.getName()))
			{
				names.add(member.getName());
			}
		}
		return names;
	}

	/** e.g. {@code "Favorite: Zezima (host)"}, {@code "Friends: Bob (host), Amy (in party) +2 more"}. */
	static String note(String label, Advertisement ad, List<String> names)
	{
		StringBuilder sb = new StringBuilder(names.size() > 1 ? label + "s: " : label + ": ");
		int shown = Math.min(names.size(), MAX_NAMED);
		for (int i = 0; i < shown; i++)
		{
			String name = names.get(i);
			sb.append(i > 0 ? ", " : "").append(name).append(isHost(ad, name) ? " (host)" : " (in party)");
		}
		if (names.size() > shown)
		{
			sb.append(" +").append(names.size() - shown).append(" more");
		}
		return sb.toString();
	}

	private static boolean isHost(Advertisement ad, String name)
	{
		return ad.getHost() != null
			&& PlayerFlagService.normalize(ad.getHost()).equals(PlayerFlagService.normalize(name));
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
