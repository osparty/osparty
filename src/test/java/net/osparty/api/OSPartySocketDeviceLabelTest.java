package net.osparty.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The fallback chain {@link OSPartySocket#deviceLabel(String, String, String)} picks between: a resolved
 * hostname, then Windows' {@code COMPUTERNAME}, then {@code HOSTNAME}, then nothing. Exercised on its own,
 * apart from the system calls that feed it in {@link OSPartySocket}, since a local hostname lookup depends on
 * network/DNS configuration and cannot be relied on to behave one way in a test.
 */
public class OSPartySocketDeviceLabelTest
{
	@Test
	public void prefersTheResolvedHostname()
	{
		assertEquals("desktop", OSPartySocket.deviceLabel("desktop", "COMPUTERNAME", "HOSTNAME"));
	}

	@Test
	public void fallsBackToComputerNameWhenHostnameIsUnavailable()
	{
		assertEquals("COMPUTERNAME", OSPartySocket.deviceLabel(null, "COMPUTERNAME", "HOSTNAME"));
		assertEquals("COMPUTERNAME", OSPartySocket.deviceLabel("  ", "COMPUTERNAME", "HOSTNAME"));
	}

	@Test
	public void fallsBackToHostnameEnvWhenNeitherOfTheOthersIsAvailable()
	{
		assertEquals("HOSTNAME", OSPartySocket.deviceLabel(null, null, "HOSTNAME"));
		assertEquals("HOSTNAME", OSPartySocket.deviceLabel(null, "", "HOSTNAME"));
	}

	@Test
	public void nullWhenNothingIsAvailable()
	{
		assertNull(OSPartySocket.deviceLabel(null, null, null));
		assertNull(OSPartySocket.deviceLabel("", " ", ""));
	}
}
