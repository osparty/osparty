package net.osparty.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import net.runelite.client.util.ImageUtil;

/**
 * Small status glyphs drawn at load time (check/cross/chevrons/presence dots).
 * Drawn rather than shipped as assets so they stay crisp at this size and need
 * no network fetch.
 */
final class StatusIcons
{
	static final ImageIcon CHECK = new ImageIcon(check());
	static final ImageIcon CROSS = new ImageIcon(cross());
	static final ImageIcon CHEVRON_DOWN = new ImageIcon(chevron(true));
	static final ImageIcon CHEVRON_UP = new ImageIcon(chevron(false));
	static final ImageIcon KEBAB = new ImageIcon(kebab());
	static final ImageIcon FRIENDS_CHAT = loadFriendsChat();
	static final ImageIcon ONLINE = new ImageIcon(dot(new Color(0x4C, 0xD1, 0x37)));
	static final ImageIcon OFFLINE = new ImageIcon(dot(new Color(0xD1, 0x3A, 0x3A)));
	static final ImageIcon COPY = new ImageIcon(copy());
	static final ImageIcon RUNEWATCH = new ImageIcon(runewatch());
	static final ImageIcon STAR_FILLED = new ImageIcon(star(true));
	static final ImageIcon STAR_OUTLINE = new ImageIcon(star(false));
	static final ImageIcon CROWN = loadCrown();
	static final ImageIcon PLUS = new ImageIcon(plus());
	/** "No entry" ban glyph for the block toggle: red when blocked, grey when not. */
	static final ImageIcon BLOCK_ON = new ImageIcon(ban(new Color(0xD1, 0x3A, 0x3A)));
	static final ImageIcon BLOCK_OFF = new ImageIcon(ban(new Color(0x90, 0x90, 0x90)));

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

	/** Three vertical dots — the "more actions" (kebab) glyph, matching the chevron's grey. */
	private static BufferedImage kebab()
	{
		BufferedImage img = base();
		Graphics2D g = img.createGraphics();
		hints(g);
		g.setColor(new Color(0xA0, 0xA0, 0xA0));
		g.fillOval(6, 2, 3, 3);
		g.fillOval(6, 6, 3, 3);
		g.fillOval(6, 10, 3, 3);
		g.dispose();
		return img;
	}

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

	/** Two overlapping pages — the classic "copy to clipboard" glyph. OS-font independent. */
	private static BufferedImage copy()
	{
		BufferedImage img = base();
		Graphics2D g = img.createGraphics();
		hints(g);
		g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		// back page
		g.setColor(new Color(0xA0, 0xA0, 0xA0));
		g.drawRoundRect(3, 2, 6, 8, 2, 2);
		// front page: fill with the darker panel colour so the overlap reads, then outline
		g.setColor(new Color(0x1E, 0x1E, 0x1E));
		g.fillRoundRect(5, 4, 6, 8, 2, 2);
		g.setColor(new Color(0xA0, 0xA0, 0xA0));
		g.drawRoundRect(5, 4, 6, 8, 2, 2);
		g.dispose();
		return img;
	}

	/** Red warning triangle with a white exclamation — RuneWatch flag. */
	private static BufferedImage runewatch()
	{
		BufferedImage img = base();
		Graphics2D g = img.createGraphics();
		hints(g);
		Polygon tri = new Polygon(new int[]{7, 1, 13}, new int[]{1, 12, 12}, 3);
		g.setColor(new Color(0xD1, 0x3A, 0x3A));
		g.fillPolygon(tri);
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.drawLine(7, 5, 7, 9);
		g.fillOval(6, 10, 2, 2);
		g.dispose();
		return img;
	}

	private static Polygon starPolygon()
	{
		Polygon p = new Polygon();
		double cx = 7, cy = 7.3, outer = 6.2, inner = 2.6;
		for (int i = 0; i < 10; i++)
		{
			double r = (i % 2 == 0) ? outer : inner;
			double ang = -Math.PI / 2 + i * Math.PI / 5;
			p.addPoint((int) Math.round(cx + r * Math.cos(ang)), (int) Math.round(cy + r * Math.sin(ang)));
		}
		return p;
	}

	/** A "no entry" sign: a circle with a diagonal slash. Drawn, so it's OS-font independent. */
	private static BufferedImage ban(Color color)
	{
		BufferedImage img = base();
		Graphics2D g = img.createGraphics();
		hints(g);
		g.setColor(color);
		g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.drawOval(2, 2, 9, 9);
		g.drawLine(4, 4, 9, 9);
		g.dispose();
		return img;
	}

	/** A green plus glyph (the "add/save" button). */
	private static BufferedImage plus()
	{
		BufferedImage img = base();
		Graphics2D g = img.createGraphics();
		hints(g);
		g.setColor(new Color(0x4C, 0xD1, 0x37));
		g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.drawLine(7, 3, 7, 11);
		g.drawLine(3, 7, 11, 7);
		g.dispose();
		return img;
	}

	/** The real OSRS Jagex Moderator emblem (bundled PNG), used at its native pixel size. */
	private static ImageIcon loadCrown()
	{
		try
		{
			return new ImageIcon(ImageUtil.loadImageResource(StatusIcons.class, "/net/osparty/icons/mod_crown.png"));
		}
		catch (Exception e)
		{
			return null;
		}
	}

	private static BufferedImage star(boolean filled)
	{
		BufferedImage img = base();
		Graphics2D g = img.createGraphics();
		hints(g);
		Polygon p = starPolygon();
		g.setColor(new Color(0xF0, 0xC0, 0x00));
		if (filled)
		{
			g.fillPolygon(p);
		}
		else
		{
			g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.drawPolygon(p);
		}
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
