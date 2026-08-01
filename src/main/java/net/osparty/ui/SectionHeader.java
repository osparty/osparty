package net.osparty.ui;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.Border;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/** The bold-orange-title-over-a-separator section header used by every sidebar tab. */
final class SectionHeader
{
	private SectionHeader()
	{
	}

	/** The bold orange title label every variant is built around. */
	static JLabel title(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		label.setForeground(ColorScheme.BRAND_ORANGE);
		return label;
	}

	/** A plain header for the list tabs: title over a separator, indented to match their rows. */
	static JPanel plain(String text, Icon icon)
	{
		JPanel row = separatorRow(BorderFactory.createEmptyBorder(5, 8, 5, 8));
		JLabel label = title(text);
		if (icon != null)
		{
			label.setIcon(icon);
			label.setIconTextGap(6);
		}
		row.add(label, BorderLayout.CENTER);
		return row;
	}

	/** The tighter divider between the Create form's sections. */
	static JPanel formDivider(String text)
	{
		JPanel row = separatorRow(BorderFactory.createEmptyBorder(8, 0, 3, 0));
		row.add(title(text), BorderLayout.WEST);
		return row;
	}

	/** The Create form's collapsible section header: a {@link #formDivider} wrapping a toggle button. */
	static JPanel formToggleRow(JButton toggle, String text, Runnable onToggle)
	{
		toggle.setText(text);
		styleToggle(toggle, FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		toggle.setBorder(BorderFactory.createEmptyBorder(8, 0, 3, 0));
		toggle.addActionListener(e -> onToggle.run());

		JPanel row = PanelWidgets.cappedRow(new BorderLayout());
		row.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR));
		row.add(toggle, BorderLayout.CENTER);
		return row;
	}

	/**
	 * The Search tab's filter-section header, styled like a RuneLite config section.
	 *
	 * @param sub a nested section under "Filters", rendered a step smaller than a top-level header.
	 */
	static void filterToggle(JButton toggle, boolean sub)
	{
		Font base = new JLabel().getFont();
		styleToggle(toggle, sub ? base.deriveFont(Font.BOLD, base.getSize2D() - 2f) : base.deriveFont(Font.BOLD));
		// Sub-headers indent only the chevron and title, so they read as children of the
		// "Filters" header while the underline and content keep the full sidebar width.
		toggle.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(3, sub ? 10 : 0, 4, 0)));
	}

	/** A clickable "caret + title + count" header for a collapsible list section. */
	static Collapsible collapsible(String text, Runnable onToggle)
	{
		return new Collapsible(text, onToggle);
	}

	static final class Collapsible
	{
		final JPanel panel;
		private final JLabel caret;
		private final JLabel titleLabel;
		private final JLabel countLabel;

		private Collapsible(String text, Runnable onToggle)
		{
			panel = separatorRow(BorderFactory.createEmptyBorder(5, 8, 5, 8));
			titleLabel = title(text);

			caret = new JLabel(Carets.EXPANDED);
			caret.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));

			countLabel = new JLabel();
			countLabel.setFont(FontManager.getRunescapeSmallFont());
			countLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);

			panel.add(caret, BorderLayout.WEST);
			panel.add(titleLabel, BorderLayout.CENTER);
			panel.add(countLabel, BorderLayout.EAST);
			panel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			panel.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					onToggle.run();
				}
			});
		}

		void setExpanded(boolean expanded)
		{
			caret.setIcon(expanded ? Carets.EXPANDED : Carets.COLLAPSED);
		}

		void setCount(int count)
		{
			countLabel.setText(count == 0 ? "" : "(" + count + ")");
		}

		void setIcon(Icon icon)
		{
			titleLabel.setIcon(icon);
			titleLabel.setIconTextGap(6);
		}
	}

	private static void styleToggle(JButton toggle, Font font)
	{
		toggle.setIcon(Carets.COLLAPSED);
		toggle.setHorizontalAlignment(SwingConstants.LEFT);
		toggle.setFocusPainted(false);
		toggle.setContentAreaFilled(false);
		toggle.setForeground(ColorScheme.BRAND_ORANGE);
		toggle.setFont(font);
		toggle.setIconTextGap(6);
		toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
	}

	private static JPanel separatorRow(Border padding)
	{
		JPanel row = PanelWidgets.cappedRow(new BorderLayout(6, 0));
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR), padding));
		return row;
	}
}
