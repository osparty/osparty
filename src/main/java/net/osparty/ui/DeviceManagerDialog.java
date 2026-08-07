package net.osparty.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import net.osparty.api.OSPartySocket;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * "Which machines can sign in as me, and can I kick one out." The only OSParty UI for anything to do with
 * the per-install credential: what {@link net.osparty.store.CredentialStore} holds locally has no view of
 * its own, because the interesting question is never "what does this machine believe" but "what does the
 * server still honour" — and only the server can answer that.
 *
 * <p>Non-modal on purpose: it is talking to the server over the same connection the rest of the plugin
 * uses, and there is no reason revoking a device should freeze the game client while the round trip is in
 * flight.
 */
public final class DeviceManagerDialog extends JDialog
{
	private final OSPartySocket socket;
	private final AccountRecoveryController recovery;
	private final JPanel rows = new JPanel();
	private final JLabel codesLabel = new JLabel();

	private DeviceManagerDialog(Component parent, OSPartySocket socket, AccountRecoveryController recovery)
	{
		super(SwingUtilities.getWindowAncestor(parent), "OSParty — Devices", ModalityType.MODELESS);
		this.socket = socket;
		this.recovery = recovery;

		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		rows.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel hint = new JLabel("<html><body style='width:260px'>"
			+ "Every device below can currently sign in to OSParty as your account. "
			+ "Revoke one you don't recognise, or one you no longer use."
			+ "</body></html>");
		hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		hint.setFont(FontManager.getRunescapeSmallFont());
		hint.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		JScrollPane scroll = new JScrollPane(rows);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);

		setLayout(new BorderLayout());
		add(hint, BorderLayout.NORTH);
		add(scroll, BorderLayout.CENTER);
		add(recoveryRow(), BorderLayout.SOUTH);
		getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);
		setSize(320, 400);
		setLocationRelativeTo(parent);
	}

	/**
	 * Fetch the device list and show it. The dialog appears already populated rather than empty-then-filled,
	 * since the round trip is usually well under a second and an empty dialog reads as "you have no devices"
	 * for the moment before the reply lands.
	 */
	public static void open(Component parent, OSPartySocket socket, AccountRecoveryController recovery)
	{
		// Nothing here can be answered without a signed-in connection, and the server refuses the request
		// rather than returning an empty list -- so without this the button appeared to do nothing at all.
		if (!socket.isSignedIn())
		{
			recovery.openRecovery();
			return;
		}
		socket.listDevices(devices -> SwingUtilities.invokeLater(() ->
		{
			DeviceManagerDialog dialog = new DeviceManagerDialog(parent, socket, recovery);
			dialog.render(devices);
			dialog.refreshRecoveryCount();
			dialog.setVisible(true);
		}));
	}

	/**
	 * The recovery-code footer: how many are left, and a way to replace them.
	 *
	 * <p>Here rather than in its own dialog because it answers the same question as the list above it --
	 * "what can get into my account" -- and because the count is the only prompt anyone will ever get to
	 * notice they have run out.
	 */
	private JPanel recoveryRow()
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, ColorScheme.DARKER_GRAY_COLOR),
			BorderFactory.createEmptyBorder(8, 8, 8, 8)));

		codesLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		codesLabel.setFont(FontManager.getRunescapeSmallFont());
		codesLabel.setToolTipText("One-time codes that sign a new device in when no other device can");
		row.add(codesLabel, BorderLayout.CENTER);

		JButton generate = new JButton("New codes");
		generate.setFont(FontManager.getRunescapeSmallFont());
		generate.setToolTipText("Generate a fresh set. Any codes you saved before stop working.");
		generate.addActionListener(e -> recovery.regenerateRecoveryCodes(this, this::refreshRecoveryCount));
		row.add(generate, BorderLayout.EAST);
		return row;
	}

	private void refreshRecoveryCount()
	{
		codesLabel.setText("Recovery codes: …");
		recovery.requestRecoveryCount(remaining -> codesLabel.setText(remaining == 0
			? "No recovery codes left"
			: "Recovery codes: " + remaining + " left"));
	}

	private void render(List<OSPartySocket.DeviceInfo> devices)
	{
		rows.removeAll();
		if (devices.isEmpty())
		{
			JLabel empty = new JLabel("No devices on record.");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setFont(FontManager.getRunescapeSmallFont());
			empty.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
			rows.add(empty);
		}
		else
		{
			for (OSPartySocket.DeviceInfo device : devices)
			{
				rows.add(deviceRow(device));
			}
		}
		rows.revalidate();
		rows.repaint();
	}

	private JPanel deviceRow(OSPartySocket.DeviceInfo device)
	{
		JPanel row = PanelWidgets.cappedRow(new BorderLayout(6, 0));
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARKER_GRAY_COLOR),
			BorderFactory.createEmptyBorder(6, 8, 6, 8)));

		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel(device.label != null && !device.label.isEmpty()
			? device.label : "Device enrolled " + formatDate(device.issuedAt));
		title.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		title.setFont(FontManager.getRunescapeSmallFont());
		text.add(title);

		JLabel lastSeen = new JLabel("Last seen " + formatDate(device.lastSeenAt));
		lastSeen.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		lastSeen.setFont(FontManager.getRunescapeSmallFont());
		text.add(lastSeen);

		row.add(text, BorderLayout.CENTER);

		JButton rename = new JButton("Rename");
		rename.setFont(FontManager.getRunescapeSmallFont());
		rename.addActionListener(e -> onRenameClicked(device));
		JButton revoke = new JButton("Revoke");
		revoke.setFont(FontManager.getRunescapeSmallFont());
		revoke.addActionListener(e -> onRevokeClicked(device, row));
		JPanel actionWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		actionWrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		actionWrap.add(rename);
		actionWrap.add(revoke);
		row.add(actionWrap, BorderLayout.EAST);

		return row;
	}

	private void onRenameClicked(OSPartySocket.DeviceInfo device)
	{
		String current = device.label != null ? device.label : "";
		String label = JOptionPane.showInputDialog(this, "Name for this device:", current);
		// Cancelled, or unchanged -- either way there is nothing to send.
		if (label == null || label.equals(current))
		{
			return;
		}
		// Re-fetch either way rather than patching this one row: on success the row holds a stale DeviceInfo,
		// and on failure (almost always the device having been revoked from elsewhere since the dialog
		// opened) this folds it into "the list was stale" instead of a failure needing its own explanation.
		socket.renameDevice(device.id, label, success ->
			socket.listDevices(devices -> SwingUtilities.invokeLater(() -> render(devices))));
	}

	private void onRevokeClicked(OSPartySocket.DeviceInfo device, JPanel row)
	{
		String name = device.label != null && !device.label.isEmpty() ? device.label : "this device";
		if (!ConfirmDialog.ask(this, "Revoke device",
			"Sign out " + ConfirmDialog.escape(name) + "? It will need to be set up again to use OSParty."))
		{
			return;
		}
		socket.revokeDevice(device.id, success -> SwingUtilities.invokeLater(() ->
		{
			if (success)
			{
				rows.remove(row);
				rows.revalidate();
				rows.repaint();
			}
			// A false result almost always means the device was already gone (revoked from elsewhere, or
			// expired) by the time this was clicked. Re-fetching rather than showing an error folds that
			// case into "the list was stale" instead of treating it as a failure needing explanation.
			else
			{
				socket.listDevices(devices -> SwingUtilities.invokeLater(() -> render(devices)));
			}
		}));
	}

	private static String formatDate(long epochMillis)
	{
		if (epochMillis <= 0)
		{
			return "unknown";
		}
		return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(epochMillis));
	}
}
