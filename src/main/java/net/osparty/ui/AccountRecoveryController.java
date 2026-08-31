package net.osparty.ui;

import java.awt.Component;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import lombok.extern.slf4j.Slf4j;
import net.osparty.api.OSPartySocket;
import net.runelite.client.util.LinkBrowser;

/**
 * Everything the player sees about signing in to OSParty, and the one place that talks to the socket about
 * it.
 *
 * <p><b>Why a controller rather than dialogs that call the socket.</b> The socket's auth callbacks are
 * single-slot setters, so any dialog that registered its own would silently replace whoever registered
 * first — and a recovery flow can outlive the dialog that started it, because a Discord round trip goes
 * through a browser. So the callbacks are owned here for the life of the plugin and routed to whatever
 * happens to be open.
 *
 * <p><b>Why it does not simply prompt.</b> The version this replaces put a modal "type the six-digit code"
 * prompt on screen every time an unsigned-in client connected — which is every world hop and every network
 * blip — and said the same thing whether a code was waiting on another machine or no other machine had
 * existed for months. A user who had merely lost their credential was being asked, repeatedly, for
 * something that did not exist. So: the failure is stated once per character per session, the panel keeps a
 * quiet banner for as long as it is true, and the routes offered are only the ones the server has confirmed
 * will answer.
 */
@Slf4j
public class AccountRecoveryController
{
	/** How often to ask whether the browser half of a Discord recovery has finished. */
	private static final int DISCORD_POLL_MS = 2000;
	/** Give up after this long so a tab the user closed does not leave a timer running for the session. */
	private static final int DISCORD_POLL_TIMEOUT_MS = 5 * 60 * 1000;

	private final OSPartySocket socket;
	private final LongSupplier accountHash;
	private final java.util.function.Supplier<String> playerName;
	private final net.osparty.store.RecoverySetupStore setupStore;
	/** Where dialogs are parented, and what gets told to show or hide the banner. */
	private Component parent;
	private Consumer<Boolean> onSignedInChanged;
	/** Tells the panel to show or retire the "finish account setup" banner. */
	private Consumer<Boolean> onSetupPendingChanged;
	/** One nudge per first enrolment — the plugin's chat line and sidebar badge. */
	private Runnable onSetupPrompt;
	/** Live Discord-link state for the current account, owned by the panel's footer. */
	private java.util.function.BooleanSupplier discordLinked;

	/**
	 * The codes each enrolment arrived with, held for the session so setup can show the original sheet.
	 *
	 * <p>Never written to disk: the point of a recovery code is to live somewhere other than this
	 * machine, and a plaintext copy beside the credential would protect nothing. If the session ends
	 * before the user looks, {@link #requestSetupCodes} mints a replacement sheet instead — the server
	 * retires the unseen one in the same stroke. EDT only.
	 */
	private final java.util.Map<Long, List<String>> freshCodes = new java.util.HashMap<>();

	/**
	 * Characters already explained to the user this run.
	 *
	 * <p>The banner stays up for as long as the problem does, but the dialog is an interruption and gets to
	 * happen once. Keyed by character because a main and an ironman are two separate sign-ins and the second
	 * one failing is genuinely news.
	 */
	private final java.util.Set<Long> explained = new java.util.HashSet<>();

	/** Which way back in the user last tried, so success can say what it cost them. */
	private enum Route
	{
		COUPLING_CODE,
		RECOVERY_CODE,
		DISCORD
	}

	private AccountRecoveryDialog dialog;
	private AccountSetupDialog setupDialog;
	private Route attempted;
	private Timer discordPoll;
	private String discordTicket;
	/** Where "Link Discord" goes. Supplied by the panel, which already owns the linking flow. */
	private Runnable onLinkDiscord;

	public AccountRecoveryController(OSPartySocket socket, LongSupplier accountHash,
		java.util.function.Supplier<String> playerName, net.osparty.store.RecoverySetupStore setupStore)
	{
		this.socket = socket;
		this.accountHash = accountHash;
		this.playerName = playerName;
		this.setupStore = setupStore;
	}

