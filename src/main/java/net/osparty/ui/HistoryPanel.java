package net.osparty.ui;

import net.osparty.service.BlockListService;
import net.osparty.service.FavoritesService;
import net.osparty.model.HistoryMember;
import net.osparty.model.PartyHistoryEntry;
import net.osparty.service.PartyHistoryService;
import net.osparty.model.Activity;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import javax.swing.JComponent;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageUtil;

/**
 * The "History" tab: a capped, newest-first list of the parties the player has been in
 * (hosted or joined), backed by {@link PartyHistoryService}. Each row shows the activity,
 * whether it was hosted or joined, the host, and how long ago it was, and expands on click to
 * reveal the full roster with each member's join/leave times (members who left are kept, greyed).
 * A search box (activity / host / member names) and a hosted/joined role filter narrow the list;
 * a "Clear" button empties it. Re-rendered whenever the tab becomes visible, on a timer while shown,
 * and after a new party is recorded (via {@link #refresh()}).
 */
class HistoryPanel extends JPanel
{
	/** How often to re-render while the tab is showing, so "x ago" times and live roster changes stay fresh. */
	private static final int REFRESH_INTERVAL_MS = 5000;

	/** Role filter options (also the combo's visible labels). */
	private static final String ROLE_ALL = "All";
	private static final String ROLE_HOSTED = "Hosted";
	private static final String ROLE_JOINED = "Joined";

	private final PartyHistoryService historyService;
	private final FavoritesService favoritesService;
	private final BlockListService blockListService;
	/** Notifies the owning panel that a favourite/block changed, so sibling tabs re-render. */
	private Runnable onFlagChanged;
	private final JPanel listContent;
	private final JScrollPane scroll;
	private final JLabel statusLabel;
	private final JButton clearButton;
	/** Free-text filter over activity, host, and member names. */
	private final JTextField searchField;
	/** Hosted / Joined / All filter. */
	private final JComboBox<String> roleFilter;
	/** Ticks a re-render while this panel is on screen; paused (stopped) while it's hidden. */
	private final Timer refreshTimer;
	/** Party keys whose roster detail is expanded, so a re-render preserves what the user opened. */
	private final Set<String> expanded = new HashSet<>();

