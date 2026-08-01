package net.osparty.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.LayoutManager;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/** Swing scaffolding shared by the sidebar tabs. */
final class PanelWidgets
{
	private PanelWidgets()
	{
	}

	/**
	 * A container whose height always tracks its content, so a BoxLayout parent can't stretch it.
	 * getMaximumSize is overridden rather than snapshotted at construction: content that grows later
	 * (an async sprite, a wrapped line) would otherwise be clipped to the height it had when built.
	 */
	static final class Capped extends JPanel
	{
		Capped(LayoutManager layout)
		{
			super(layout);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}

	/** A height-capped, left-aligned row on the standard sidebar background. */
	static JPanel cappedRow(LayoutManager layout)
	{
		JPanel panel = new Capped(layout);
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		return panel;
	}

	/** A height-capped vertical group of rows. */
	static JPanel cappedColumn()
	{
		JPanel panel = cappedRow(null);
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		return panel;
	}

	/** The small-font coloured label the sidebar is built from. */
	static JLabel smallLabel(String text, Color fg)
	{
		JLabel label = new JLabel(text);
		label.setForeground(fg);
		label.setFont(FontManager.getRunescapeSmallFont());
		return label;
	}

	/** {@link #smallLabel} left-aligned, for a BoxLayout column. */
	static JLabel smallLabelLeft(String text, Color fg)
	{
		JLabel label = smallLabel(text, fg);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	/** The 3-dot menu trigger: left-click drops {@code menu} beneath it. */
	static JLabel kebab(String tooltip, JPopupMenu menu)
	{
		JLabel kebab = new JLabel(StatusIcons.KEBAB);
		kebab.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		kebab.setToolTipText(tooltip);
		kebab.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e))
				{
					menu.show(kebab, 0, kebab.getHeight());
				}
			}
		});
		return kebab;
	}

	/** Let every descendant defer its right-click to {@code root}'s component popup menu. */
	static void inheritPopupMenu(JComponent root)
	{
		for (Component child : root.getComponents())
		{
			if (child instanceof JComponent)
			{
				JComponent jc = (JComponent) child;
				jc.setInheritsPopupMenu(true);
				inheritPopupMenu(jc);
			}
		}
	}

	/** A read-only wrapping text label: a JTextArea so long messages wrap instead of truncating to "…". */
	static JTextArea wrappingText()
	{
		JTextArea area = new JTextArea()
		{
			@Override
			public Dimension getMaximumSize()
			{
				// BoxLayout may stretch children; cap the height so it only grows when the text wraps.
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		area.setFont(FontManager.getRunescapeSmallFont());
		area.setAlignmentX(Component.LEFT_ALIGNMENT);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setEditable(false);
		area.setFocusable(false);
		area.setOpaque(false);
		return area;
	}
}
