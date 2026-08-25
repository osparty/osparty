package net.osparty.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Shown the moment the server refuses to sign this device in as a character that is already
 * signed in elsewhere. This replaces a dialog that used to demand a six-digit code unconditionally
 * — including on the ordinary occasions when no such code existed anywhere for the user to type —
 * so every row here is gated on whether that route genuinely exists right now, and when none do,
 * the dialog says so plainly instead of presenting a form with no way to fill it in.
 *
 * <p>Pure view: every action taken here — a typed code, a Discord click, a retry — leaves through
 * one of the callbacks passed into {@link #show}. None of them close the dialog, because the
 * result comes back asynchronously on whatever channel the controller owns; only the controller
 * knows when (or whether) an attempt actually succeeded, which is why it holds the instance
 * {@link #show} returns and either disposes it or reports back through {@link #setStatus}.
 */
public final class AccountRecoveryDialog extends JDialog
{
	private final JLabel status = new JLabel(" ");
	/** Every route's action button, so a resolved attempt can put them all back within reach. */
	private final java.util.List<JButton> routeButtons = new java.util.ArrayList<>();
	/** The coupling row's field and its own "Sign in" button — present only when {@code coupling} was true. */
	private JTextField couplingField;
	private JButton couplingSignIn;
	private JButton couplingSendCode;
	/**
	 * Whether a code has actually landed on the other device yet. Guards {@link #couplingSignIn} against
	 * {@link #setStatus}, which otherwise re-arms every route button on any unrelated failure — a coupling
	 * field with no code behind it would then silently accept typing that can never succeed.
	 */
	private boolean codeSent;

	private AccountRecoveryDialog(Component parent, String characterName, boolean coupling, boolean recoveryCodes,
		boolean discord, Runnable onRequestCode, Consumer<String> onCouplingCode, Consumer<String> onRecoveryCode,
		Runnable onDiscordRecovery, Runnable onRetry)
	{
		// Modeless, for the same reason DeviceManagerDialog is: this is talking to the server, and there is
		// no reason to freeze the game client while a round trip is in flight. It is also load-bearing --
		// a modal setVisible(true) does not return until the dialog is dismissed, so the controller would
		// not hold the instance until it no longer needed it, and neither setStatus nor the automatic
		// dismissal on a successful sign-in could ever reach it.
		super(parent == null ? null : SwingUtilities.getWindowAncestor(parent), "OSParty: couldn't sign in",
			ModalityType.MODELESS);

		String who = characterName == null || characterName.isEmpty() ? "this character" : characterName;
		boolean anyRoute = coupling || recoveryCodes || discord;

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
		content.add(lead(who));

		if (coupling)
		{
			content.add(spacer());
			content.add(couplingRow(onRequestCode, onCouplingCode));
		}
		if (recoveryCodes)
		{
			content.add(spacer());
			content.add(recoveryCodeRow(onRecoveryCode));
		}
		if (discord)
		{
			content.add(spacer());
			content.add(discordRow(onDiscordRecovery));
		}
		if (!anyRoute)
		{
			content.add(spacer());
			content.add(noRouteParagraph());
		}

		status.setForeground(ColorScheme.PROGRESS_INPROGRESS_COLOR);
		status.setFont(FontManager.getRunescapeSmallFont());
		status.setBorder(BorderFactory.createEmptyBorder(6, 10, 0, 10));
		status.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel south = new JPanel();
		south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
		south.setBackground(ColorScheme.DARK_GRAY_COLOR);
		south.add(status);
		south.add(buttonRow(onRetry));

		setLayout(new BorderLayout());
		getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(content, BorderLayout.CENTER);
		add(south, BorderLayout.SOUTH);

		pack();
		setLocationRelativeTo(parent);
	}

	/**
	 * Show the dialog and return the instance so the controller can {@link #dispose()} it once an
	 * asynchronous route attempt resolves, or call {@link #setStatus} to report progress or failure
	 * back onto it while it's still open.
	 *
	 * @param characterName the character the sign-in was for, or null to say "this character"
	 * @param coupling      true if a signed-in device for this account is online somewhere right now
	 * @param recoveryCodes true if this account has unspent recovery codes
	 * @param discord       true if this account can be recovered through a linked Discord account
	 */
	public static AccountRecoveryDialog show(Component parent, String characterName, boolean coupling,
		boolean recoveryCodes, boolean discord, Runnable onRequestCode, Consumer<String> onCouplingCode,
		Consumer<String> onRecoveryCode, Runnable onDiscordRecovery, Runnable onRetry)
	{
		AccountRecoveryDialog dialog = new AccountRecoveryDialog(parent, characterName, coupling, recoveryCodes,
			discord, onRequestCode, onCouplingCode, onRecoveryCode, onDiscordRecovery, onRetry);
		dialog.setVisible(true);
		return dialog;
	}

	/**
	 * Report the outcome of whichever route was last attempted. Null clears it.
	 *
	 * <p>Re-enables every route button, because the controller only calls this once an attempt has
	 * resolved — and the usual reason it resolved badly is a mistyped code, which the user has to be able
	 * to correct. A success disposes the dialog rather than reaching here.
	 */
	public void setStatus(String message)
	{
		status.setText(message == null ? " " : message);
		for (JButton button : routeButtons)
		{
			button.setEnabled(true);
		}
		// Only re-arm the coupling row's own Sign in button once a code has actually been sent to it —
		// otherwise an unrelated failure (e.g. a bad recovery code) would silently enable a field with no
		// code behind it. It isn't in routeButtons for exactly that reason.
		if (couplingSignIn != null && codeSent)
		{
			couplingSignIn.setEnabled(true);
		}
	}

	/**
	 * The server answered a {@link net.osparty.api.OSPartySocket#requestCouplingCode()} call.
	 *
	 * @param reached how many of this account's other devices were shown the code; 0 means none were online
	 */
	public void couplingCodeSent(int reached)
	{
		if (couplingSendCode != null)
		{
			// Retryable either way -- a zero-reach answer is not an error, just nobody home right now.
			couplingSendCode.setEnabled(true);
		}
		if (reached > 0)
		{
			codeSent = true;
			if (couplingField != null)
			{
				couplingField.setEnabled(true);
				couplingField.requestFocusInWindow();
			}
			if (couplingSignIn != null)
			{
				couplingSignIn.setEnabled(true);
			}
			status.setText(reached == 1 ? "Code sent to your other device."
				: "Code sent to your " + reached + " other devices.");
		}
		else
		{
			status.setText("No other device turned out to be online. Try one of the other options below.");
		}
	}

	/** Mark an attempt in flight. Not {@link #setStatus}, which would undo the disable it pairs with. */
	private void checking()
	{
		status.setText("Checking…");
	}

	private JLabel lead(String who)
	{
		String escaped = ConfirmDialog.escape(who);
		String body = "OSParty couldn't sign this device in as <b>" + escaped + "</b>, because the account "
			+ "is already signed in on another device.<br><br>"
			+ "Browsing and joining parties still work as normal. Hosting under <b>" + escaped + "</b> won't, "
			+ "until this device is signed in.";
		JLabel label = new JLabel("<html><body style='width:290px'>" + body + "</body></html>");
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private JPanel couplingRow(Runnable onRequestCode, Consumer<String> onCouplingCode)
	{
		JTextField field = new JTextField();
		field.setFont(FontManager.getRunescapeSmallFont());
		// Nothing to type yet -- no code exists until "Send code" is pressed and the server confirms it went
		// somewhere. A field you cannot fill is clearer than one that silently does nothing.
		field.setEnabled(false);

		JButton signIn = new JButton("Sign in");
		signIn.setFont(FontManager.getRunescapeSmallFont());
		signIn.setEnabled(false);
		Runnable submit = () ->
		{
			String code = field.getText().trim();
			if (code.isEmpty())
			{
				return;
			}
			signIn.setEnabled(false);
			checking();
			onCouplingCode.accept(code);
		};
		signIn.addActionListener(e -> submit.run());
		field.addActionListener(e -> submit.run());

		JButton sendCode = new JButton("Send code");
		sendCode.setFont(FontManager.getRunescapeSmallFont());
		sendCode.addActionListener(e ->
		{
			sendCode.setEnabled(false);
			status.setText("Sending code…");
			onRequestCode.run();
		});
		routeButtons.add(sendCode);

		couplingField = field;
		couplingSignIn = signIn;
		couplingSendCode = sendCode;

		JPanel row = PanelWidgets.cappedColumn();
		row.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));

		JTextArea desc = PanelWidgets.wrappingText();
		desc.setText("OSParty can send a six-digit code to your other signed-in device. Once it arrives, "
			+ "type it here to sign in.");
		desc.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		row.add(desc);

		JPanel sendRow = new JPanel(new BorderLayout());
		sendRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		sendRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		sendRow.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		sendRow.add(sendCode, BorderLayout.EAST);
		row.add(sendRow);

		JPanel controls = new JPanel(new BorderLayout(6, 0));
		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
		controls.setAlignmentX(Component.LEFT_ALIGNMENT);
		controls.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		controls.add(field, BorderLayout.CENTER);
		controls.add(signIn, BorderLayout.EAST);
		row.add(controls);

		return row;
	}

	private JPanel recoveryCodeRow(Consumer<String> onRecoveryCode)
	{
		JTextField field = new JTextField();
		field.setFont(FontManager.getRunescapeSmallFont());

		JButton useCode = new JButton("Use code");
		useCode.setFont(FontManager.getRunescapeSmallFont());
		Runnable submit = () ->
		{
			String code = field.getText().trim();
			if (code.isEmpty())
			{
				return;
			}
			useCode.setEnabled(false);
			checking();
			onRecoveryCode.accept(code);
		};
		useCode.addActionListener(e -> submit.run());
		field.addActionListener(e -> submit.run());

		return routeRow("You can use one of the recovery codes you saved for this account.", field, useCode);
	}

	private JPanel discordRow(Runnable onDiscordRecovery)
	{
		JButton recover = new JButton("Recover with Discord");
		recover.setFont(FontManager.getRunescapeSmallFont());
		recover.addActionListener(e ->
		{
			recover.setEnabled(false);
			checking();
			onDiscordRecovery.run();
		});

		return routeRow("You can prove it's you through the Discord account linked to this character.",
			null, recover);
	}

	/** A description above a single control row: an optional input filling the space, and a button on the right. */
	private JPanel routeRow(String description, JTextField input, JButton action)
	{
		JPanel row = PanelWidgets.cappedColumn();
		row.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));

		JTextArea desc = PanelWidgets.wrappingText();
		desc.setText(description);
		desc.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		row.add(desc);

		routeButtons.add(action);

		JPanel controls = new JPanel(new BorderLayout(6, 0));
		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
		controls.setAlignmentX(Component.LEFT_ALIGNMENT);
		controls.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		if (input != null)
		{
			controls.add(input, BorderLayout.CENTER);
		}
		controls.add(action, BorderLayout.EAST);
		row.add(controls);

		return row;
	}

	private JTextArea noRouteParagraph()
	{
		JTextArea area = PanelWidgets.wrappingText();
		area.setText("This character is signed in on a device that isn't available right now, and there's "
			+ "no saved way back in from here. Hosting will work again from whichever device is still signed in.");
		area.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return area;
	}

	private JPanel buttonRow(Runnable onRetry)
	{
		JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JButton retry = new JButton("Try again");
		retry.setFont(FontManager.getRunescapeSmallFont());
		retry.addActionListener(e -> onRetry.run());
		row.add(retry);

		JButton close = new JButton("Close");
		close.setFont(FontManager.getRunescapeSmallFont());
		close.addActionListener(e -> dispose());
		row.add(close);

		return row;
	}

	private static Component spacer()
	{
		return Box.createVerticalStrut(8);
	}
}
