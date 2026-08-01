package net.osparty.ui;

import net.osparty.OSPartyConfig;
import net.osparty.tools.DefenceTracker;
import net.osparty.tools.DefenceTracker.DefenceState;
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
	private static final String PLAIN_TOOLTIP = "Monster defence";

	private final DefenceTracker tracker;
	private final OSPartyConfig config;
	/** What the pushed tooltip was built from, so a per-frame render only rebuilds it on a change. */
	private boolean tipMagic;
	private long tipMagicDef = Long.MIN_VALUE;
	private long tipPercent = Long.MIN_VALUE;

	public DefenceInfoBox(BufferedImage image, Plugin plugin, DefenceTracker tracker, OSPartyConfig config)
	{
		super(image, plugin);
		this.tracker = tracker;
		this.config = config;
		setTooltip(PLAIN_TOOLTIP);
	}

	@Override
	public String getText()
	{
		DefenceState state = tracker.state();
		if (state == null)
		{
			return "";
		}
		updateTooltip(state);
		long shown = config.defenceShowFullLevel() ? state.getCurrent() : state.getCurrent() - state.getMin();
		return Long.toString(Math.max(0, shown));
	}

	private void updateTooltip(DefenceState state)
	{
		boolean magic = config.magicDefence() && state.getMagicBaseRoll() > 0;
		long def = magic ? state.getMagicDef() : 0;
		long percent = magic
			? Math.max(0, Math.round(state.getMagicRoll() * 100.0 / state.getMagicBaseRoll())) : 0;
		if (magic == tipMagic && def == tipMagicDef && percent == tipPercent)
		{
			return;
		}
		tipMagic = magic;
		tipMagicDef = def;
		tipPercent = percent;
		setTooltip(magic
			? PLAIN_TOOLTIP + " (magic defence: " + def + " bonus, " + percent + "% of starting roll)"
			: PLAIN_TOOLTIP);
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