	/**
	 * Attach to the socket. Every callback below arrives on the socket reader thread, so each hops to the
	 * EDT before touching Swing.
	 */
	public void register()
	{
		socket.setOnSignedIn(event -> SwingUtilities.invokeLater(() -> onSignedIn(event)));
		socket.setOnAuthFailed(event -> SwingUtilities.invokeLater(() -> onAuthFailed(event)));
		socket.setOnCouplingCode(event -> SwingUtilities.invokeLater(() -> showIncomingCode(event.code)));
		socket.setOnCouplingCodeSent(reached -> SwingUtilities.invokeLater(() -> onCouplingCodeSent(reached)));
		socket.setOnCouplingResult(event -> SwingUtilities.invokeLater(
			() -> onCouplingResult(event.success)));
		socket.setOnRecoveryResult(event -> SwingUtilities.invokeLater(() -> onRecoveryResult(event)));
		socket.setOnDiscordRecoveryUrl((url, ticket) -> SwingUtilities.invokeLater(
			() -> onDiscordRecoveryUrl(url, ticket)));
		// A notice, not a loss: another device joining the account takes nothing from this one. Shown
		// because the screen the code was read off is the one place somebody who did not expect it would
		// notice.
		socket.setOnCouplingAccepted(account -> SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
			parent, "Another device just signed in to this account.", "OSParty",
			JOptionPane.INFORMATION_MESSAGE)));
	}

	/** Detach, so a plugin restart does not stack callbacks onto a dead instance. Every setter takes null. */
	public void unregister()
	{
		socket.setOnSignedIn(null);
		socket.setOnAuthFailed(null);
		socket.setOnCouplingCode(null);
		socket.setOnCouplingCodeSent(null);
		socket.setOnCouplingResult(null);
		socket.setOnRecoveryResult(null);
		socket.setOnDiscordRecoveryUrl(null);
		socket.setOnCouplingAccepted(null);
		stopDiscordPoll();
		closeSetupDialog();
	}

	/** The panel, once it exists: dialogs parent to it and the banner lives on it. */
	public void attachPanel(Component panel, Consumer<Boolean> signedInChanged)
	{
		this.parent = panel;
		this.onSignedInChanged = signedInChanged;
	}

	/**
	 * Where "Link Discord too" goes.
	 *
	 * <p>Supplied by the panel rather than reimplemented here: a link made from a signed-in device is
	 * precisely what recovery later needs, so pointing at the ordinary linking flow is both correct and one
	 * mechanism instead of two.
	 */
	public void setOnLinkDiscord(Runnable onLinkDiscord)
	{
		this.onLinkDiscord = onLinkDiscord;
	}

	/** Live Discord-link state, from the panel's footer — the setup dialog reads it to arm "Done". */
	public void setDiscordLinked(java.util.function.BooleanSupplier discordLinked)
	{
		this.discordLinked = discordLinked;
	}

	/** Tells the panel to show or retire the "finish account setup" banner. */
	public void setOnSetupPendingChanged(Consumer<Boolean> onSetupPendingChanged)
	{
		this.onSetupPendingChanged = onSetupPendingChanged;
	}

	/** One nudge per first enrolment — the plugin hangs its chat line and sidebar badge here. */
	public void setOnSetupPrompt(Runnable onSetupPrompt)
	{
		this.onSetupPrompt = onSetupPrompt;
	}

	/**
	 * Whether the character currently logged in still owes a recovery setup.
	 *
	 * <p>Polled by the panel alongside its other footer state, which is what keeps the banner honest
	 * across the cases no event covers: a restart with setup still pending, and switching characters.
	 */
	public boolean isSetupPendingNow()
	{
		long account = accountHash.getAsLong();
		return net.osparty.store.AccountHash.isKnown(account) && setupStore.isPending(account)
			&& socket.isSignedIn();
	}

	/** Open recovery setup for the current character — what the panel's setup banner does when clicked. */
	public void openSetup()
	{
		long account = accountHash.getAsLong();
		if (!net.osparty.store.AccountHash.isKnown(account))
		{
			JOptionPane.showMessageDialog(parent, "Log in to your OSRS account first.", "OSParty",
				JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		if (setupDialog != null && setupDialog.isShowing())
		{
			setupDialog.toFront();
			return;
		}
		setupDialog = AccountSetupDialog.show(parent, playerName.get(),
			() -> discordLinked != null && discordLinked.getAsBoolean(),
			this::startDiscordLinkFromSetup,
			(onCodes, onError) -> requestSetupCodes(account, onCodes, onError),
			codesSaved -> completeSetup(account, codesSaved));
	}

	/**
	 * This enrolment's codes, for the setup dialog: the sheet the server sent, when this session still
	 * holds it, and a freshly minted replacement otherwise. Minting retires the unseen originals, which
	 * costs nothing — nobody ever saved them.
	 */
	private void requestSetupCodes(long account, Consumer<List<String>> onCodes, Consumer<String> onError)
	{
		List<String> held = freshCodes.get(account);
		if (held != null)
		{
			onCodes.accept(held);
			return;
		}
		if (!socket.isSignedIn())
		{
			onError.accept("Not connected to OSParty right now. Try again in a moment.");
			return;
		}
		awaitRecoveryCodes(event ->
		{
			if (event.codes == null || event.codes.isEmpty())
			{
				onError.accept("Couldn't fetch codes right now. Try again in a moment.");
				return;
			}
			// Held for the session so closing and reopening setup shows this sheet again instead of
			// minting another.
			freshCodes.put(account, event.codes);
			onCodes.accept(event.codes);
		});
		socket.issueRecoveryCodes();
	}

	/** The user saved codes or linked Discord — retire the banner and stop holding their sheet. */
	private void completeSetup(long account, boolean codesSaved)
	{
		setupStore.clearPending(account);
		if (codesSaved)
		{
			setupStore.markCodesSaved(account);
		}
		freshCodes.remove(account);
		setupDialog = null;
		notifySetupPendingChanged();
	}

	/**
	 * The current account just unlinked its Discord.
	 *
	 * <p>If setup was completed on the strength of that link alone, the account is back to having no
	 * saved way in — the codes minted at enrolment still exist server-side, but a sheet nobody ever
	 * looked at recovers nothing. Reopening setup is what keeps "Done" honest after the fact. A user
	 * whose sheet is saved is left alone: their unlink costs them a convenience, not their way back.
	 */
	public void onDiscordUnlinked()
	{
		long account = accountHash.getAsLong();
		if (!net.osparty.store.AccountHash.isKnown(account) || !socket.isSignedIn()
			|| setupStore.hasSavedCodes(account))
		{
			return;
		}
		setupStore.markPending(account);
		notifySetupPendingChanged();
	}

	/** Open the sign-in help on demand — what the panel's banner does when clicked. */
	public void openRecovery()
	{
		if (socket.isSignedIn())
		{
			JOptionPane.showMessageDialog(parent, "This device is already signed in.", "OSParty",
				JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		// The banner outlives both of these -- someone can log out, or drop offline, while it is still up --
		// and in either case retryAuth would go nowhere and the button would look broken.
		if (!net.osparty.store.AccountHash.isKnown(accountHash.getAsLong()))
		{
			JOptionPane.showMessageDialog(parent, "Log in to your OSRS account first.", "OSParty",
				JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		if (!socket.isConnected())
		{
			JOptionPane.showMessageDialog(parent, "Not connected to OSParty right now. Try again in a moment.",
				"OSParty", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		// Nothing is known about which routes are open until the server says so, and it only says so in
		// response to a request. Asking is also the most likely thing to just fix it, since the usual reason
		// someone clicks this is that they have now started OSParty on their other machine.
		socket.retryAuth();
	}

	/**
	 * Mint a fresh set of codes for a signed-in device, show them, and report the new count.
	 *
	 * <p>{@code onDone} fires whether or not the user went through with it, so a caller showing the count
	 * does not have to guess.
	 */
	public void regenerateRecoveryCodes(Component from, Runnable onDone)
	{
		if (!socket.isSignedIn())
		{
			JOptionPane.showMessageDialog(from, "Sign this device in first.", "OSParty",
				JOptionPane.WARNING_MESSAGE);
			onDone.run();
			return;
		}
		if (!ConfirmDialog.ask(from, "New recovery codes",
			"Generate a new set? Any codes you saved before will stop working."))
		{
			onDone.run();
			return;
		}
		awaitRecoveryCodes(event ->
		{
			RecoveryCodesDialog.show(from, event.codes);
			onDone.run();
		});
		socket.issueRecoveryCodes();
	}

	/** How many codes are left, for the device manager's footer. */
	public void requestRecoveryCount(Consumer<Integer> onCount)
	{
		awaitRecoveryCodes(event -> onCount.accept(event.remaining));
		socket.requestRecoveryStatus();
	}

	/**
	 * Take the next {@code recoveryCodes} frame and then let go.
	 *
	 * <p>The socket's callback is a single slot, so this is the only place that writes it — two callers each
	 * installing their own would mean whichever asked second silently answered for both.
	 */
	private void awaitRecoveryCodes(Consumer<OSPartySocket.RecoveryCodesEvent> once)
	{
		socket.setOnRecoveryCodes(event -> SwingUtilities.invokeLater(() ->
		{
			socket.setOnRecoveryCodes(null);
			once.accept(event);
		}));
	}

	private void onSignedIn(OSPartySocket.SignedInEvent event)
	{
		// Whether this was the end of a sign-in that had visibly failed, as opposed to a first enrolment
		// nobody had to do anything about. Read before the dialog goes, since closing it is what erases the
		// evidence.
		boolean afterFailure = explained.remove(event.accountHash) || dialog != null;
		Route route = attempted;
		attempted = null;
		closeDialog();
		stopDiscordPoll();
		signedInChanged(true);
		List<String> codes = event.recoveryCodes;
		if (codes != null && !codes.isEmpty())
		{
			// The account's first device. These codes used to go straight onto the screen in an
			// application-modal dialog, on the first game tick after login — the game frozen, the codes
			// one reflex-click from gone for good. Now the sheet is held quietly, the panel offers setup
			// for as long as it takes, and the player looks when they choose to.
			freshCodes.put(event.accountHash, codes);
			setupStore.markPending(event.accountHash);
			notifySetupPendingChanged();
			if (onSetupPrompt != null)
			{
				onSetupPrompt.run();
			}
			return;
		}
		if (afterFailure)
		{
			// Success used to say nothing at all: the credential arrived, the dialog closed itself, and the
			// only trace was a banner disappearing behind whatever the user was looking at. Somebody who has
			// just typed a code needs to be told it worked, and told what it cost them.
			JOptionPane.showMessageDialog(parent,
				"This device is now signed in. Your other devices are unaffected."
					+ (route == Route.RECOVERY_CODE ? "\n\nThat recovery code has now been used up." : ""),
				"OSParty", JOptionPane.INFORMATION_MESSAGE);
		}
	}

	private void onAuthFailed(OSPartySocket.AuthFailedEvent event)
	{
		signedInChanged(false);
		if (!explained.add(event.accountHash))
		{
			// Said once already this run. The banner is still up; leave the user alone.
			return;
		}
		showDialog(event);
	}

	private void showDialog(OSPartySocket.AuthFailedEvent event)
	{
		closeDialog();
		dialog = AccountRecoveryDialog.show(parent, playerName.get(),
			event.coupling, event.recoveryCodes, event.discord,
			socket::requestCouplingCode,
			code -> submitCouplingCode(code),
			this::submitRecoveryCode,
			this::startDiscordRecovery,
			socket::retryAuth);
	}

	private void submitCouplingCode(String code)
	{
		attempted = Route.COUPLING_CODE;
		socket.couplingConfirm(accountHash.getAsLong(), code);
	}

	private void startDiscordRecovery()
	{
		attempted = Route.DISCORD;
		socket.startDiscordRecovery();
	}

	/**
	 * Show the code to the person who already holds this account, so they can read it onto the machine
	 * asking for it. Never sent to the machine asking — being able to see this screen is the whole test.
	 */
	private void showIncomingCode(String code)
	{
		JOptionPane.showMessageDialog(parent,
			"Another device is signing in to this account.\n\n"
				+ "Code: " + code + "\n\n"
				+ "Type it there to allow it. If this wasn't you, ignore it: nothing happens without "
				+ "the code, and this device keeps working either way.",
			"OSParty device code", JOptionPane.INFORMATION_MESSAGE);
	}

	private void submitRecoveryCode(String code)
	{
		attempted = Route.RECOVERY_CODE;
		socket.redeemRecoveryCode(code);
	}

	private void onCouplingCodeSent(int reached)
	{
		if (dialog != null)
		{
			dialog.couplingCodeSent(reached);
		}
	}

	private void onCouplingResult(boolean success)
	{
		if (success)
		{
			// The authIssued frame rides alongside, and onSignedIn both closes the dialog and confirms it.
			return;
		}
		attempted = null;
		setStatus("That code didn't work. Check it and try again.");
	}

	private void onRecoveryResult(OSPartySocket.RecoveryResultEvent event)
	{
		if (event.pending)
		{
			// Still waiting on the browser. Not a failure, and not worth saying anything about.
			return;
		}
		if (event.success)
		{
			// onSignedIn does the confirming; this frame only says the attempt resolved.
			stopDiscordPoll();
			return;
		}
		stopDiscordPoll();
		attempted = null;
		setStatus(event.detail == null ? "That didn't work." : capitalise(event.detail));
	}

	private void onDiscordRecoveryUrl(String url, String ticket)
	{
		discordTicket = ticket;
		LinkBrowser.browse(url);
		setStatus("Waiting for Discord…");
		startDiscordPoll();
	}

	/**
	 * Ask, repeatedly, whether the browser half has finished.
	 *
	 * <p>Polled rather than pushed because the two halves of a Discord recovery reach different servers: the
	 * browser lands wherever the ingress sends it, and that server has no route back to this socket. The
	 * ticket is what ties them together and it never leaves this process.
	 */
	private void startDiscordPoll()
	{
		stopDiscordPoll();
		final int[] elapsed = {0};
		discordPoll = new Timer(DISCORD_POLL_MS, e ->
		{
			elapsed[0] += DISCORD_POLL_MS;
			if (elapsed[0] >= DISCORD_POLL_TIMEOUT_MS)
			{
				stopDiscordPoll();
				setStatus("Gave up waiting for Discord. Try again when you're ready.");
				return;
			}
			socket.pollDiscordRecovery(discordTicket);
		});
		discordPoll.start();
	}

	private void stopDiscordPoll()
	{
		if (discordPoll != null)
		{
			discordPoll.stop();
			discordPoll = null;
		}
		discordTicket = null;
	}

	/**
	 * "Link Discord" from the account-setup dialog.
	 *
	 * <p>Deliberately the ordinary linking flow rather than anything special: a link made now, from a device
	 * that is signed in, is exactly what recovery later needs, and there is no second mechanism to maintain.
	 */
	private void startDiscordLinkFromSetup()
	{
		if (onLinkDiscord != null)
		{
			onLinkDiscord.run();
		}
	}

	private void setStatus(String message)
	{
		if (dialog != null)
		{
			dialog.setStatus(message);
		}
	}

	private void closeDialog()
	{
		if (dialog != null)
		{
			dialog.dispose();
			dialog = null;
		}
	}

	private void closeSetupDialog()
	{
		if (setupDialog != null)
		{
			setupDialog.dispose();
			setupDialog = null;
		}
	}

	private void notifySetupPendingChanged()
	{
		if (onSetupPendingChanged != null)
		{
			onSetupPendingChanged.accept(isSetupPendingNow());
		}
	}

	private void signedInChanged(boolean signedIn)
	{
		if (onSignedInChanged != null)
		{
			onSignedInChanged.accept(signedIn);
		}
	}

	private static String capitalise(String text)
	{
		return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}
}
