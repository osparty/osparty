package net.osparty.ui;

import net.osparty.history.PartyHistoryEntry;
import net.osparty.history.PartyHistoryService;
import net.osparty.model.Activity;
import net.osparty.model.Member;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The "History" tab: a capped, newest-first list of the parties the player has been in
 * (hosted or joined), backed by {@link PartyHistoryService}. Each row shows the activity,
 * whether it was hosted or joined, the host, and how long ago it was. A "Clear" button
 * empties the list. Re-rendered whenever the tab becomes visible and after a new party is
 * recorded (via {@link #refresh()}).
 */
class HistoryPanel extends JPanel
{
	private final PartyHistoryService historyService;
	private final JPanel listContent;
	private final JLabel statusLabel;
	private final JButton clearButton;

	HistoryPanel(PartyHistoryService historyService)
	{
		this.historyService = historyService;

		setLayout(new BorderLayout(0, 0));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		// ---- header: title + Clear ----
		JPanel header = new JPanel(new BorderLayout(6, 0));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(5, 8, 5, 8)));
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));

		JLabel title = new JLabel("Party history");
		title.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		title.setForeground(ColorScheme.BRAND_ORANGE);

		clearButton = new JButton("Clear");
		clearButton.setFocusPainted(false);
		clearButton.setFont(FontManager.getRunescapeSmallFont());
		clearButton.setMargin(new java.awt.Insets(1, 6, 1, 6));
		clearButton.addActionListener(e ->
		{
			int choice = JOptionPane.showConfirmDialog(this,
				"Clear your entire party history? This can't be undone.",
				"Clear party history", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (choice == JOptionPane.YES_OPTION)
			{
				historyService.clear();
				SwingUtilities.invokeLater(this::refresh);
			}
		});

		header.add(title, BorderLayout.CENTER);
		header.add(clearButton, BorderLayout.EAST);

		// ---- scrollable list ----
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

		// Re-render when the tab is shown, so it reflects parties recorded while it was hidden.
		addAncestorListener(new AncestorListener()
		{
			@Override
			public void ancestorAdded(AncestorEvent event)
			{
				refresh();
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

	/** Rebuild the list from the current history. Safe to call on the EDT. */
	void refresh()
	{
		listContent.removeAll();
		List<PartyHistoryEntry> history = historyService.list();

		clearButton.setVisible(!history.isEmpty());
		statusLabel.setText(history.isEmpty() ? "No parties yet." : "");

		for (PartyHistoryEntry entry : history)
		{
			listContent.add(buildRow(entry));
			listContent.add(Box.createVerticalStrut(4));
		}
		listContent.revalidate();
		listContent.repaint();
	}

	private static JPanel buildRow(PartyHistoryEntry entry)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel titleLabel = new JLabel(activityTitle(entry));
		titleLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		titleLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

		String role = entry.isHosted() ? "You hosted" : "Joined " + hostName(entry.getHost());
		JLabel subLabel = new JLabel(role);
		subLabel.setFont(FontManager.getRunescapeSmallFont());
		subLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		subLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

		text.add(titleLabel);
		text.add(subLabel);

		// Compact roster line (host first), truncated to keep the row tidy; the full list is on the tooltip.
		String members = memberNames(entry);
		if (members != null)
		{
			JLabel membersLabel = new JLabel("With: " + truncate(members, 34));
			membersLabel.setFont(FontManager.getRunescapeSmallFont());
			membersLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
			membersLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			membersLabel.setToolTipText(members);
			text.add(membersLabel);
		}

		JLabel when = new JLabel(timeAgo(entry.getJoinedAt()));
		when.setFont(FontManager.getRunescapeSmallFont());
		when.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);

		row.add(text, BorderLayout.CENTER);
		row.add(when, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** Activity display title (with CM/HM/ToA-invocation), falling back to the raw id. */
	private static String activityTitle(PartyHistoryEntry entry)
	{
		Activity activity = Activity.fromId(entry.getActivity());
		if (activity == null)
		{
			return entry.getActivity() == null ? "Party" : entry.getActivity();
		}
		return activity.displayName(entry.isHardMode(), entry.getInvocation());
	}

	private static String hostName(String host)
	{
		return host == null || host.isEmpty() ? "party" : host;
	}

	/** Comma-joined member names (host first, blanks skipped), or {@code null} when none were recorded. */
	private static String memberNames(PartyHistoryEntry entry)
	{
		List<Member> members = entry.getMembers();
		if (members == null || members.isEmpty())
		{
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (Member m : members)
		{
			if (m == null || m.getName() == null || m.getName().isEmpty())
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(", ");
			}
			sb.append(m.getName());
		}
		return sb.length() == 0 ? null : sb.toString();
	}

	private static String truncate(String s, int max)
	{
		return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
	}

	/** A coarse "x ago" label for the given epoch-millis timestamp. */
	private static String timeAgo(long when)
	{
		long delta = System.currentTimeMillis() - when;
		if (delta < 0)
		{
			delta = 0;
		}
		long mins = TimeUnit.MILLISECONDS.toMinutes(delta);
		if (mins < 1)
		{
			return "just now";
		}
		if (mins < 60)
		{
			return mins + "m ago";
		}
		long hours = TimeUnit.MILLISECONDS.toHours(delta);
		if (hours < 24)
		{
			return hours + "h ago";
		}
		long days = TimeUnit.MILLISECONDS.toDays(delta);
		return days + "d ago";
	}
}
