package net.osparty.ui;

import java.awt.Component;
import javax.swing.JOptionPane;

final class ReportConfirm
{
	private ReportConfirm()
	{
	}

	static boolean confirm(Component parent, String name)
	{
		String who = escape(name == null ? "this player" : name);
		String message = "<html><body style='width:230px'>"
			+ "Report <b>" + who + "</b>'s advertisement?<br><br>"
			+ "Use this for adverts that don't belong on the board — boosting or account services, "
			+ "gold selling, scams, or offensive content."
			+ "<ul style='margin-top:6px;margin-left:14px'>"
			+ "<li>A moderator reviews the advert itself.</li>"
			+ "<li>You won't be told the outcome.</li>"
			+ "<li>You can only report an advert once.</li>"
			+ "</ul>"
			+ "To just stop seeing someone, use <b>Block host</b> instead."
			+ "</body></html>";

		int choice = JOptionPane.showConfirmDialog(parent, message,
			"Report " + (name == null ? "advertisement" : name),
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		return choice == JOptionPane.OK_OPTION;
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
