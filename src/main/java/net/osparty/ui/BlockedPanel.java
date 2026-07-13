package net.osparty.ui;

import net.osparty.service.BlockListService;
import net.osparty.store.PlayerFlag;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The "Blocked" tab: a management list of the players on the local block list, each with
 * an Unblock button. Blocked players are hidden from Search by default (see
 * {@link BlockListService}); this tab is where you review and lift those blocks. Formerly a
 * collapsible section on the Favorites tab. Re-rendered when the tab is shown and whenever a
 * block changes elsewhere (via {@link #render()}); unblocking here notifies the other tabs
 * through {@link #setOnBlockChanged(Runnable)}.
 */
class BlockedPanel extends JPanel
{
	private final BlockListService blockListService;
	private final JPanel listContent;
	private final JLabel statusLabel;
	private Runnable onBlockChanged;

	BlockedPanel(BlockListService blockListService)
	{
		this.blockListService = blockListService;

		setLayout(new BorderLayout(0, 0));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		JPanel header = new JPanel(new BorderLayout(6, 0));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(5, 8, 5, 8)));
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));

		JLabel title = new JLabel("Blocked players");
		title.setIcon(StatusIcons.BLOCK_ON);
		title.setIconTextGap(6);
		title.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		title.setForeground(ColorScheme.BRAND_ORANGE);
		header.add(title, BorderLayout.CENTER);

		listContent = new JPanel();
		listContent.setLayout(new BoxLayout(listContent, BoxLayout.Y_AXIS));
		listContent.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel column = new JPanel(new BorderLayout());
		column.setBackground(ColorScheme.DARK_GRAY_COLOR);
		column.add(header, BorderLayout.NORTH);
		column.add(listContent, BorderLayout.CENTER);

		JScrollPane scroll = new JScrollPane(column);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getVerticalScrollBar().setUnitIncrement(16);

		statusLabel = new JLabel();
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

		add(scroll, BorderLayout.CENTER);
		add(statusLabel, BorderLayout.SOUTH);

		// Re-render when shown, so it reflects blocks toggled from other tabs while hidden.
		addAncestorListener(new AncestorListener()
		{
			@Override
			public void ancestorAdded(AncestorEvent event)
			{
				render();
			}

			@Override
			public void ancestorRemoved(AncestorEvent event)
			{
			}

			@Override
			public void ancestorMoved(AncestorEvent event)
			{
			}
		});
	}

	/** Notified after an unblock here, so the Search/Favorites tabs can re-render. */
	void setOnBlockChanged(Runnable onBlockChanged)
	{
		this.onBlockChanged = onBlockChanged;
	}

	/** Rebuild the list from the current block list. Safe to call on the EDT. */
	void render()
	{
		listContent.removeAll();
		List<PlayerFlag> blocked = blockListService == null ? new ArrayList<>() : blockListService.entries();
		blocked.sort((a, b) -> a.getUsername().compareToIgnoreCase(b.getUsername()));

		statusLabel.setText(blocked.isEmpty() ? "No blocked players." : "");

		for (PlayerFlag flag : blocked)
		{
			listContent.add(buildRow(flag));
			listContent.add(Box.createVerticalStrut(4));
		}
		listContent.revalidate();
		listContent.repaint();
	}

	private JPanel buildRow(PlayerFlag flag)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 6));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel name = new JLabel(flag.getUsername());
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JButton unblock = new JButton("Unblock");
		unblock.setFocusPainted(false);
		unblock.setFont(FontManager.getRunescapeSmallFont());
		unblock.setMargin(new java.awt.Insets(1, 6, 1, 6));
		unblock.addActionListener(e ->
		{
			blockListService.toggle(flag.getAccountHash(), flag.getUsername());
			SwingUtilities.invokeLater(() ->
			{
				render();
				if (onBlockChanged != null)
				{
					onBlockChanged.run();
				}
			});
		});

		row.add(name, BorderLayout.CENTER);
		row.add(unblock, BorderLayout.EAST);
		// Cap the height so the Y-axis BoxLayout doesn't stretch each row to fill the panel.
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}
}
