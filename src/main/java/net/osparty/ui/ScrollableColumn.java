package net.osparty.ui;

import java.awt.Dimension;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import javax.swing.JPanel;
import javax.swing.Scrollable;

/**
 * A column that fills the scroll viewport's width so rows never clip horizontally: a plain JPanel
 * view gets its preferred width inside a JScrollPane, so with HORIZONTAL_SCROLLBAR_NEVER any row
 * wider than the sidebar silently disappears under the vertical scrollbar. PartyPanel and
 * SearchPanel carry private copies of this predating it; new panels should use this one.
 */
class ScrollableColumn extends JPanel implements Scrollable
{
	ScrollableColumn(LayoutManager layout)
	{
		super(layout);
	}

	@Override
	public Dimension getPreferredScrollableViewportSize()
	{
		return getPreferredSize();
	}

	@Override
	public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
	{
		return 16;
	}

	@Override
	public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
	{
		return visibleRect.height;
	}

	@Override
	public boolean getScrollableTracksViewportWidth()
	{
		return true;
	}

	@Override
	public boolean getScrollableTracksViewportHeight()
	{
		return false;
	}
}
