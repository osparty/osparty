package net.osparty.ui;

import net.osparty.OSPartyConfig;
import net.osparty.party.LiveParty;
import net.osparty.party.LiveParty.Marker;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Marks party members tagged as a learner or teacher in the game scene.
 */
public class PlayerMarkerOverlay extends Overlay
{
	private static final double ICON_TILE_FILL = 0.18;

	private final Client client;
	private final LiveParty liveParty;
	private final OSPartyConfig config;
	private final BufferedImage learnerIcon;
	private final BufferedImage teacherIcon;

	public PlayerMarkerOverlay(Client client, LiveParty liveParty, OSPartyConfig config,
		BufferedImage learnerIcon, BufferedImage teacherIcon)
	{
		this.client = client;
		this.liveParty = liveParty;
		this.config = config;
		this.learnerIcon = learnerIcon;
		this.teacherIcon = teacherIcon;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		boolean icons = config.learnerTeacherIcons();
		boolean tiles = config.learnerTeacherTiles();
		if (!icons && !tiles)
		{
			return null;
		}
		Map<String, Marker> markers = liveParty.learnerMarkers();
		if (markers.isEmpty())
		{
			return null;
		}

		for (Player player : client.getPlayers())
		{
			if (player == null || player.getName() == null)
			{
				continue;
			}
			Marker marker = markers.get(LiveParty.normalizeName(player.getName()));
			if (marker == null || marker == Marker.NONE)
			{
				continue;
			}
			if (tiles)
			{
				drawTile(graphics, player, colorFor(marker));
			}
			if (icons)
			{
				drawTileIcon(graphics, player, marker == Marker.TEACHER ? teacherIcon : learnerIcon);
			}
		}
		return null;
	}

	private Color colorFor(Marker marker)
	{
		return marker == Marker.TEACHER ? config.teacherColor() : config.learnerColor();
	}

	private void drawTile(Graphics2D graphics, Player player, Color color)
	{
		LocalPoint lp = player.getLocalLocation();
		if (lp == null)
		{
			return;
		}
		Polygon tile = Perspective.getCanvasTilePoly(client, lp);
		if (tile == null)
		{
			return;
		}
		int maxAlpha = Math.max(0, Math.min(255, config.markerTileMaxAlpha()));
		graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(),
			Math.min(maxAlpha, color.getAlpha())));
		graphics.fill(tile);
		graphics.setColor(color);
		graphics.draw(tile);
	}

	/** Draw the role icon flat on the player's tile, in perspective, like a ground decal. */
	private void drawTileIcon(Graphics2D graphics, Player player, BufferedImage icon)
	{
		if (icon == null)
		{
			return;
		}
		LocalPoint lp = player.getLocalLocation();
		if (lp == null)
		{
			return;
		}
		Polygon tile = Perspective.getCanvasTilePoly(client, lp);
		if (tile == null || tile.npoints < 4)
		{
			return;
		}
		// getCanvasTilePoly corners: [0]=SW, [1]=SE, [2]=NE, [3]=NW. Inset toward the tile centre so the
		// coloured tile stays visible as a frame around the icon.
		double cx = (tile.xpoints[0] + tile.xpoints[1] + tile.xpoints[2] + tile.xpoints[3]) / 4.0;
		double cy = (tile.ypoints[0] + tile.ypoints[1] + tile.ypoints[2] + tile.ypoints[3]) / 4.0;
		double nwx = inset(cx, tile.xpoints[3]), nwy = inset(cy, tile.ypoints[3]);
		double nex = inset(cx, tile.xpoints[2]), ney = inset(cy, tile.ypoints[2]);
		double swx = inset(cx, tile.xpoints[0]), swy = inset(cy, tile.ypoints[0]);

		double w = icon.getWidth();
		double h = icon.getHeight();
		if (w <= 0 || h <= 0)
		{
			return;
		}
		// Map the icon square onto the tile: top edge NW->NE, left edge NW->SW (icon "up" faces north).
		AffineTransform transform = new AffineTransform(
			(nex - nwx) / w, (ney - nwy) / w,
			(swx - nwx) / h, (swy - nwy) / h,
			nwx, nwy);

		Shape oldClip = graphics.getClip();
		Object oldInterp = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		graphics.clip(tile);
		graphics.drawImage(icon, transform, null);
		graphics.setClip(oldClip);
		if (oldInterp != null)
		{
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterp);
		}
	}

	private static double inset(double centre, double corner)
	{
		return centre + (corner - centre) * ICON_TILE_FILL;
	}
}
