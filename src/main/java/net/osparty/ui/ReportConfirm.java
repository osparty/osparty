package net.osparty.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;

final class ReportConfirm
{
	/** The server truncates at this length; capping here means nothing typed is silently lost. */
	private static final int DESCRIPTION_LIMIT = 500;

	private ReportConfirm()
	{
	}

	/**
	 * @return the reporter's optional description of the problem (empty when they typed nothing),
	 *     or null when they cancelled
	 */
	static String confirm(Component parent, String name)
	{
		String who = ConfirmDialog.escape(name == null ? "this player" : name);
		String body = "<html><body style='width:230px'>"
			+ "Report <b>" + who + "</b>'s advertisement?<br><br>"
			+ "Use this for adverts that don't belong on the board: boosting or account services, "
			+ "gold selling, scams, or offensive content."
			+ "<ul style='margin-top:6px;margin-left:14px'>"
			+ "<li>A moderator reviews the advert itself.</li>"
			+ "<li>You won't be told the outcome.</li>"
			+ "<li>You can only report an advert once.</li>"
			+ "</ul>"
			+ "To just stop seeing someone, use <b>Block host</b> instead.<br><br>"
			+ "<b>What's wrong?</b> (optional)</body></html>";

		JTextArea description = new JTextArea(3, 20);
		description.setLineWrap(true);
		description.setWrapStyleWord(true);
		((AbstractDocument) description.getDocument()).setDocumentFilter(new LimitFilter());

		JPanel panel = new JPanel(new BorderLayout(0, 6));
		panel.add(new JLabel(body), BorderLayout.NORTH);
		panel.add(new JScrollPane(description), BorderLayout.CENTER);

		int choice = JOptionPane.showConfirmDialog(parent, panel,
			"Report " + (name == null ? "advertisement" : name),
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		return choice == JOptionPane.OK_OPTION ? description.getText().trim() : null;
	}

	private static final class LimitFilter extends DocumentFilter
	{
		@Override
		public void insertString(FilterBypass fb, int offset, String text, AttributeSet attr)
			throws BadLocationException
		{
			replace(fb, offset, 0, text, attr);
		}

		@Override
		public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
			throws BadLocationException
		{
			int room = DESCRIPTION_LIMIT - fb.getDocument().getLength() + length;
			if (text == null || room >= text.length())
			{
				super.replace(fb, offset, length, text, attrs);
			}
			else if (room > 0)
			{
				super.replace(fb, offset, length, text.substring(0, room), attrs);
			}
		}
	}
}
