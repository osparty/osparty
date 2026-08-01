package net.osparty.ui;

import java.awt.Dimension;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import javax.swing.JPanel;
import javax.swing.Scrollable;

/**
 * A column that fills the scroll viewport's width so rows never clip horizontally: a plain JPanel
 * view gets its preferred width inside a JScrollPane, so with HORIZONTAL_SCROLLBAR_NEVER any row
 * wider than the sidebar silently disappears under the vertical scrollbar.
 */
class ScrollableColumn extends JPanel implements Scrollable
{
	/** Block scroll step in px, or 0 to page by the visible height. */
	private final int blockIncrement;

	ScrollableColumn(LayoutManager layout)
	{
		this(layout, 0);
	}

	ScrollableColumn(LayoutManager layout, int blockIncrement)
	{
		super(layout);
		this.blockIncrement = blockIncrement;
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
		return blockIncrement > 0 ? blockIncrement : visibleRect.height;
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
