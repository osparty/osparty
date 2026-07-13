package net.osparty.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import net.runelite.client.util.ImageUtil;

/**
 * Tab-bar icons drawn at load time with {@link Graphics2D} (not assets/fonts) so they render
 * identically on every platform. Mirrors {@link StatusIcons}.
 */
final class TabIcons
{
	static final ImageIcon SEARCH = boxed(search());
	/** The bundled OSRS chat-channel "group of people" icon; falls back to the drawn crowd. */
	static final ImageIcon PARTY = loadParty();
	static final ImageIcon FAVORITES = boxed(star());
	static final ImageIcon HISTORY = boxed(history());
	static final ImageIcon EDIT = boxed(edit());
	static final ImageIcon BLOCK = boxed(block());

	/**
	 * Shared canvas size (px) every tab icon is centred into, so all tabs render the same size.
	 * Kept at 16: larger tips the five-tab in-party bar over the sidebar width, wrapping a tab off-screen.
	 */
	static final int BOX = 16;

	/** Tab icon size in px. A touch larger than {@link StatusIcons} so tabs stay easy to hit. */
	private static final int SIZE = 16;
	/** Neutral light grey for the transient fallbacks shown only until a sprite loads. */
	private static final Color INK = new Color(0xC8, 0xC8, 0xC8);
	/** OSRS interface gold for the permanently-drawn tabs (Party/History). */
	private static final Color GOLD = new Color(0xEC, 0xC1, 0x3B);
	/** Shading tones for the hourglass (History): darker outline and near-black sand. */
	private static final Color RIM = new Color(0x7A, 0x5A, 0x12);
	private static final Color HAND = new Color(0x3A, 0x2A, 0x10);

	private TabIcons()
	{
	}

	/** Centre a glyph into the shared {@link #BOX}×{@link #BOX} canvas so every tab icon is the same size. */
	static ImageIcon boxed(BufferedImage img)
	{
		double scale = Math.min(1.0, Math.min((double) BOX / img.getWidth(), (double) BOX / img.getHeight()));
		int w = Math.max(1, (int) Math.round(img.getWidth() * scale));
		int h = Math.max(1, (int) Math.round(img.getHeight() * scale));
		BufferedImage scaled = (w == img.getWidth() && h == img.getHeight()) ? img : ImageUtil.resizeImage(img, w, h);
		BufferedImage canvas = new BufferedImage(BOX, BOX, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(scaled, (BOX - w) / 2, (BOX - h) / 2, null);
		g.dispose();
		return new ImageIcon(canvas);
	}

	/** Like {@link #boxed}, but crops transparent margins first so a sparse item sprite fills the box. */
	static ImageIcon boxedTrimmed(BufferedImage img)
	{
		int minX = img.getWidth(), minY = img.getHeight(), maxX = -1, maxY = -1;
		for (int y = 0; y < img.getHeight(); y++)
		{
			for (int x = 0; x < img.getWidth(); x++)
			{
				if ((img.getRGB(x, y) >>> 24) != 0)
				{
					minX = Math.min(minX, x);
					maxX = Math.max(maxX, x);
					minY = Math.min(minY, y);
					maxY = Math.max(maxY, y);
				}
			}
		}
		if (maxX < minX || maxY < minY)
		{
			return boxed(img); // fully transparent — nothing to crop
		}
		return boxed(img.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1));
	}

	/** Magnifying glass: a lens circle with a diagonal handle. */
	private static BufferedImage search()
	{
		BufferedImage img = base();
		Graphics2D g = img.createGraphics();
		hints(g);
		g.setColor(INK);
		g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.drawOval(2, 2, 8, 8);
		g.drawLine(9, 9, 13, 13);
		g.dispose();
		return img;
	}

	/** The bundled chat-channel PNG, or the drawn crowd if the resource can't be loaded. */
	private static ImageIcon loadParty()
	{
		try
		{
			BufferedImage img = ImageUtil.loadImageResource(TabIcons.class, "/net/osparty/icons/chat_channel.png");
			if (img != null)
			{
				return boxed(img);
			}
		}
		catch (Exception e)
		{
			// fall through to the drawn crowd
		}
		return boxed(party());
	}

