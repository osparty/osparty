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
 * The box only fits a few characters, so the full picture lives in its tooltip.
 */
public class DefenceInfoBox extends InfoBox
{
	private final DefenceTracker tracker;
	private final OSPartyConfig config;
	/** The last tooltip pushed, so a per-frame render only rebuilds it on a change. */
	private String tooltip = "";

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
		updateTooltip(state);
		boolean full = config.defenceShowFullLevel();
		long current = DefenceReadout.shownDefence(state, full);
		long base = DefenceReadout.shownBaseDefence(state, full);
		switch (config.defenceInfoBoxValue())
		{
			case PERCENT:
				return DefenceReadout.percentRemaining(current, base) + "%";
			case DRAINED:
				return Long.toString(Math.max(0, base - current));
			case CURRENT:
			default:
				return Long.toString(current);
		}
	}

	/** e.g. {@code Great Olm: Defence 142/200 (71%, -58) | Magic level 250/250, bonus 180/200 (84% of starting roll)}. */
	private void updateTooltip(DefenceState state)
	{
		boolean full = config.defenceShowFullLevel();
		long current = DefenceReadout.shownDefence(state, full);
		long base = DefenceReadout.shownBaseDefence(state, full);
		StringBuilder tip = new StringBuilder(state.getName()).append(": Defence ")
			.append(current).append('/').append(base)
			.append(" (").append(DefenceReadout.percentRemaining(current, base)).append('%');
		if (base > current)
		{
			tip.append(", -").append(base - current);
		}
		tip.append(')');
		if (config.magicDefence())
		{
			tip.append(" | Magic level ").append(state.getMagicLevel()).append('/').append(state.getMagicBaseLevel())
				.append(", bonus ").append(state.getMagicDef()).append('/').append(state.getMagicBaseDef())
				.append(" (").append(DefenceReadout.percentRemaining(state.getMagicRoll(), state.getMagicBaseRoll()))
				.append("% of starting roll)");
		}
		String text = tip.toString();
		if (!text.equals(tooltip))
		{
			tooltip = text;
			setTooltip(text);
		}
	}

	@Override
	public Color getTextColor()
	{
		DefenceState state = tracker.state();
		return state == null ? Color.WHITE : DefenceReadout.defenceColor(state, config);
	}
}
