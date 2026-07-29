package net.osparty.ui;

import net.osparty.OSPartyConfig;
import net.osparty.party.LivePartyBackend;
import net.osparty.party.TilePing;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.TextComponent;

/**
 * Draws an arrow pinned to the edge of the game view pointing toward each party ping that is
 * off-screen (out of the loaded scene, behind the camera, or scrolled out of the viewport).
 *
 * <p>Rendered on {@link OverlayLayer#ABOVE_WIDGETS} so the arrow sits on top of the game interface
 * (chat box, inventory, minimap) instead of vanishing behind it, while still staying under the
 * right-click menu. On-scene ping rings are drawn separately by {@link TilePingOverlay}.
 */
public class PingArrowOverlay extends Overlay
{
	private static final int EDGE_MARGIN = 24;

	private final Client client;
	private final LivePartyBackend liveParty;
	private final OSPartyConfig config;

	public PingArrowOverlay(Client client, LivePartyBackend liveParty, OSPartyConfig config)
	{
		this.client = client;
		this.liveParty = liveParty;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.pings() || !config.pingOffscreenIndicator())
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
		for (TilePing ping : pings)
		{
			WorldPoint wp = ping.getPoint();
			if (wp == null || wp.getPlane() != plane)
			{
				continue;
			}
			double t = (now - ping.getCreatedAt()) / (double) config.pingAnimMs();
			if (t < 0 || t > 1)
			{
				continue;
			}
			// Only pings TilePingOverlay isn't already drawing on the scene.
			if (TilePingOverlay.onScreenCanvas(client, wp) == null)
			{
				drawOffscreenArrow(graphics, wp, ping, t);
			}
		}
		return null;
	}

	/**
	 * Direction is the ping's world bearing relative to us, rotated into screen space by the camera
	 * yaw (matching {@link Perspective}'s ground rotation), so it stays correct even when the tile is
	 * behind the camera. The arrow is then clamped to the viewport rectangle.
	 */
	private void drawOffscreenArrow(Graphics2D g, WorldPoint wp, TilePing ping, double t)
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}
		WorldPoint self = local.getWorldLocation();
		if (self == null)
		{
			return;
		}
		int dx = wp.getX() - self.getX(); // east
		int dy = wp.getY() - self.getY(); // north
		if (dx == 0 && dy == 0)
		{
			return; // pinged our own tile; no meaningful direction
		}

		double yaw = client.getCameraYaw() * Perspective.UNIT14; // 14-bit Jagex angle units -> radians
		double sin = Math.sin(yaw);
		double cos = Math.cos(yaw);
		double right = dx * cos + dy * sin;   // screen-right component
		double forward = dy * cos - dx * sin; // into-screen component (screen up)
		double sx = right;
		double sy = -forward; // canvas y grows downward
		double len = Math.hypot(sx, sy);
		if (len < 1e-6)
		{
			return;
		}
		sx /= len;
		sy /= len;

		int x0 = client.getViewportXOffset();
		int y0 = client.getViewportYOffset();
		double cx = x0 + client.getViewportWidth() / 2.0;
		double cy = y0 + client.getViewportHeight() / 2.0;
		double halfW = Math.max(1, client.getViewportWidth() / 2.0 - EDGE_MARGIN);
		double halfH = Math.max(1, client.getViewportHeight() / 2.0 - EDGE_MARGIN);
		double toX = Math.abs(sx) < 1e-6 ? Double.MAX_VALUE : halfW / Math.abs(sx);
		double toY = Math.abs(sy) < 1e-6 ? Double.MAX_VALUE : halfH / Math.abs(sy);
		double edge = Math.min(toX, toY);
		double px = cx + sx * edge;
		double py = cy + sy * edge;

		Color base = ping.getColor();
		int alpha = clampAlpha((int) (230 * (1 - t)));
		double pulse = 0.85 + 0.15 * Math.sin(t * Math.PI * 6);
		drawArrowHead(g, px, py, Math.atan2(sy, sx), base, alpha, pulse);

		// Sender's name, tucked just inside the arrow toward the centre.
		String name = ping.getName();
		if (name != null && !name.isEmpty())
		{
			int textAlpha = clampAlpha((int) (255 * (1 - t)));
			int textWidth = g.getFontMetrics().stringWidth(name);
			int lx = (int) Math.round(px - sx * 18) - textWidth / 2;
			int ly = (int) Math.round(py - sy * 18) + 4;
			TextComponent text = new TextComponent();
			text.setText(name);
			text.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), textAlpha));
			text.setPosition(new java.awt.Point(lx, ly));
			text.render(g);
		}
	}

	private void drawArrowHead(Graphics2D g, double px, double py, double angle, Color base, int alpha, double scale)
	{
		double size = 13 * scale;
		double width = 8 * scale;
		double perp = angle + Math.PI / 2;
		double tipX = px + Math.cos(angle) * size;
		double tipY = py + Math.sin(angle) * size;
		double backX = px - Math.cos(angle) * (size * 0.35);
		double backY = py - Math.sin(angle) * (size * 0.35);

		Polygon tri = new Polygon();
		tri.addPoint((int) Math.round(tipX), (int) Math.round(tipY));
		tri.addPoint((int) Math.round(backX + Math.cos(perp) * width), (int) Math.round(backY + Math.sin(perp) * width));
		tri.addPoint((int) Math.round(backX - Math.cos(perp) * width), (int) Math.round(backY - Math.sin(perp) * width));

		g.setColor(new Color(0, 0, 0, clampAlpha((int) (alpha * 0.6))));
		g.draw(tri);
		g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
		g.fill(tri);
	}

	private static int clampAlpha(int a)
	{
		return Math.max(0, Math.min(255, a));
	}
}
