package net.osparty.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
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
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The one and only look the user ever gets at a freshly issued batch of recovery codes: the
 * server hands them over once and never again, so from this dialog on, the only surviving copy
 * is whatever the user walks away with. Every control here exists to make that more likely —
 * copy, save to disk, or sidestep the whole problem by linking Discord instead.
 *
 * <p>Pure view: {@code onLinkDiscord} is the only way out of this dialog, and it is opaque here —
 * this class has no idea how the codes were generated, where they are stored, or what linking
 * Discord actually does.
 */
public final class RecoveryCodesDialog extends JDialog
{
	/** Rows/columns the code list is sized to, so ten "XXXX-XXXX-XXXX-XXXX" codes need no scrolling. */
	private static final int VISIBLE_ROWS = 10;
	private static final int VISIBLE_COLUMNS = 19;

	private RecoveryCodesDialog(Component parent, List<String> codes, boolean firstTime, Runnable onLinkDiscord)
	{
		super(parent == null ? null : SwingUtilities.getWindowAncestor(parent), "OSParty recovery codes",
			ModalityType.APPLICATION_MODAL);

		setLayout(new BorderLayout());
		getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(explanation(firstTime), BorderLayout.NORTH);
		add(codesArea(codes), BorderLayout.CENTER);
		add(buttons(codes, firstTime, onLinkDiscord), BorderLayout.SOUTH);

		pack();
		setLocationRelativeTo(parent);
	}

	/**
	 * Show {@code codes}. {@code firstTime} tells the copy whether there is anything to warn about
	 * losing (a fresh account has nothing at stake yet) versus a regeneration, where the old codes
	 * are now dead and anyone still holding them is out of luck. {@code onLinkDiscord} is null
	 * whenever Discord linking isn't on offer from here (already linked, or this is a regeneration).
	 */
	public static void show(Component parent, List<String> codes, boolean firstTime, Runnable onLinkDiscord)
	{
		RecoveryCodesDialog dialog = new RecoveryCodesDialog(parent, codes, firstTime, onLinkDiscord);
		dialog.setVisible(true);
	}

	private JLabel explanation(boolean firstTime)
	{
		StringBuilder body = new StringBuilder(
			"These codes are the way back into this account if this device is ever lost or wiped. "
			+ "They're shown now and can't be shown again, so keep them somewhere safe.");
		if (!firstTime)
		{
			body.append(" Any codes issued before this have stopped working.");
		}
		JLabel label = new JLabel("<html><body style='width:280px'>" + body + "</body></html>");
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setBorder(BorderFactory.createEmptyBorder(10, 10, 8, 10));
		return label;
	}

	private JScrollPane codesArea(List<String> codes)
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
		scroll.setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 10));
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		return scroll;
	}

	private JPanel buttons(List<String> codes, boolean firstTime, Runnable onLinkDiscord)
	{
		JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));

		// Nothing to copy or save when there is nothing to show.
		boolean hasCodes = !codes.isEmpty();

		JButton copy = new JButton("Copy");
		copy.setFont(FontManager.getRunescapeSmallFont());
		copy.setEnabled(hasCodes);
		copy.addActionListener(e -> copyToClipboard(codes));
		row.add(copy);

		JButton save = new JButton("Save to file…");
		save.setFont(FontManager.getRunescapeSmallFont());
		save.setEnabled(hasCodes);
		save.addActionListener(e -> saveToFile(codes));
		row.add(save);

		if (firstTime && onLinkDiscord != null)
		{
			JButton link = new JButton("Link Discord too");
			link.setFont(FontManager.getRunescapeSmallFont());
			link.setToolTipText("A Discord account linked to this character is a second way back in "
				+ "that needs nothing written down.");
			link.addActionListener(e ->
			{
				onLinkDiscord.run();
				dispose();
			});
			row.add(link);
		}

		JButton done = new JButton("Done");
		done.setFont(FontManager.getRunescapeSmallFont());
		done.addActionListener(e -> dispose());
		row.add(done);

		return row;
	}

	private void copyToClipboard(List<String> codes)
	{
		Toolkit.getDefaultToolkit().getSystemClipboard()
			.setContents(new StringSelection(String.join("\n", codes)), null);
	}

	private void saveToFile(List<String> codes)
	{
		JFileChooser chooser = new JFileChooser();
		chooser.setSelectedFile(new File("osparty-recovery-codes.txt"));
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
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
			JOptionPane.showMessageDialog(this, "Couldn't save the file: " + e.getMessage(),
				"OSParty", JOptionPane.ERROR_MESSAGE);
		}
	}
}
