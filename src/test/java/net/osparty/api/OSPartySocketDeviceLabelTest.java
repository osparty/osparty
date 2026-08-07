package net.osparty.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The fallback chain {@link OSPartySocket#deviceLabel(String, String)} picks between: an explicit override,
 * then a resolved hostname, then nothing. Exercised on its own, apart from the system calls that feed it in
 * {@link OSPartySocket}, since a local hostname lookup depends on network/DNS configuration and cannot be
 * relied on to behave one way in a test.
 */
public class OSPartySocketDeviceLabelTest
{
	@Test
	public void prefersTheOverride()
	{
		assertEquals("raid-pc", OSPartySocket.deviceLabel("raid-pc", "desktop"));
		assertEquals("raid-pc", OSPartySocket.deviceLabel("  raid-pc  ", "desktop"));
	}

	@Test
	public void fallsBackToTheResolvedHostname()
	{
		assertEquals("desktop", OSPartySocket.deviceLabel(null, "desktop"));
		assertEquals("desktop", OSPartySocket.deviceLabel("  ", "desktop"));
	}

	@Test
	public void nullWhenNothingIsAvailable()
	{
		assertNull(OSPartySocket.deviceLabel(null, null));
		assertNull(OSPartySocket.deviceLabel("", " "));
	}
}
