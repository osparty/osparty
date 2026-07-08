package net.osparty.ui;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.ImageIcon;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.util.ImageUtil;

/**
 * Icons for {@link DiscordBadge}, sized to sit next to a name like the account-type and
 * favourite-star icons (≤14px). Cache sprites load asynchronously through the injected
 * {@link SpriteManager} — same pattern as the favourite star in {@code PartyCardPanel} — and
 * the content-creator badge is a bundled PNG (no YouTube icon exists in the game cache).
 *
 * <p>{@link #preload} is idempotent and called from the card panels' constructors, so the
 * icons are ready long before the first network snapshot builds a card.
 */
final class BadgeIcons
{
	private static final int MAX_SIZE = 14;

	private static final Map<DiscordBadge, ImageIcon> ICONS = new ConcurrentHashMap<>();
	private static final AtomicBoolean LOADED = new AtomicBoolean();

	private BadgeIcons()
	{
	}

	static void preload(SpriteManager spriteManager)
	{
		if (spriteManager == null || !LOADED.compareAndSet(false, true))
		{
			return;
		}
		for (DiscordBadge badge : DiscordBadge.values())
		{
			if (badge.getSpriteId() < 0)
			{
				put(badge, ImageUtil.loadImageResource(BadgeIcons.class, "/net/osparty/icons/youtube.png"));
				continue;
			}
			spriteManager.getSpriteAsync(badge.getSpriteId(), 0, img -> put(badge, img));
		}
	}

	/** @return the badge icon, or {@code null} while its sprite hasn't loaded (skip rendering). */
	static ImageIcon get(DiscordBadge badge)
	{
		return ICONS.get(badge);
	}

	private static void put(DiscordBadge badge, BufferedImage img)
	{
		if (img == null)
		{
			return;
		}
		// Fit within MAX_SIZE preserving aspect ratio; the 13x13 badge sprites stay native.
		double scale = Math.min(1.0,
			Math.min((double) MAX_SIZE / img.getWidth(), (double) MAX_SIZE / img.getHeight()));
		if (scale < 1.0)
		{
			img = ImageUtil.resizeImage(img,
				Math.max(1, (int) Math.round(img.getWidth() * scale)),
				Math.max(1, (int) Math.round(img.getHeight() * scale)));
		}
		ICONS.put(badge, new ImageIcon(img));
	}
}
