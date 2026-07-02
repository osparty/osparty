package net.osparty.ui;

import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import net.runelite.api.vars.AccountType;
import net.runelite.client.util.ImageUtil;

/**
 * The in-game account-type badges (ironman variants), bundled as PNGs so we can
 * show the real icon next to a player's name instead of a text tag.
 */
final class AccountIcons
{
	/** Upper bound on either dimension; sources are already ~10x13, so they render near-native. */
	private static final int MAX_SIZE = 14;

	private static final ImageIcon IRONMAN = load("ironman");
	private static final ImageIcon HARDCORE_IRONMAN = load("hardcore_ironman");
	private static final ImageIcon ULTIMATE_IRONMAN = load("ultimate_ironman");
	private static final ImageIcon GROUP_IRONMAN = load("group_ironman");
	private static final ImageIcon HARDCORE_GROUP_IRONMAN = load("hardcore_group_ironman");

	private AccountIcons()
	{
	}

	private static ImageIcon load(String name)
	{
		try
		{
			BufferedImage img = ImageUtil.loadImageResource(AccountIcons.class, "/net/osparty/icons/" + name + ".png");
			if (img == null)
			{
				return null;
			}
			// Scale to fit within MAX_SIZE while preserving the source aspect ratio — a fixed
			// square resize stretched these portrait (10x13) badges wide and oversized them
			// versus the host crown. Sources are already small, so this is usually near-native.
			double scale = Math.min(1.0,
				Math.min((double) MAX_SIZE / img.getWidth(), (double) MAX_SIZE / img.getHeight()));
			if (scale >= 1.0)
			{
				return new ImageIcon(img);
			}
			int w = Math.max(1, (int) Math.round(img.getWidth() * scale));
			int h = Math.max(1, (int) Math.round(img.getHeight() * scale));
			return new ImageIcon(ImageUtil.resizeImage(img, w, h));
		}
		catch (Exception e)
		{
			return null;
		}
	}

	/** @return the badge for {@code type}, or {@code null} for a normal account / unknown. */
	static ImageIcon forType(AccountType type)
	{
		if (type == null)
		{
			return null;
		}
		switch (type)
		{
			case IRONMAN:
				return IRONMAN;
			case HARDCORE_IRONMAN:
				return HARDCORE_IRONMAN;
			case ULTIMATE_IRONMAN:
				return ULTIMATE_IRONMAN;
			case GROUP_IRONMAN:
				return GROUP_IRONMAN;
			case HARDCORE_GROUP_IRONMAN:
				return HARDCORE_GROUP_IRONMAN;
			default:
				return null;
		}
	}
}
