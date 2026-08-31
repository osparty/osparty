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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/** Draws a monster's live defence by its health bar: Defence icon, level and drain, plus an optional magic-defence readout. */
public class NpcDefenceOverlay extends Overlay
{
	private static final Color PLATE_COLOR = new Color(0, 0, 0, 150);
	private static final int GAP = 3;
	/** Space between the Defence and magic readouts when they share a row. */
	private static final int SEGMENT_GAP = 8;
	private static final int ARROW_WIDTH = 7;

	private final Client client;
	private final DefenceTracker tracker;
	private final OSPartyConfig config;
	private final BufferedImage icon;
	private final BufferedImage magicIcon;

	/** One {@code [icon] value ↓drain} block; a row holds one or two. */
	@Value
	private static class Segment
	{
		BufferedImage icon;
		String text;
		Color color;
		String drain;
	}

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
		int baseline = anchor.getY() - config.defenceHpBarYOffset();

		Segment defence = defenceSegment(state);
		Segment magic = config.magicDefence() ? magicSegment(state) : null;
		if (magic == null)
		{
			drawRow(graphics, fm, centreX, baseline, Collections.singletonList(defence));
		}
		else if (config.magicDefenceSameRow())
		{
			drawRow(graphics, fm, centreX, baseline, Arrays.asList(defence, magic));
		}
		else
		{
			drawRow(graphics, fm, centreX, baseline, Collections.singletonList(defence));
			drawRow(graphics, fm, centreX, baseline + fm.getHeight(), Collections.singletonList(magic));
		}
		return null;
	}

	private Segment defenceSegment(DefenceState state)
	{
		boolean full = config.defenceShowFullLevel();
		long current = DefenceReadout.shownDefence(state, full);
		long base = DefenceReadout.shownBaseDefence(state, full);
		return new Segment(iconOrNull(icon),
			DefenceReadout.value(config.defenceValueFormat(), current, base),
			DefenceReadout.defenceColor(state, config),
			DefenceReadout.drain(config.defenceDrainFormat(), current, base));
	}

	/** @return the magic-defence block for the configured mode, or null to draw nothing. */
	private Segment magicSegment(DefenceState state)
	{
		long rollPercent = DefenceReadout.percentRemaining(state.getMagicRoll(), state.getMagicBaseRoll());
		String text;
		String drain = null;
		switch (config.magicDefenceDisplay())
		{
			case BONUS:
				text = DefenceReadout.value(config.defenceValueFormat(), state.getMagicDef(), state.getMagicBaseDef());
				drain = DefenceReadout.drain(config.defenceDrainFormat(), state.getMagicDef(), state.getMagicBaseDef());
				break;
			case LEVEL:
				text = DefenceReadout.value(config.defenceValueFormat(), state.getMagicLevel(), state.getMagicBaseLevel());
				drain = DefenceReadout.drain(config.defenceDrainFormat(), state.getMagicLevel(), state.getMagicBaseLevel());
				break;
			case PERCENT:
				// Already relative to where the monster started, so there's no drain to count off.
				if (rollPercent >= 100)
				{
					return null;
				}
				text = rollPercent + "%";
				break;
			case BOTH:
				text = rollPercent < 100
					? state.getMagicDef() + "  " + rollPercent + "%"
					: Long.toString(state.getMagicDef());
				drain = DefenceReadout.drain(config.defenceDrainFormat(), state.getMagicDef(), state.getMagicBaseDef());
				break;
			default:
				return null;
		}
		return new Segment(iconOrNull(magicIcon), text, config.magicDefenceColor(), drain);
	}

	private BufferedImage iconOrNull(BufferedImage image)
	{
		return config.defenceShowIcons() ? image : null;
	}

	/** One row of blocks, centred on {@code centreX}, under a single plate. */
	private void drawRow(Graphics2D graphics, FontMetrics fm, int centreX, int baseline, List<Segment> segments)
	{
		int totalW = SEGMENT_GAP * (segments.size() - 1);
		for (Segment segment : segments)
		{
			totalW += width(fm, segment);
		}
		int x = centreX - totalW / 2;

		if (config.defenceTextPlate())
		{
			graphics.setColor(PLATE_COLOR);
			graphics.fillRect(x - 2, baseline - fm.getAscent() - 1, totalW + 4, fm.getHeight() + 2);
		}
		for (Segment segment : segments)
		{
			x = drawSegment(graphics, fm, x, baseline, segment) + SEGMENT_GAP;
		}
	}

	private static int width(FontMetrics fm, Segment segment)
	{
		int w = fm.stringWidth(segment.getText());
		if (segment.getIcon() != null)
		{
			w += segment.getIcon().getWidth() + GAP;
		}
		if (segment.getDrain() != null)
		{
			w += GAP + ARROW_WIDTH + 2 + fm.stringWidth(segment.getDrain());
		}
		return w;
	}

	/** @return the x just past the drawn block. */
	private int drawSegment(Graphics2D graphics, FontMetrics fm, int x, int baseline, Segment segment)
	{
		int cursor = x;
		BufferedImage image = segment.getIcon();
		if (image != null)
		{
			graphics.drawImage(image, cursor, baseline - image.getHeight() + 2, null);
			cursor += image.getWidth() + GAP;
		}
		OverlayUtil.renderTextLocation(graphics, new Point(cursor, baseline), segment.getText(), segment.getColor());
		cursor += fm.stringWidth(segment.getText());

		if (segment.getDrain() != null)
		{
			cursor += GAP;
			drawDownArrow(graphics, cursor, baseline, fm.getAscent());
			cursor += ARROW_WIDTH + 2;
			OverlayUtil.renderTextLocation(graphics, new Point(cursor, baseline), segment.getDrain(),
				config.defenceDrainColor());
			cursor += fm.stringWidth(segment.getDrain());
		}
		return cursor;
	}

	/** A small filled down-pointing triangle (the in-game font lacks an arrow glyph). */
	private void drawDownArrow(Graphics2D graphics, int x, int baseline, int ascent)
	{
		int top = baseline - ascent + 2;
		int bottom = baseline - 1;
		Polygon tri = new Polygon();
		tri.addPoint(x, top);
		tri.addPoint(x + ARROW_WIDTH, top);
		tri.addPoint(x + ARROW_WIDTH / 2, bottom);
		graphics.setColor(config.defenceDrainColor());
		graphics.fill(tri);
	}

	private NPC npcByIndex(int index)
	{
		return client.getTopLevelWorldView().npcs().byIndex(index);
	}
}
