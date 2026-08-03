package net.osparty.ui;

import net.osparty.OSPartyConfig;
import net.osparty.enums.DefenceOverlayPosition;
import net.osparty.enums.MagicDefenceDisplay;
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

/** Draws a monster's live defence by its health bar: Defence icon, current level, and drain amount. */
public class NpcDefenceOverlay extends Overlay
{
	private static final Color DRAIN_COLOR = new Color(255, 80, 80);
	private static final Color PLATE_COLOR = new Color(0, 0, 0, 150);
	private static final int GAP = 3;

	private final Client client;
	private final DefenceTracker tracker;
	private final OSPartyConfig config;
	private final BufferedImage icon;
	private final BufferedImage magicIcon;

	public NpcDefenceOverlay(Client client, DefenceTracker tracker, OSPartyConfig config, BufferedImage icon,
		BufferedImage magicIcon)
	{
		this.client = client;
		this.tracker = tracker;
		this.config = config;
		this.icon = icon;
		this.magicIcon = magicIcon;
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

		// Anchor on the npc at the chosen position's height, then lay each row out centred on it.
		DefenceOverlayPosition position = config.defenceHpBarPosition();
		int zOffset = (int) (npc.getLogicalHeight() * position.getHeightFactor()) + position.getHeightOffset();
		Point anchor = npc.getCanvasTextLocation(graphics, "", zOffset);
		if (anchor == null)
		{
			return null;
		}
		int centreX = anchor.getX() + position.getXNudge();
		int baseline = anchor.getY();

		long shown = config.defenceShowFullLevel()
			? state.getCurrent()
			: state.getCurrent() - state.getMin();
		long drained = Math.max(0, state.getBase() - state.getCurrent());
		drawRow(graphics, fm, centreX, baseline, icon, Long.toString(Math.max(0, shown)), colorFor(state),
			drained > 0 ? Long.toString(drained) : null);

		if (config.magicDefence())
		{
			String magicStr = magicText(state);
			if (magicStr != null)
			{
				// Only the bonus readout has a drain to count off; the percentage is
				// already relative to where the monster started.
				long magicDrained = Math.max(0, state.getMagicBaseDef() - state.getMagicDef());
				boolean showDrain = magicDrained > 0 && config.magicDefenceDisplay() != MagicDefenceDisplay.PERCENT;
				drawRow(graphics, fm, centreX, baseline + fm.getHeight(), magicIcon, magicStr,
					config.magicDefenceColor(), showDrain ? Long.toString(magicDrained) : null);
			}
		}
		return null;
	}

	/** @return the magic-defence text for the configured mode, or null to draw nothing. */
	private String magicText(DefenceState state)
	{
		long percent = state.getMagicBaseRoll() > 0
			? Math.max(0, Math.round(state.getMagicRoll() * 100.0 / state.getMagicBaseRoll()))
			: 100;
		switch (config.magicDefenceDisplay())
		{
			case BONUS:
				return Long.toString(state.getMagicDef());
			case PERCENT:
				return percent < 100 ? percent + "%" : null;
			case BOTH:
				return percent < 100
					? state.getMagicDef() + "  " + percent + "%"
					: Long.toString(state.getMagicDef());
			default:
				return null;
		}
	}

	/** One centred {@code [icon] value ↓drain} row. */
	private void drawRow(Graphics2D graphics, FontMetrics fm, int centreX, int baseline, BufferedImage image,
		String text, Color color, String drain)
	{
		int iconW = image != null ? image.getWidth() : 0;
		int iconH = image != null ? image.getHeight() : 0;
		int textW = fm.stringWidth(text);
		int arrowW = 7;
		int drainBlockW = drain != null ? (GAP + arrowW + 2 + fm.stringWidth(drain)) : 0;
		int totalW = iconW + GAP + textW + drainBlockW;
		int x = centreX - totalW / 2;

		if (config.defenceTextPlate())
		{
			graphics.setColor(PLATE_COLOR);
			graphics.fillRect(x - 2, baseline - fm.getAscent() - 1, totalW + 4, fm.getHeight() + 2);
		}
		if (image != null)
		{
			graphics.drawImage(image, x, baseline - iconH + 2, null);
		}

		int cursor = x + iconW + GAP;
		OverlayUtil.renderTextLocation(graphics, new Point(cursor, baseline), text, color);
		cursor += textW;

		if (drain != null)
		{
			cursor += GAP;
			drawDownArrow(graphics, cursor, baseline, arrowW, fm.getAscent());
			cursor += arrowW + 2;
			OverlayUtil.renderTextLocation(graphics, new Point(cursor, baseline), drain, DRAIN_COLOR);
		}
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
		return client.getTopLevelWorldView().npcs().byIndex(index);
	}
}
