package net.osparty.ui;

import java.awt.Component;
import javax.swing.JOptionPane;

/**
 * Confirmation for reporting an advertisement. Modelled on {@link BlockConfirm}: reporting is a
 * one-click action sitting next to Favourite and Block in the same menu, and it sends a player's
 * advertisement to human moderators, so it is worth one deliberate click.
 *
 * <p>The wording sets expectations that the feature genuinely cannot meet: the reporter is told
 * nothing about the outcome, ever, because reports are reviewed asynchronously and any feedback
 * would also tell an abuser which of their reports landed.
 */
final class ReportConfirm
{
	private ReportConfirm()
	{
	}

	/**
	 * Ask the user to confirm reporting {@code name}'s advertisement.
	 *
	 * @return true if the user confirmed and the caller should send the report.
	 */
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

	/** Neutralise the few HTML-significant characters, since the label renders as HTML. */
	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
