package net.osparty.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * A freshly regenerated batch of recovery codes, shown from the device manager's "New codes" button.
 * The server hands them over once and never again, so from this dialog on, the only surviving copy is
 * whatever the user walks away with.
 *
 * <p>Regeneration only: a first enrolment's codes go through {@link AccountSetupDialog}, at a moment
 * the player chose, rather than through anything that appears on its own.
 */
public final class RecoveryCodesDialog extends JDialog
{
	private RecoveryCodesDialog(Component parent, List<String> codes)
	{
		// Modeless like every other account dialog: this is a sheet to read and copy from, and there is
		// no reason to freeze the game client while the user finds a pen.
		super(parent == null ? null : SwingUtilities.getWindowAncestor(parent), "OSParty recovery codes",
			ModalityType.MODELESS);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);

		setLayout(new BorderLayout());
		getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(explanation(), BorderLayout.NORTH);
		JScrollPane area = RecoveryCodesView.codesArea(codes);
		area.setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 10));
		add(area, BorderLayout.CENTER);
		add(buttons(codes), BorderLayout.SOUTH);

		pack();
		setLocationRelativeTo(parent);
	}

	/** Show a regenerated set of {@code codes}. */
	public static void show(Component parent, List<String> codes)
	{
		new RecoveryCodesDialog(parent, codes).setVisible(true);
	}

	private JLabel explanation()
	{
		JLabel label = new JLabel("<html><body style='width:280px'>"
			+ "These codes are a way back into this account if this device is ever lost or wiped. "
			+ "They're shown now and can't be shown again, so keep them somewhere safe. "
			+ "Any codes issued before this have stopped working."
			+ "</body></html>");
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setBorder(BorderFactory.createEmptyBorder(10, 10, 8, 10));
		return label;
	}

	private JPanel buttons(List<String> codes)
	{
		JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));

		// Nothing to copy or save when there is nothing to show.
		boolean hasCodes = !codes.isEmpty();

		JButton copy = new JButton("Copy");
		copy.setFont(FontManager.getRunescapeSmallFont());
		copy.setEnabled(hasCodes);
		copy.addActionListener(e -> RecoveryCodesView.copyToClipboard(codes, copy));
		row.add(copy);

		JButton save = new JButton("Save to file…");
		save.setFont(FontManager.getRunescapeSmallFont());
		save.setEnabled(hasCodes);
		save.addActionListener(e -> RecoveryCodesView.saveToFile(codes, this));
		row.add(save);

		JButton done = new JButton("Done");
		done.setFont(FontManager.getRunescapeSmallFont());
		done.addActionListener(e -> dispose());
		row.add(done);

		return row;
	}
}
