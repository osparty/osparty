package net.osparty.ui;

import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import net.runelite.client.util.ImageUtil;

/** RuneLite's config-section caret (grey): points right when collapsed, down when expanded. */
final class Carets
{
	/** Darkening applied to RuneLite's white arrow so it matches a config section's grey chevron. */
	private static final int GREY_LUMINANCE_OFFSET = -121;

	static final ImageIcon COLLAPSED = caret(0);
	static final ImageIcon EXPANDED = caret(Math.PI / 2);

	private Carets()
	{
	}

	private static ImageIcon caret(double rotation)
	{
		BufferedImage arrow = ImageUtil.loadImageResource(Carets.class, "/util/arrow_right.png");
		if (arrow == null)
		{
			return null;
		}
		BufferedImage grey = ImageUtil.luminanceOffset(arrow, GREY_LUMINANCE_OFFSET);
		if (rotation != 0)
		{
			grey = ImageUtil.rotateImage(grey, rotation);
		}
		return new ImageIcon(grey);
	}
}
