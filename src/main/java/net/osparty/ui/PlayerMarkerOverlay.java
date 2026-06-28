package net.osparty.ui;

import net.osparty.OSPartyConfig;
import net.osparty.party.LiveParty;
import net.osparty.party.LiveParty.Marker;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Marks party members tagged as a learner or teacher in the game scene: an icon
 * beside their name and a coloured highlight on their tile. Untagged members get
 * nothing. The icon and tile marker are independent toggles, and the tile colour
 * is configurable per role. Driven by {@link LiveParty#learnerMarkers()}.
 */
public class PlayerMarkerOverlay extends Overlay
{
	/** Gap in px between the icon and the name. */
	private static final int ICON_GAP = 2;

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
				drawNameIcon(graphics, player, marker == Marker.TEACHER ? teacherIcon : learnerIcon);
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
		graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.min(60, color.getAlpha())));
		graphics.fill(tile);
		graphics.setColor(color);
		graphics.draw(tile);
	}

	/** Draw the icon just left of the player's overhead name, like a clan icon. */
	private void drawNameIcon(Graphics2D graphics, Player player, BufferedImage icon)
	{
		if (icon == null)
		{
			return;
		}
		String name = player.getName();
		Point textLoc = player.getCanvasTextLocation(graphics, name, player.getLogicalHeight() + 40);
		if (textLoc == null)
		{
			return;
		}
		int x = textLoc.getX() - ICON_GAP - icon.getWidth();
		int y = textLoc.getY() - icon.getHeight();
		graphics.drawImage(icon, x, y, null);
	}
}
