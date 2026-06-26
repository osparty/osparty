package net.osparty.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import net.runelite.client.util.ImageUtil;

/**
 * Small status glyphs drawn at load time: a green check and a red cross used to
 * show whether a party member is in the host's friends chat. Drawn rather than
 * shipped as assets so they stay crisp at this size and need no network fetch;
 * swap in wiki PNGs here if preferred.
 */
final class StatusIcons
{
	static final ImageIcon CHECK = new ImageIcon(check());
	static final ImageIcon CROSS = new ImageIcon(cross());
	static final ImageIcon CHEVRON_DOWN = new ImageIcon(chevron(true));
	static final ImageIcon CHEVRON_UP = new ImageIcon(chevron(false));
	/** The in-game friends-chat channel icon (from the OSRS wiki). */
	static final ImageIcon FRIENDS_CHAT = loadFriendsChat();
	/** Green/red presence dots shown beside a member's name. */
	static final ImageIcon ONLINE = new ImageIcon(dot(new Color(0x4C, 0xD1, 0x37)));
	static final ImageIcon OFFLINE = new ImageIcon(dot(new Color(0xD1, 0x3A, 0x3A)));

	private static final int SIZE = 14;

	private StatusIcons()
	{
	}

	private static BufferedImage check()
	{
		BufferedImage img = base();
		Graphics2D g = img.createGraphics();
		hints(g);
		g.setColor(new Color(0x4C, 0xD1, 0x37));
		g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.drawLine(3, 8, 6, 11);
		g.drawLine(6, 11, 11, 3);
		g.dispose();
		return img;
	}

	private static BufferedImage cross()
	{
		BufferedImage img = base();
		Graphics2D g = img.createGraphics();
		hints(g);
		g.setColor(new Color(0xD1, 0x3A, 0x3A));
		g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.drawLine(3, 3, 11, 11);
		g.drawLine(11, 3, 3, 11);
		g.dispose();
		return img;
	}

	/** A small chevron pointing down (collapsed) or up (expanded). */
	private static BufferedImage chevron(boolean down)
	{
		BufferedImage img = base();
		Graphics2D g = img.createGraphics();
		hints(g);
		g.setColor(new Color(0xA0, 0xA0, 0xA0));
		g.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		if (down)
		{
			g.drawLine(3, 5, 7, 9);
			g.drawLine(7, 9, 11, 5);
		}
		else
		{
			g.drawLine(3, 9, 7, 5);
			g.drawLine(7, 5, 11, 9);
		}
		g.dispose();
		return img;
	}

	/** A small filled presence dot in the given colour. */
	private static BufferedImage dot(Color color)
	{
		BufferedImage img = base();
		Graphics2D g = img.createGraphics();
		hints(g);
		g.setColor(color);
		g.fillOval(3, 3, 8, 8);
		g.dispose();
		return img;
	}

	private static ImageIcon loadFriendsChat()
	{
		try
		{
			BufferedImage img = ImageUtil.loadImageResource(StatusIcons.class, "/net/osparty/icons/fc.png");
			return new ImageIcon(img.getScaledInstance(12, 14, Image.SCALE_SMOOTH));
		}
		catch (Exception e)
		{
			return null;
		}
	}

	private static BufferedImage base()
	{
		return new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
	}

	private static void hints(Graphics2D g)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
	}
}
