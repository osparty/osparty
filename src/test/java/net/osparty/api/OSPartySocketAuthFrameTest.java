package net.osparty.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.osparty.store.CredentialStore;
import okhttp3.OkHttpClient;
import org.junit.Before;
import org.junit.Test;

/**
 * The sign-in frames as the service actually spells them, fed through the socket that has to read them.
 *
 * <p><b>Why this exists.</b> Both halves of the credential handshake were tested and both passed: the
 * service's tests asserted the JSON it emits, the plugin compiled against its own idea of that JSON, and
 * nothing checked the two agreed. They did not. Every board frame deserialises into one flat class here, and
 * {@code authIssued} carried its code list under the same name {@code authFailed} uses for a boolean, so Gson
 * threw binding an array to a Boolean and the whole frame was dropped by a {@code catch} that returned in
 * silence. The visible result was a first sign-in that stored no credential and showed nothing at all, while
 * the service had already written the row -- nothing failed anywhere, it simply did not happen.
 *
 * <p>So these cases send the literal wire text rather than anything built from the plugin's own types. A
 * shape the plugin cannot read has to fail here, not in front of a user.
 */
public class OSPartySocketAuthFrameTest
{
	private static final long ACCOUNT = 4242L;

	private OSPartySocket socket;
	private CredentialStore credentials;

	@Before
	public void setUp() throws Exception
	{
		File dir = Files.createTempDirectory("osparty-auth-frame").toFile();
		dir.deleteOnExit();
		Gson gson = new Gson();
		credentials = new CredentialStore(dir, gson);
		socket = new OSPartySocket(new OkHttpClient(), gson, credentials);
		socket.setAccountHash(ACCOUNT);
	}

	/** The frame that was being dropped: a first sign-in, carrying the account's one-time codes. */
	@Test
	public void anIssuedCredentialIsStoredAndItsCodesSurfaced()
	{
		AtomicReference<OSPartySocket.SignedInEvent> seen = new AtomicReference<>();
		socket.setOnSignedIn(seen::set);

		socket.onMessage(null, "{\"type\":\"authIssued\",\"token\":\"tok-abc\",\"playerId\":\"pid\","
			+ "\"firstDevice\":true,\"codes\":[\"AAAA-BBBB-CCCC-DDDD\",\"EEEE-FFFF-GGGG-HHHH\"]}");

		assertEquals("tok-abc", credentials.get(ACCOUNT));
		assertNotNull(seen.get());
		assertEquals(2, seen.get().recoveryCodes.size());
		assertEquals("AAAA-BBBB-CCCC-DDDD", seen.get().recoveryCodes.get(0));
	}

	/** Every later enrolment carries no codes, and that must read as "none" rather than breaking the frame. */
	@Test
	public void aLaterCredentialArrivesWithoutCodes()
	{
		AtomicReference<OSPartySocket.SignedInEvent> seen = new AtomicReference<>();
		socket.setOnSignedIn(seen::set);

		socket.onMessage(null, "{\"type\":\"authIssued\",\"token\":\"tok-xyz\",\"playerId\":\"pid\","
			+ "\"firstDevice\":false}");

		assertEquals("tok-xyz", credentials.get(ACCOUNT));
		assertNotNull(seen.get());
		assertTrue(seen.get().recoveryCodes.isEmpty());
	}

	/**
	 * The other half of the collision. {@code recoveryCodes} is a boolean here and a list on
	 * {@code authIssued}; one flat class cannot hold both under one name, and this pins that they no longer
	 * try to.
	 */
	@Test
	public void aFailedSignInReportsItsRoutesAsFlags()
	{
		AtomicReference<OSPartySocket.AuthFailedEvent> seen = new AtomicReference<>();
		socket.setOnAuthFailed(seen::set);

		socket.onMessage(null, "{\"type\":\"authFailed\",\"accountHash\":" + ACCOUNT
			+ ",\"reason\":\"already-enrolled\",\"coupling\":true,\"recoveryCodes\":true,\"discord\":false}");

		assertNotNull(seen.get());
		assertTrue(seen.get().coupling);
		assertTrue(seen.get().recoveryCodes);
		assertTrue(seen.get().hasAnyRoute());
	}

	/**
	 * Both shapes on one connection, in the order a second machine meets them: refused, then issued once the
	 * user has proved themselves. This is what the collision made impossible -- reading one poisoned nothing,
	 * but the two could not coexist.
	 */
	@Test
	public void bothShapesReadOnTheSameConnection()
	{
		List<String> types = new ArrayList<>();
		socket.setOnAuthFailed(event -> types.add("failed"));
		socket.setOnSignedIn(event -> types.add("issued"));

		socket.onMessage(null, "{\"type\":\"authFailed\",\"accountHash\":" + ACCOUNT
			+ ",\"reason\":\"already-enrolled\",\"coupling\":true,\"recoveryCodes\":false,\"discord\":false}");
		socket.onMessage(null, "{\"type\":\"authIssued\",\"token\":\"tok-1\",\"playerId\":\"pid\","
			+ "\"firstDevice\":true,\"codes\":[\"AAAA-BBBB-CCCC-DDDD\"]}");

		assertEquals(List.of("failed", "issued"), types);
	}

	/** The ack for a requested code, whose count decides whether the client arms its input at all. */
	@Test
	public void aSentCodeReportsHowManyDevicesSawIt()
	{
		AtomicReference<Integer> seen = new AtomicReference<>();
		socket.setOnCouplingCodeSent(seen::set);

		socket.onMessage(null, "{\"type\":\"couplingCodeSent\",\"accountHash\":" + ACCOUNT + ",\"reached\":2}");

		assertEquals(Integer.valueOf(2), seen.get());
	}

	/** A poll that found nothing yet is the ordinary case, and must not read as a refusal. */
	@Test
	public void aPendingRecoveryPollIsNotAFailure()
	{
		AtomicReference<OSPartySocket.RecoveryResultEvent> seen = new AtomicReference<>();
		socket.setOnRecoveryResult(seen::set);

		socket.onMessage(null, "{\"type\":\"recoveryResult\",\"success\":false,\"pending\":true}");

		assertNotNull(seen.get());
		assertTrue(seen.get().pending);
	}

	/** A status reply carries the count and no codes; an issue carries both. */
	@Test
	public void recoveryCodeRepliesReadBothWays()
	{
		AtomicReference<OSPartySocket.RecoveryCodesEvent> seen = new AtomicReference<>();
		socket.setOnRecoveryCodes(seen::set);

		socket.onMessage(null, "{\"type\":\"recoveryCodes\",\"remaining\":7}");
		assertTrue(seen.get().codes.isEmpty());
		assertEquals(7, seen.get().remaining);

		socket.onMessage(null, "{\"type\":\"recoveryCodes\",\"codes\":[\"AAAA-BBBB-CCCC-DDDD\"],"
			+ "\"remaining\":1}");
		assertEquals(1, seen.get().codes.size());
	}
}