	/** A little crowd — three heads over a shared shoulder mass: a "group / party" glyph (fallback). */
	private static BufferedImage party()
	{
		BufferedImage img = base();
		Graphics2D g = img.createGraphics();
		hints(g);
		g.setColor(GOLD);
		// Two shoulder domes plus three heads so the silhouette reads as a small crowd.
		g.fillArc(4, 8, 8, 9, 0, 180);    // centre/back figure's shoulders, raised
		g.fillArc(0, 11, 7, 8, 0, 180);   // left figure
		g.fillArc(9, 11, 7, 8, 0, 180);   // right figure
		g.fillOval(6, 2, 5, 5);           // centre head (raised)
		g.fillOval(1, 6, 4, 4);           // left head
		g.fillOval(11, 6, 4, 4);          // right head
		g.dispose();
		return img;
	}

	/** Filled five-point star — favourites. */
	private static BufferedImage star()
	{
		BufferedImage img = base();
		Graphics2D g = img.createGraphics();
		hints(g);
		g.setColor(INK);
		Polygon p = new Polygon();
		double cx = 8, cy = 8.4, outer = 7, inner = 3;
		for (int i = 0; i < 10; i++)
		{
			double r = (i % 2 == 0) ? outer : inner;
			double ang = -Math.PI / 2 + i * Math.PI / 5;
			p.addPoint((int) Math.round(cx + r * Math.cos(ang)), (int) Math.round(cy + r * Math.sin(ang)));
		}
		g.fillPolygon(p);
		g.dispose();
		return img;
	}

	/** A gold shaded hourglass — the "time / history" symbol. */
	private static BufferedImage history()
	{
		BufferedImage img = base();
		Graphics2D g = img.createGraphics();
		hints(g);
		// Two glass bulbs (triangles meeting at the pinch), filled gold with a darker outline.
		Polygon top = new Polygon(new int[]{4, 12, 8}, new int[]{3, 3, 8}, 3);
		Polygon bottom = new Polygon(new int[]{8, 4, 12}, new int[]{8, 13, 13}, 3);
		g.setColor(GOLD);
		g.fillPolygon(top);
		g.fillPolygon(bottom);
		g.setColor(RIM);
		g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.drawPolygon(top);
		g.drawPolygon(bottom);
		// Gold caps top and bottom.
		g.setColor(GOLD);
		g.fillRoundRect(2, 1, 12, 2, 2, 2);
		g.fillRoundRect(2, 13, 12, 2, 2, 2);
		g.setColor(RIM);
		g.drawRoundRect(2, 1, 12, 2, 2, 2);
		g.drawRoundRect(2, 13, 12, 2, 2, 2);
		// Sand: a settled pile in the lower bulb plus a thin falling stream.
		g.setColor(HAND);
		g.fillPolygon(new Polygon(new int[]{6, 10, 8}, new int[]{12, 12, 9}, 3));
		g.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.drawLine(8, 8, 8, 11);
		g.dispose();
		return img;
	}

	/** Pencil on a diagonal — the "edit party" state of the Party tab. */
	private static BufferedImage edit()
	{
		BufferedImage img = base();
		Graphics2D g = img.createGraphics();
		hints(g);
		g.setColor(INK);
		g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		// Pencil body from lower-left to upper-right.
		g.drawLine(3, 12, 11, 4);
		// Tip (lower-left) and eraser end (upper-right) crossbars.
		g.drawLine(3, 12, 5, 12);
		g.drawLine(3, 12, 3, 10);
		g.drawLine(10, 3, 12, 5);
		g.dispose();
		return img;
	}

	/** "No entry" sign — a circle with a diagonal slash: the block list. */
	private static BufferedImage block()
	{
		BufferedImage img = base();
		Graphics2D g = img.createGraphics();
		hints(g);
		g.setColor(INK);
		g.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.drawOval(2, 2, 11, 11);
		g.drawLine(4, 4, 11, 11);
		g.dispose();
		return img;
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