	HistoryPanel(PartyHistoryService historyService, FavoritesService favoritesService,
		BlockListService blockListService)
	{
		this.historyService = historyService;
		this.favoritesService = favoritesService;
		this.blockListService = blockListService;

		setLayout(new BorderLayout(0, 0));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		// ---- header: title + Clear ----
		JPanel header = new JPanel(new BorderLayout(6, 0));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));

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

		// ---- filter bar: search + role ----
		searchField = new JTextField();
		searchField.setFont(FontManager.getRunescapeSmallFont());
		searchField.setToolTipText("Filter by activity, host or member name");
		// Re-render on every keystroke; refresh() re-reads the field, so focus stays put.
		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				refresh();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				refresh();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				refresh();
			}
		});

		roleFilter = new JComboBox<>(new String[]{ROLE_ALL, ROLE_HOSTED, ROLE_JOINED});
		roleFilter.setFont(FontManager.getRunescapeSmallFont());
		roleFilter.setToolTipText("Show all parties, or only ones you hosted / joined");
		roleFilter.addActionListener(e -> refresh());

		JPanel filterBar = new JPanel(new BorderLayout(6, 0));
		filterBar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		filterBar.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(0, 8, 6, 8)));
		filterBar.add(searchField, BorderLayout.CENTER);
		filterBar.add(roleFilter, BorderLayout.EAST);

		JPanel top = new JPanel(new BorderLayout());
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.add(header, BorderLayout.NORTH);
		top.add(filterBar, BorderLayout.CENTER);

		// ---- scrollable list ----
		listContent = new JPanel();
		listContent.setLayout(new BoxLayout(listContent, BoxLayout.Y_AXIS));
		listContent.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Width-tracking view (see ScrollableColumn): rows otherwise clip under the vertical
		// scrollbar, hiding the right-hand timestamps. NORTH keeps a short list top-anchored.
		JPanel column = new ScrollableColumn(new BorderLayout());
		column.setBackground(ColorScheme.DARK_GRAY_COLOR);
		column.add(listContent, BorderLayout.NORTH);

		scroll = new JScrollPane(column);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getVerticalScrollBar().setUnitIncrement(16);

		statusLabel = new JLabel();
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

		add(top, BorderLayout.NORTH);
		add(scroll, BorderLayout.CENTER);
		add(statusLabel, BorderLayout.SOUTH);

		refreshTimer = new Timer(REFRESH_INTERVAL_MS, e -> refresh());
		refreshTimer.setRepeats(true);

		// Refresh whenever the panel actually becomes visible (reliable across the tab group's
		// show/hide, unlike AncestorListener), and only tick the periodic refresh while it's on screen.
		addHierarchyListener(e ->
		{
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0)
			{
				return;
			}
			if (isShowing())
			{
				refresh();
				refreshTimer.start();
			}
			else
			{
				refreshTimer.stop();
			}
		});
	}

	/** Register a callback fired when a favourite/block is toggled here, so sibling tabs refresh. */
	void setOnFlagChanged(Runnable onFlagChanged)
	{
		this.onFlagChanged = onFlagChanged;
	}

	/** Re-render (so the just-actioned player's buttons drop away) and refresh the sibling tabs. */
	private void flagsChanged()
	{
		refresh();
		if (onFlagChanged != null)
		{
			onFlagChanged.run();
		}
	}

	/** Rebuild the list from the current history, preserving scroll position. Safe to call on the EDT. */
	void refresh()
	{
		int scrollPos = scroll.getVerticalScrollBar().getValue();

		listContent.removeAll();
		List<PartyHistoryEntry> history = historyService.list();

		String query = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
		String role = (String) roleFilter.getSelectedItem();

		List<PartyHistoryEntry> shown = new ArrayList<>();
		for (PartyHistoryEntry entry : history)
		{
			if (matches(entry, query, role))
			{
				shown.add(entry);
			}
		}

		clearButton.setVisible(!history.isEmpty());
		statusLabel.setText(history.isEmpty() ? "No parties yet."
			: shown.isEmpty() ? "No matching parties." : "");

		for (PartyHistoryEntry entry : shown)
		{
			listContent.add(buildRow(entry));
			listContent.add(Box.createVerticalStrut(4));
		}
		listContent.revalidate();
		listContent.repaint();

		// Restore the scroll offset after layout so a periodic refresh doesn't jump the user to the top.
		SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(scrollPos));
	}

	/** Soft green marking a member still in the party, mirrored from the live roster's "online" dot. */
	private static final Color PRESENT_COLOR = new Color(0x4C, 0xAF, 0x50);

	/** RuneLite's config-section caret (grey): points right when collapsed, down when expanded. */
	private static final ImageIcon CARET_COLLAPSED = caret(0);
	private static final ImageIcon CARET_EXPANDED = caret(Math.PI / 2);

	private static ImageIcon caret(double rotation)
	{
		BufferedImage arrow = ImageUtil.loadImageResource(HistoryPanel.class, "/util/arrow_right.png");
		if (arrow == null)
		{
			return null;
		}
		BufferedImage grey = ImageUtil.luminanceOffset(arrow, -121);
		if (rotation != 0)
		{
			grey = ImageUtil.rotateImage(grey, rotation);
		}
		return new ImageIcon(grey);
	}

	/**
	 * One history row: a clickable header (activity, role, roster summary, when) that toggles a
	 * detail panel listing every member with their join/leave times. Rows with no recorded roster
	 * aren't expandable.
	 */
	private JPanel buildRow(PartyHistoryEntry entry)
	{
		JPanel container = new JPanel();
		container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
		container.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		container.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel header = new JPanel(new BorderLayout(6, 0));
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		header.setAlignmentX(Component.LEFT_ALIGNMENT);

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

		// Compact roster line (present members, host first), truncated to keep the row tidy.
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

		// Per-row delete (drawn X, no confirm — it's one row; Clear covers the nuke case).
		JButton delete = iconButton(StatusIcons.CROSS, "Remove this party from your history");
		delete.addActionListener(e ->
		{
			historyService.delete(entry);
			expanded.remove(keyOf(entry));
			SwingUtilities.invokeLater(this::refresh);
		});

		JPanel east = new JPanel();
		east.setLayout(new BoxLayout(east, BoxLayout.X_AXIS));
		east.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		east.add(when);
		east.add(Box.createHorizontalStrut(4));
		east.add(delete);

		header.add(text, BorderLayout.CENTER);
		header.add(east, BorderLayout.EAST);

		boolean expandable = entry.getMembers() != null && !entry.getMembers().isEmpty();
		if (!expandable)
		{
			container.add(header);
			container.setMaximumSize(new Dimension(Integer.MAX_VALUE, container.getPreferredSize().height));
			return container;
		}

		String key = keyOf(entry);
		boolean open = expanded.contains(key);

		// A caret on the left signals (and toggles) the collapsible roster detail below, matching
		// the disclosure carets on the Friends and Search tabs.
		JLabel chevron = new JLabel(open ? CARET_EXPANDED : CARET_COLLAPSED);
		chevron.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
		header.add(chevron, BorderLayout.WEST);

		JPanel detail = buildDetail(entry);
		detail.setVisible(open); // restore what the user had open before a re-render
		container.add(header);
		container.add(detail);

		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				boolean show = !detail.isVisible();
				detail.setVisible(show);
				chevron.setIcon(show ? CARET_EXPANDED : CARET_COLLAPSED);
				if (show)
				{
					expanded.add(key);
				}
				else
				{
					expanded.remove(key);
				}
				container.setMaximumSize(new Dimension(Integer.MAX_VALUE,
					container.getLayout().preferredLayoutSize(container).height));
				listContent.revalidate();
				listContent.repaint();
			}
		});

		container.setMaximumSize(new Dimension(Integer.MAX_VALUE, container.getPreferredSize().height));
		return container;
	}

	/** The collapsible roster: present members first (host first), then those who have left, greyed. */
	private JPanel buildDetail(PartyHistoryEntry entry)
	{
		JPanel detail = new JPanel();
		detail.setLayout(new BoxLayout(detail, BoxLayout.Y_AXIS));
		detail.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		detail.setAlignmentX(Component.LEFT_ALIGNMENT);
		detail.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(4, 12, 6, 8)));

		// Present members first so the current roster reads top-to-bottom, then the ones who left.
		// Skip unidentified rows (blank / "<unknown>" placeholder) that older data may have captured.
		for (HistoryMember m : entry.getMembers())
		{
			if (m != null && m.isPresent() && isNamed(m))
			{
				detail.add(memberLine(entry, m));
			}
		}
		for (HistoryMember m : entry.getMembers())
		{
			if (m != null && !m.isPresent() && isNamed(m))
			{
				detail.add(memberLine(entry, m));
			}
		}
		return detail;
	}

	/** One member line: name (host tagged) on the left; favourite, block, then join/leave times on the right. */
	private JPanel memberLine(PartyHistoryEntry entry, HistoryMember m)
	{
		JPanel line = new JPanel(new BorderLayout(6, 0));
		line.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		line.setAlignmentX(Component.LEFT_ALIGNMENT);

		String name = m.getName() == null || m.getName().isEmpty() ? "?" : m.getName();
		boolean isHost = sameName(name, entry.getHost());
		JLabel nameLabel = new JLabel(isHost ? name + " (host)" : name);
		nameLabel.setFont(FontManager.getRunescapeSmallFont());
		nameLabel.setForeground(m.isPresent() ? PRESENT_COLOR : ColorScheme.MEDIUM_GRAY_COLOR);

		// Plain ASCII hyphen: the en dash renders as a missing-glyph box in the RuneScape font on Linux.
		String span = clock(m.getJoinedAt()) + " - " + (m.isPresent() ? "now" : clock(m.getLeftAt()));
		JLabel timeLabel = new JLabel(span);
		timeLabel.setFont(FontManager.getRunescapeSmallFont());
		timeLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		// Fixed-width, right-aligned time column: the span varies per row ("- now" vs "- 13:45"),
		// which would otherwise shift the favourite/block icons out of column alignment.
		timeLabel.setHorizontalAlignment(SwingConstants.RIGHT);
		Dimension timeSize = new Dimension(
			timeLabel.getFontMetrics(timeLabel.getFont()).stringWidth("00:00 - 00:00"),
			timeLabel.getPreferredSize().height);
		timeLabel.setPreferredSize(timeSize);
		timeLabel.setMinimumSize(timeSize);
		timeLabel.setMaximumSize(timeSize);

		line.add(nameLabel, BorderLayout.WEST);

		// Right side, in reading order: favourite, block, then the join/leave span.
		JPanel right = new JPanel();
		right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
		right.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JComponent actions = memberActions(m);
		if (actions != null)
		{
			right.add(actions);
			right.add(Box.createHorizontalStrut(6));
		}
		right.add(timeLabel);

		line.add(right, BorderLayout.EAST);
		line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
		return line;
	}

	/**
	 * A favourite + block toggle pair for {@code m}, reflecting current state (filled star / red ban
	 * when set), or {@code null} for an unnamed row. For your own row the pair is still shown but
	 * disabled (you can't favourite or block yourself), which also keeps the action column aligned
	 * with every other row. Favouriting and blocking are mutually exclusive, so setting one clears the
	 * other.
	 */
	private JComponent memberActions(HistoryMember m)
	{
		if (favoritesService == null || blockListService == null || !isNamed(m))
		{
			return null;
		}
		final long hash = m.getAccountHash();
		final String name = m.getName();
		final boolean self = blockListService.isSelf(hash, name);
		boolean isFavorite = favoritesService.isFavorite(hash, name);
		boolean isBlocked = blockListService.isBlocked(hash, name);

		JPanel actions = new JPanel();
		actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
		actions.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JButton fav = iconButton(isFavorite ? StatusIcons.STAR_FILLED : StatusIcons.STAR_OUTLINE,
			self ? "You can't favourite yourself"
				: (isFavorite ? "Remove " + name + " from" : "Add " + name + " to") + " Favorites");
		JButton block = iconButton(isBlocked ? StatusIcons.BLOCK_ON : StatusIcons.BLOCK_OFF,
			self ? "You can't block yourself" : (isBlocked ? "Unblock " + name : "Block " + name));

		if (self)
		{
			// Grey the icons out and skip the listeners: you can't favourite or block yourself.
			disableButton(fav);
			disableButton(block);
			actions.add(fav);
			actions.add(Box.createHorizontalStrut(2));
			actions.add(block);
			return actions;
		}

		fav.addActionListener(e ->
		{
			favoritesService.toggle(hash, name);
			if (!isFavorite && blockListService.isBlocked(hash, name))
			{
				blockListService.toggle(hash, name);
			}
			flagsChanged();
		});

		block.addActionListener(e ->
		{
			if (!isBlocked && !BlockConfirm.confirm(block, name))
			{
				return;
			}
			blockListService.toggle(hash, name);
			if (!isBlocked && favoritesService.isFavorite(hash, name))
			{
				favoritesService.toggle(hash, name);
			}
			flagsChanged();
		});

		actions.add(fav);
		actions.add(Box.createHorizontalStrut(2));
		actions.add(block);
		return actions;
	}

	/** Grey out and neutralise an inline action button (Swing auto-greys the icon; drop the hand cursor). */
	private static void disableButton(JButton button)
	{
		button.setEnabled(false);
		button.setCursor(Cursor.getDefaultCursor());
	}

	/** A borderless, transparent icon button sized for an inline roster action. */
	private static JButton iconButton(ImageIcon icon, String tooltip)
	{
		JButton button = new JButton(icon);
		button.setFocusPainted(false);
		button.setContentAreaFilled(false);
		button.setBorderPainted(false);
		button.setMargin(new Insets(0, 2, 0, 2));
		button.setToolTipText(tooltip);
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return button;
	}

	/**
	 * Whether {@code entry} passes the current filters: the role selector (hosted/joined/all) and a
	 * case-insensitive substring {@code query} (already lower-cased) over the activity title, host name,
	 * and every member name. An empty query matches everything within the selected role.
	 */
	private static boolean matches(PartyHistoryEntry entry, String query, String role)
	{
		if (ROLE_HOSTED.equals(role) && !entry.isHosted())
		{
			return false;
		}
		if (ROLE_JOINED.equals(role) && entry.isHosted())
		{
			return false;
		}
		if (query.isEmpty())
		{
			return true;
		}
		if (containsIgnoreCase(activityTitle(entry), query) || containsIgnoreCase(entry.getHost(), query))
		{
			return true;
		}
		if (entry.getMembers() != null)
		{
			for (HistoryMember m : entry.getMembers())
			{
				if (m != null && containsIgnoreCase(m.getName(), query))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Substring test where {@code needleLower} is already lower-cased; false for a null haystack. */
	private static boolean containsIgnoreCase(String haystack, String needleLower)
	{
		return haystack != null && haystack.toLowerCase().contains(needleLower);
	}

	/** A stable key for an entry's expand state: the party id, or host+time when the id is absent. */
	private static String keyOf(PartyHistoryEntry entry)
	{
		return entry.getPartyId() != null ? entry.getPartyId() : entry.getHost() + "@" + entry.getJoinedAt();
	}

	private static boolean sameName(String a, String b)
	{
		return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
	}

	/** False for a member we can't name: null/blank, or RuneLite's unsynced {@code "<unknown>"} placeholder. */
	private static boolean isNamed(HistoryMember m)
	{
		String n = m.getName();
		return n != null && !n.trim().isEmpty() && !"<unknown>".equalsIgnoreCase(n.trim());
	}

	/** Local wall-clock time ({@code HH:mm}) for an epoch-millis stamp; blank when unknown (0). */
	private static String clock(long when)
	{
		return when <= 0 ? "?" : new SimpleDateFormat("HH:mm").format(new Date(when));
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

	/**
	 * Comma-joined names of the members currently in the party (host first, blanks skipped), or
	 * {@code null} when none were recorded. Members who have left are omitted from this summary line —
	 * they still appear, greyed, in the expanded detail.
	 */
	private static String memberNames(PartyHistoryEntry entry)
	{
		List<HistoryMember> members = entry.getMembers();
		if (members == null || members.isEmpty())
		{
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (HistoryMember m : members)
		{
			if (m == null || !m.isPresent() || !isNamed(m))
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
		// ASCII dots, not the ellipsis glyph — same missing-glyph issue as the en dash on Linux.
		return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "...";
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
