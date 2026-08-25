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
	/** Where dialogs are parented, and what gets told to show or hide the banner. */
	private Component parent;
	private Consumer<Boolean> onSignedInChanged;

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
	private Route attempted;
	private Timer discordPoll;
	private String discordTicket;
	/** Where "Link Discord too" goes. Supplied by the panel, which already owns the linking flow. */
	private Runnable onLinkDiscord;

	public AccountRecoveryController(OSPartySocket socket, LongSupplier accountHash,
		java.util.function.Supplier<String> playerName)
	{
		this.socket = socket;
		this.accountHash = accountHash;
		this.playerName = playerName;
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
		if (!net.osparty.store.PlayerFlag.isKnown(accountHash.getAsLong()))
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
			RecoveryCodesDialog.show(from, event.codes, false, null);
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
			// The account's first device, and the only moment its codes will ever be visible. Everything
			// else here is about getting back in; this is the one chance to make that possible.
			RecoveryCodesDialog.show(parent, codes, true, this::startDiscordLinkFromSetup);
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
	 * "Link Discord too" from the first-run codes dialog.
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
