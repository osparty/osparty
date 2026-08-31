package net.osparty.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Recovery setup for a freshly enrolled character, opened from the panel banner — never on its own.
 *
 * <p>This replaces the modal codes popup that used to appear on the first game tick after login: an
 * application-modal dialog that froze the game client mid-world, could be dismissed in one click, and
 * whose codes were then gone for good. Here the player arrives by choosing to, the game keeps running
 * behind it, and closing it loses nothing — the banner keeps the offer open until setup is done.
 *
 * <p>Discord is offered first because it asks nothing of the user's filing habits: a verified link is
 * a way back in from any machine with nothing written down. Codes are the offline fallback, revealed
 * on request, and either route (or both) completes setup. "Done" stays out of reach until one of them
 * has actually happened — the one lesson of the old dialog being that an exit with no gate is the exit
 * everyone takes.
 *
 * <p>Pure view: codes come through {@link CodesRequest}, Discord through {@code onLinkDiscord}, and
 * completion leaves through {@code onComplete}. The controller owns the instance and the state.
 */
public final class AccountSetupDialog extends JDialog
{
	/** How often to ask whether the Discord link the user started in a browser has completed. */
	private static final int LINK_POLL_MS = 1000;

	/** Asks the controller for this account's codes; exactly one of the two callbacks answers. */
	public interface CodesRequest
	{
		void request(Consumer<List<String>> onCodes, Consumer<String> onError);
	}

	private final BooleanSupplier discordLinked;
	private final JLabel status = new JLabel(" ");
	private final JLabel discordState = new JLabel(" ");
	private final JButton linkDiscord;
	private final JButton done;
	private final JPanel codesSlot;
	private final JCheckBox savedCodes = new JCheckBox("I've saved my codes");
	private final Timer linkPoll;
	private boolean codesShown;

	private AccountSetupDialog(Component parent, String characterName, BooleanSupplier discordLinked,
		Runnable onLinkDiscord, CodesRequest codesRequest, Consumer<Boolean> onComplete)
	{
		// Modeless like the rest of the account dialogs: the game keeps running, and the Discord half
		// of setup goes through a browser this window has to survive being behind.
		super(parent == null ? null : SwingUtilities.getWindowAncestor(parent), "OSParty account setup",
			ModalityType.MODELESS);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		this.discordLinked = discordLinked;

		String who = characterName == null || characterName.isEmpty() ? "this character" : characterName;

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
		content.add(lead(who));

		linkDiscord = new JButton("Link Discord");
		linkDiscord.setFont(FontManager.getRunescapeSmallFont());
		linkDiscord.addActionListener(e ->
		{
			linkDiscord.setEnabled(false);
			status.setText("Check your browser to authorise Discord…");
			onLinkDiscord.run();
		});
		content.add(spacer());
		content.add(discordRow());

		codesSlot = PanelWidgets.cappedColumn();
		codesSlot.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
		buildCodesOffer(codesRequest);
		content.add(spacer());
		content.add(codesRow());

		status.setForeground(ColorScheme.PROGRESS_INPROGRESS_COLOR);
		status.setFont(FontManager.getRunescapeSmallFont());
		status.setBorder(BorderFactory.createEmptyBorder(6, 10, 0, 10));
		status.setAlignmentX(Component.LEFT_ALIGNMENT);

		done = new JButton("Done");
		done.setFont(FontManager.getRunescapeSmallFont());
		done.setToolTipText("Finishes setup once Discord is linked or your codes are saved");
		done.addActionListener(e ->
		{
			// Which route finished matters later: a saved sheet is forever, a Discord link can be
			// unlinked — and unlinking with no sheet saved reopens setup.
			onComplete.accept(codesShown && savedCodes.isSelected());
			dispose();
		});
		savedCodes.addActionListener(e -> refreshDone());

		JPanel south = new JPanel();
		south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
		south.setBackground(ColorScheme.DARK_GRAY_COLOR);
		south.add(status);
		south.add(buttonRow());

		setLayout(new BorderLayout());
		getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(content, BorderLayout.CENTER);
		add(south, BorderLayout.SOUTH);

		// The link completes in a browser and lands as panel state, so the dialog watches rather than
		// being told — the same poll-shaped answer the footer button uses.
		linkPoll = new Timer(LINK_POLL_MS, e -> refreshDiscordState());
		linkPoll.start();
		refreshDiscordState();

		pack();
		setLocationRelativeTo(parent);
	}

	/**
	 * Show setup for {@code characterName} and return the instance, so the controller can dispose it
	 * when the character logs out or the plugin shuts down.
	 *
	 * @param discordLinked live link state for the current account, polled while the dialog is open
	 * @param onLinkDiscord the panel's ordinary linking flow — a link made now, from a signed-in
	 *                      device, is exactly what recovery later needs
	 * @param codesRequest  answers with this enrolment's codes, minting a fresh sheet when the
	 *                      originals did not survive a restart
	 * @param onComplete    the user finished — clear the pending flag and retire the banner. Carries
	 *                      whether the codes sheet was confirmed saved, as opposed to Discord alone
	 */
	public static AccountSetupDialog show(Component parent, String characterName,
		BooleanSupplier discordLinked, Runnable onLinkDiscord, CodesRequest codesRequest,
		Consumer<Boolean> onComplete)
	{
		AccountSetupDialog dialog = new AccountSetupDialog(parent, characterName, discordLinked,
			onLinkDiscord, codesRequest, onComplete);
		dialog.setVisible(true);
		return dialog;
	}

