package net.osparty.enums;

import java.awt.Font;
import net.runelite.client.ui.FontManager;

/**
 * Font size choice for on-scene overlay text, mapped to the RuneLite font set.
 */
public enum SceneFontSize
{
	SMALL("Small"),
	MEDIUM("Medium"),
	LARGE("Large");

	private final String label;

	SceneFontSize(String label)
	{
		this.label = label;
	}

	public Font font()
	{
		switch (this)
		{
			case MEDIUM:
				return FontManager.getRunescapeFont();
			case LARGE:
				return FontManager.getRunescapeBoldFont();
			case SMALL:
			default:
				return FontManager.getRunescapeSmallFont();
		}
	}

	@Override
	public String toString()
	{
		return label;
	}
}
