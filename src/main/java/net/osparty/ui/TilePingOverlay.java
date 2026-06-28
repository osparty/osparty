package net.osparty.ui;

import net.osparty.OSPartyConfig;
import net.osparty.party.LiveParty;
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
 * Each ping animates for {@link LiveParty} ping window, then disappears.
 */
public class TilePingOverlay extends Overlay
{
	/** Matches LiveParty's ping window so a ping fades out fully. */
	private static final long ANIM_MS = 2_000;
	private static final int MAX_RADIUS = 48;

	private final Client client;
	private final LiveParty liveParty;
	private final OSPartyConfig config;

	public TilePingOverlay(Client client, LiveParty liveParty, OSPartyConfig config)
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
			LocalPoint lp = LocalPoint.fromWorld(client, wp);
			if (lp == null)
			{
				continue;
			}
			double t = (now - ping.getCreatedAt()) / (double) ANIM_MS;
			if (t < 0 || t > 1)
			{
				continue;
			}
			drawPing(graphics, lp, wp.getPlane(), ping, t);
		}
		graphics.setStroke(prev);
		return null;
	}

	private void drawPing(Graphics2D g, LocalPoint lp, int plane, TilePing ping, double t)
	{
		Color base = ping.getColor();
		Point center = Perspective.localToCanvas(client, lp, plane);
		if (center == null)
		{
			return;
		}

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