	@Override
	public void dispose()
	{
		linkPoll.stop();
		super.dispose();
	}

	private JLabel lead(String who)
	{
		String escaped = ConfirmDialog.escape(who);
		String body = "OSParty signed <b>" + escaped + "</b> in on this device. The sign-in is OSParty's "
			+ "own — it never touches your OSRS login — and it's what stops anyone else posing as this "
			+ "character here. It also verifies account ownership for scam protection, like when a party "
			+ "is reported to RuneWatch.<br><br>"
			+ "If this computer is ever lost or wiped, you'll get back in one of these ways — set up at "
			+ "least one now.";
		JLabel label = new JLabel("<html><body style='width:290px'>" + body + "</body></html>");
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private JPanel discordRow()
	{
		JPanel row = PanelWidgets.cappedColumn();
		row.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));

		row.add(description(
			"Link Discord (recommended): nothing to write down, and it works from any machine."));

		discordState.setFont(FontManager.getRunescapeSmallFont());
		discordState.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		discordState.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel controls = new JPanel(new BorderLayout(6, 0));
		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
		controls.setAlignmentX(Component.LEFT_ALIGNMENT);
		controls.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		controls.add(discordState, BorderLayout.CENTER);
		controls.add(linkDiscord, BorderLayout.EAST);
		row.add(controls);

		return row;
	}

	private JPanel codesRow()
	{
		JPanel row = PanelWidgets.cappedColumn();
		row.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));

		row.add(description("Recovery codes: ten one-time codes that work even without Discord. "
			+ "Keep them somewhere safe — a password manager, or printed."));
		row.add(codesSlot);

		return row;
	}

	/**
	 * A route description as a fixed-width HTML label, the same shape as {@link #lead}. Not
	 * {@link PanelWidgets#wrappingText}: a wrapping text area reports one line's height until it has
	 * been laid out, so {@code pack()} under-measures the dialog and clips whatever sits lowest.
	 */
	private JLabel description(String text)
	{
		JLabel label = new JLabel("<html><body style='width:290px'>" + text + "</body></html>");
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	/** The codes area's initial state: a single button that fetches and reveals. */
	private void buildCodesOffer(CodesRequest codesRequest)
	{
		JButton show = new JButton("Show recovery codes");
		show.setFont(FontManager.getRunescapeSmallFont());
		show.addActionListener(e ->
		{
			show.setEnabled(false);
			status.setText("Fetching codes…");
			codesRequest.request(
				codes -> SwingUtilities.invokeLater(() -> revealCodes(codes)),
				error -> SwingUtilities.invokeLater(() ->
				{
					show.setEnabled(true);
					status.setText(error);
				}));
		});

		JPanel buttonRow = new JPanel(new BorderLayout());
		buttonRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttonRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		buttonRow.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		buttonRow.add(show, BorderLayout.EAST);
		codesSlot.add(buttonRow);
	}

	/** Swap the offer button for the sheet itself, with the save affordances and the gate. */
	private void revealCodes(List<String> codes)
	{
		status.setText(" ");
		codesShown = !codes.isEmpty();
		codesSlot.removeAll();

		JScrollPane area = RecoveryCodesView.codesArea(codes);
		area.setAlignmentX(Component.LEFT_ALIGNMENT);
		area.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		codesSlot.add(area);

		JButton copy = new JButton("Copy");
		copy.setFont(FontManager.getRunescapeSmallFont());
		copy.setEnabled(codesShown);
		copy.addActionListener(e -> RecoveryCodesView.copyToClipboard(codes, copy));

		JButton save = new JButton("Save to file…");
		save.setFont(FontManager.getRunescapeSmallFont());
		save.setEnabled(codesShown);
		save.addActionListener(e -> RecoveryCodesView.saveToFile(codes, this));

		savedCodes.setFont(FontManager.getRunescapeSmallFont());
		savedCodes.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		savedCodes.setBackground(ColorScheme.DARK_GRAY_COLOR);
		savedCodes.setEnabled(codesShown);

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		actions.setBackground(ColorScheme.DARK_GRAY_COLOR);
		actions.setAlignmentX(Component.LEFT_ALIGNMENT);
		actions.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		actions.add(copy);
		actions.add(save);
		actions.add(savedCodes);
		codesSlot.add(actions);

		codesSlot.revalidate();
		pack();
	}

	private JPanel buttonRow()
	{
		JPanel row = PanelWidgets.cappedRow(new FlowLayout(FlowLayout.RIGHT, 6, 6));

		row.add(done);

		JButton later = new JButton("Later");
		later.setFont(FontManager.getRunescapeSmallFont());
		later.setToolTipText("Closes for now; the panel keeps offering setup until it's done");
		later.addActionListener(e -> dispose());
		row.add(later);

		return row;
	}

	private void refreshDiscordState()
	{
		boolean linked = discordLinked.getAsBoolean();
		if (linked)
		{
			discordState.setText("Discord linked.");
			discordState.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
			linkDiscord.setEnabled(false);
			if ("Check your browser to authorise Discord…".equals(status.getText()))
			{
				status.setText(" ");
			}
		}
		refreshDone();
	}

	private void refreshDone()
	{
		done.setEnabled(discordLinked.getAsBoolean() || (codesShown && savedCodes.isSelected()));
	}

	private static Component spacer()
	{
		return Box.createVerticalStrut(8);
	}
}
