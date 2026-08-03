package net.osparty.ui;

import net.osparty.OSPartyConfig;
import net.osparty.party.LivePartyBackend;
import net.osparty.party.TilePing;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.TextComponent;

/**
 * Draws party members' map pings on the game scene: a pulsing, expanding ring on
 * the pinged tile in the sender's colour, with the sender's name in the centre.
 * Each ping animates for {@link LivePartyBackend} ping window, then disappears.
 *
 * <p>Pings that fall outside the visible viewport are handled by {@link PingArrowOverlay},
 * which draws an edge arrow above the game interface instead.
 */
public class TilePingOverlay extends Overlay
{
	private static final int MAX_RADIUS = 48;

	private final Client client;
	private final LivePartyBackend liveParty;
	private final OSPartyConfig config;

	public TilePingOverlay(Client client, LivePartyBackend liveParty, OSPartyConfig config)
	{
		this.client = client;
		this.liveParty = liveParty;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.pings())
		{
			return null;
		}
		List<TilePing> pings = liveParty.activePings();
		if (pings.isEmpty())
		{
			return null;
		}

		long now = System.currentTimeMillis();
		int plane = client.getPlane();
		Stroke prev = graphics.getStroke();
		graphics.setStroke(new BasicStroke(2f));
		for (TilePing ping : pings)
		{
			WorldPoint wp = ping.getPoint();
			if (wp == null || wp.getPlane() != plane)
			{
				continue;
			}
			// Should match the live party's ping window for a clean fade-out.
			double t = (now - ping.getCreatedAt()) / (double) config.pingAnimMs();
			if (t < 0 || t > 1)
			{
				continue;
			}

			LocalPoint lp = LocalPoint.fromWorld(client, wp);
			if (lp == null)
			{
				continue;
			}
			Point center = onScreenCanvas(client, wp, lp);
			if (center != null)
			{
				drawPing(graphics, center, lp, ping, t);
			}
			// Off-screen pings are drawn as edge arrows by PingArrowOverlay.
		}
		graphics.setStroke(prev);
		return null;
	}

	/**
	 * The tile's canvas point if it projects inside the visible game viewport, else {@code null}
	 * (out of scene, behind the camera, or scrolled off the edge). Shared with {@link PingArrowOverlay}
	 * so a ping is shown as exactly one of an on-scene ring or an off-screen arrow.
	 */
	static Point onScreenCanvas(Client client, WorldPoint wp)
	{
		LocalPoint lp = LocalPoint.fromWorld(client, wp);
		return lp == null ? null : onScreenCanvas(client, wp, lp);
	}

	/** As {@link #onScreenCanvas(Client, WorldPoint)}, for callers that already resolved the local point. */
	private static Point onScreenCanvas(Client client, WorldPoint wp, LocalPoint lp)
	{
		Point p = Perspective.localToCanvas(client, lp, wp.getPlane());
		if (p == null)
		{
			return null;
		}
		int x0 = client.getViewportXOffset();
		int y0 = client.getViewportYOffset();
		if (p.getX() < x0 || p.getX() > x0 + client.getViewportWidth()
			|| p.getY() < y0 || p.getY() > y0 + client.getViewportHeight())
		{
			return null;
		}
		return p;
	}

	private void drawPing(Graphics2D g, Point center, LocalPoint lp, TilePing ping, double t)
	{
		Color base = ping.getColor();

		// Tile highlight: pulse the tile poly's fill so the destination is obvious.
		Polygon poly = Perspective.getCanvasTilePoly(client, lp);
		if (poly != null)
		{
			double pulse = 0.5 + 0.5 * Math.sin(t * Math.PI * 6); // a couple of beats
			int fillAlpha = clampAlpha((int) (110 * (1 - t) * pulse));
			int lineAlpha = clampAlpha((int) (220 * (1 - t)));
			g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), fillAlpha));
			g.fill(poly);
			g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), lineAlpha));
			g.draw(poly);
		}

		// Expanding ring radiating out from the tile centre.
		int radius = (int) (MAX_RADIUS * t);
		int ringAlpha = clampAlpha((int) (220 * (1 - t)));
		g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), ringAlpha));
		g.drawOval(center.getX() - radius, center.getY() - radius, radius * 2, radius * 2);

		// Sender's name in the centre of the tile.
		String name = ping.getName();
		if (name != null && !name.isEmpty())
		{
			int textWidth = g.getFontMetrics().stringWidth(name);
			int textAlpha = clampAlpha((int) (255 * (1 - t * t)));
			TextComponent text = new TextComponent();
			text.setText(name);
			text.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), textAlpha));
			text.setPosition(new java.awt.Point(center.getX() - textWidth / 2, center.getY() + 4));
			text.render(g);
		}
	}

	private static int clampAlpha(int a)
	{
		return Math.max(0, Math.min(255, a));
	}
}
