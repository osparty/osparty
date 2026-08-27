package net.osparty.ui;

import net.osparty.service.BlockListService;
import net.osparty.store.PlayerFlag;
import java.awt.BorderLayout;
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
 * {@link BlockListService}); this tab is where you review and lift those blocks. Re-rendered
 * when the tab is shown and whenever a block changes elsewhere (via {@link #render()});
 * unblocking here notifies the other tabs through {@link #setOnBlockChanged(Runnable)}.
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

		JPanel header = SectionHeader.plain("Blocked players", StatusIcons.BLOCK_ON);

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
		List<PlayerFlag> blocked = blockListService.entries();
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
		JPanel row = PanelWidgets.cappedRow(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 6));

		JLabel name = new JLabel(flag.getUsername());
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JButton unblock = new JButton("Unblock");
		unblock.setFocusPainted(false);
		unblock.setFont(FontManager.getRunescapeSmallFont());
		unblock.setMargin(new java.awt.Insets(1, 6, 1, 6));
		unblock.addActionListener(e ->
		{
			blockListService.toggle(flag.getPlayerId(), flag.getUsername());
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

		return row;
	}
}
