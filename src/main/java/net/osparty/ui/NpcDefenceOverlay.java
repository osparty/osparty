package net.osparty.ui;

import net.osparty.OSPartyConfig;
import net.osparty.enums.DefenceOverlayPosition;
import net.osparty.tools.DefenceTracker;
import net.osparty.tools.DefenceTracker.DefenceState;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Draws the live defence of the monster the party is draining, next to its
 * overhead health bar: the Defence skill icon, the current level, and — once it's
 * been drained — a red down-arrow with the amount lost (e.g. [icon] 170 ▼30).
 */
public class NpcDefenceOverlay extends Overlay
{
	private static final Color DRAIN_COLOR = new Color(255, 80, 80);
	private static final int GAP = 3;

	private final Client client;
	private final DefenceTracker tracker;
	private final OSPartyConfig config;
	private final BufferedImage icon;

	public NpcDefenceOverlay(Client client, DefenceTracker tracker, OSPartyConfig config, BufferedImage icon)
	{
		this.client = client;
		this.tracker = tracker;
		this.config = config;
		this.icon = icon;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.defenceHpBar())
		{
			return null;
		}
		DefenceState state = tracker.state();
		if (state == null)
		{
			return null;
		}
		NPC npc = npcByIndex(state.getNpcIndex());
		if (npc == null)
		{
			return null;
		}

		graphics.setFont(config.defenceFontSize().font());
		FontMetrics fm = graphics.getFontMetrics();

		long shown = config.defenceShowFullLevel()
			? state.getCurrent()
			: state.getCurrent() - state.getMin();
		shown = Math.max(0, shown);
		long drained = Math.max(0, state.getBase() - state.getCurrent());

		String levelStr = Long.toString(shown);
		String drainStr = Long.toString(drained);
		boolean showDrain = drained > 0;

		int iconW = icon != null ? icon.getWidth() : 0;
		int iconH = icon != null ? icon.getHeight() : 0;
		int levelW = fm.stringWidth(levelStr);
		int arrowW = 7;
		int drainBlockW = showDrain ? (GAP + arrowW + 2 + fm.stringWidth(drainStr)) : 0;
		int totalW = iconW + GAP + levelW + drainBlockW;

		// Anchor on the npc at the height the chosen position implies, then lay the
		// row out centred on it (with any sideways nudge for the chosen position).
		DefenceOverlayPosition position = config.defenceHpBarPosition();
		int zOffset = (int) (npc.getLogicalHeight() * position.getHeightFactor()) + position.getHeightOffset();
		Point anchor = npc.getCanvasTextLocation(graphics, "", zOffset);
		if (anchor == null)
		{
			return null;
		}
		int x = anchor.getX() - totalW / 2 + position.getXNudge();
		int baseline = anchor.getY();

		if (config.defenceTextPlate())
		{
			graphics.setColor(new Color(0, 0, 0, 150));
			graphics.fillRect(x - 2, baseline - fm.getAscent() - 1, totalW + 4, fm.getHeight() + 2);
		}

		if (icon != null)
		{
			graphics.drawImage(icon, x, baseline - iconH + 2, null);
		}
		int cursor = x + iconW + GAP;

		OverlayUtil.renderTextLocation(graphics, new Point(cursor, baseline), levelStr, colorFor(state));
		cursor += levelW;

		if (showDrain)
		{
			cursor += GAP;
			drawDownArrow(graphics, cursor, baseline, arrowW, fm.getAscent());
			cursor += arrowW + 2;
			OverlayUtil.renderTextLocation(graphics, new Point(cursor, baseline), drainStr, DRAIN_COLOR);
		}
		return null;
	}

	/** A small filled down-pointing triangle (the in-game font lacks an arrow glyph). */
	private void drawDownArrow(Graphics2D graphics, int x, int baseline, int width, int ascent)
	{
		int top = baseline - ascent + 2;
		int bottom = baseline - 1;
		Polygon tri = new Polygon();
		tri.addPoint(x, top);
		tri.addPoint(x + width, top);
		tri.addPoint(x + width / 2, bottom);
		graphics.setColor(DRAIN_COLOR);
		graphics.fill(tri);
	}

	private Color colorFor(DefenceState state)
	{
		long relative = Math.max(state.getCurrent() - state.getMin(), 0);
		if (relative == 0)
		{
			return config.defenceCappedColor();
		}
		if (relative <= config.defenceLowThreshold())
		{
			return config.defenceLowColor();
		}
		return config.defenceHighColor();
	}

	private NPC npcByIndex(int index)
	{
		for (NPC npc : client.getNpcs())
		{
			if (npc != null && npc.getIndex() == index)
			{
				return npc;
			}
		}
		return null;
	}
}
