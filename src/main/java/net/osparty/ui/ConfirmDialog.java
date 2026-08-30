package net.osparty.ui;

import java.awt.Component;
import javax.swing.JOptionPane;

/** OK/Cancel warning dialogs whose body is HTML, used by {@link BlockConfirm}; {@link ReportConfirm} shares {@link #escape}. */
final class ConfirmDialog
{
	private ConfirmDialog()
	{
	}

	/** @return true if the user confirmed and the caller should proceed. */
	static boolean ask(Component parent, String title, String bodyHtml)
	{
		String message = "<html><body style='width:230px'>" + bodyHtml + "</body></html>";
		int choice = JOptionPane.showConfirmDialog(parent, message, title,
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		return choice == JOptionPane.OK_OPTION;
	}

	/** Neutralise the few HTML-significant characters, since the body renders as HTML. */
	static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
