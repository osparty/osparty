package net.osparty.ui;

import java.awt.Component;
import javax.swing.JOptionPane;

/**
 * Shared confirmation for blocking a player. Blocking is a rare, easily-misclicked action with
 * several side effects (parties hidden from Search, applicant handling, favourite cleared), so
 * every block entry point routes through here first. Unblocking is not confirmed.
 */
final class BlockConfirm
{
	private BlockConfirm()
	{
	}

	/**
	 * Ask the user to confirm blocking {@code name}, spelling out the consequences.
	 *
	 * @return true if the user confirmed and the caller should proceed with the block.
	 */
	static boolean confirm(Component parent, String name)
	{
		String who = escape(name == null ? "this player" : name);
		String message = "<html><body style='width:230px'>"
			+ "Block <b>" + who + "</b>?<br><br>"
			+ "While they are blocked:"
			+ "<ul style='margin-top:2px;margin-left:14px'>"
			+ "<li>Their parties are hidden from Search (unless <i>Show blocked parties</i> is turned on).</li>"
			+ "<li>If they apply to a party you host you'll be warned, or they'll be auto-declined, "
			+ "per your <i>Blocked applicant</i> setting.</li>"
			+ "<li>They're removed from your favourites (a player can't be both).</li>"
			+ "</ul>"
			+ "You can undo this any time from the <b>Blocked</b> tab."
			+ "</body></html>";

		int choice = JOptionPane.showConfirmDialog(parent, message, "Block " + (name == null ? "player" : name),
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		return choice == JOptionPane.OK_OPTION;
	}

	/** Neutralise the few HTML-significant characters, since the label renders as HTML. */
	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
