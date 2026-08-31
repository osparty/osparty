package net.osparty.ui;

import java.awt.Component;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Timer;
import net.runelite.client.ui.ColorScheme;

/**
 * The pieces every recovery-codes surface shares: the monospaced sheet, copy with visible feedback,
 * and save-to-file. Kept out of the dialogs so {@link RecoveryCodesDialog} (regeneration) and
 * {@link AccountSetupDialog} (first setup) cannot drift apart in how they present the same codes.
 */
final class RecoveryCodesView
{
	/** Rows/columns the code list is sized to, so ten "XXXX-XXXX-XXXX-XXXX" codes need no scrolling. */
	private static final int VISIBLE_ROWS = 10;
	private static final int VISIBLE_COLUMNS = 19;
	/** How long the Copy button says "Copied" before offering itself again. */
	private static final int COPY_FEEDBACK_MS = 2000;

	private RecoveryCodesView()
	{
	}

	static JScrollPane codesArea(List<String> codes)
	{
		JTextArea area = new JTextArea(VISIBLE_ROWS, VISIBLE_COLUMNS);
		area.setText(codes.isEmpty() ? "No codes to show." : String.join("\n", codes));
		area.setEditable(false);
		area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
		area.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		area.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		area.setCaretPosition(0);
		area.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

		JScrollPane scroll = new JScrollPane(area);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		return scroll;
	}

	/** Copy the codes, and say so on the button — a silent copy is indistinguishable from a broken one. */
	static void copyToClipboard(List<String> codes, JButton source)
	{
		Toolkit.getDefaultToolkit().getSystemClipboard()
			.setContents(new StringSelection(String.join("\n", codes)), null);
		String original = source.getText();
		source.setText("Copied");
		source.setEnabled(false);
		Timer restore = new Timer(COPY_FEEDBACK_MS, e ->
		{
			source.setText(original);
			source.setEnabled(true);
		});
		restore.setRepeats(false);
		restore.start();
	}

	static void saveToFile(List<String> codes, Component parent)
	{
		JFileChooser chooser = new JFileChooser();
		chooser.setSelectedFile(new File("osparty-recovery-codes.txt"));
		if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION)
		{
			return;
		}
		Path target = chooser.getSelectedFile().toPath();
		try
		{
			Files.write(target, String.join(System.lineSeparator(), codes).getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			JOptionPane.showMessageDialog(parent, "Couldn't save the file: " + e.getMessage(),
				"OSParty", JOptionPane.ERROR_MESSAGE);
		}
	}
}
