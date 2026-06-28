package net.osparty.ui;

import net.osparty.OSPartyConfig;
import net.osparty.combat.DefenceTracker;
import net.osparty.combat.DefenceTracker.DefenceState;
import java.awt.Color;
import java.awt.image.BufferedImage;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.infobox.InfoBox;

/**
 * Status-bar (info-box) display of the monster's live defence, an alternative or
 * complement to the {@link NpcDefenceOverlay} scene display. Reads the value from
 * {@link DefenceTracker} on every render so it stays current without per-tick updates.
 */
public class DefenceInfoBox extends InfoBox
{
	private final DefenceTracker tracker;
	private final OSPartyConfig config;

	public DefenceInfoBox(BufferedImage image, Plugin plugin, DefenceTracker tracker, OSPartyConfig config)
	{
		super(image, plugin);
		this.tracker = tracker;
		this.config = config;
		setTooltip("Monster defence");
	}

	@Override
	public String getText()
	{
		DefenceState state = tracker.state();
		if (state == null)
		{
			return "";
		}
		long shown = config.defenceShowFullLevel() ? state.getCurrent() : state.getCurrent() - state.getMin();
		return Long.toString(Math.max(0, shown));
	}

	@Override
	public Color getTextColor()
	{
		DefenceState state = tracker.state();
		if (state == null)
		{
			return Color.WHITE;
		}
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
}
