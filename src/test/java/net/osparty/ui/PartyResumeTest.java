package net.osparty.ui;

import java.util.HashMap;
import java.util.Map;
import net.osparty.model.Advertisement;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

/**
 * The membership marker a member is put back into its party from ({@link PartyState#rememberMembership}).
 * Covers what it carries, and the three things that must stop it: another account, a party we were in too
 * long ago to still be coming straight back to, and any ordinary way out of one.
 */
public class PartyResumeTest
{
	private static final long ACCOUNT = 4242L;
	private static final long OTHER_ACCOUNT = 9999L;
	/** As written to the config store, so a stale marker can be aged without waiting for one. */
	private static final String KEY_SEEN_AT = "memberPartySeenAt";

	/** Everything the panel wrote, in the shape RuneLite would hold it. */
	private Map<String, String> stored;
	private PartyState partyState;
	private Advertisement ad;

	@Before
	public void setUp()
	{
		stored = new HashMap<>();
		partyState = new PartyState(configBackedBy(stored));

		ad = new Advertisement();
		ad.setId("p1");
		ad.setHost("Host");
		ad.setPassphrase("pp");
		ad.setInviteCode("ABCD");
	}

	/** A {@link ConfigManager} that keeps what it is given, across every overload of set/get/unset. */
	private static ConfigManager configBackedBy(Map<String, String> store)
	{
		return mock(ConfigManager.class, invocation ->
		{
			Object[] args = invocation.getArguments();
			switch (invocation.getMethod().getName())
			{
				case "setConfiguration":
					store.put((String) args[1], String.valueOf(args[2]));
					return null;
				case "unsetConfiguration":
					store.remove(args[1]);
					return null;
				case "getConfiguration":
					return store.get(args[1]);
				default:
					return null;
			}
		});
	}

	@Test
	public void remembersThePartyAndWhatWeWereInIt()
	{
		partyState.setMember(ad);
		partyState.rememberMembership(ad, "MELEE", true, ACCOUNT);

		PartyState.Membership saved = partyState.savedMembership(ACCOUNT);
		assertNotNull(saved);
		assertEquals("p1", saved.getPartyId());
		assertEquals("ABCD", saved.getInviteCode());
		assertEquals("MELEE", saved.getRole());
		assertEquals(true, saved.isLearner());
	}

	@Test
	public void doesNotResumeAnotherAccountsParty()
	{
		partyState.rememberMembership(ad, null, false, ACCOUNT);

		assertNull(partyState.savedMembership(OTHER_ACCOUNT));
		// An alt logging in must not consume it either: the account that left it is still coming back.
		assertNotNull(partyState.savedMembership(ACCOUNT));
	}

	@Test
	public void doesNotResumeAPartyWeLeftLongAgo()
	{
		partyState.rememberMembership(ad, null, false, ACCOUNT);
		stored.put(KEY_SEEN_AT, Long.toString(System.currentTimeMillis() - 120_000));

		assertNull(partyState.savedMembership(ACCOUNT));
		// And the marker is gone, so nothing keeps asking about a party that has long since moved on.
		assertFalse(stored.containsKey(KEY_SEEN_AT));
	}

	@Test
	public void leavingThePartyForgetsIt()
	{
		partyState.setMember(ad);
		partyState.rememberMembership(ad, null, false, ACCOUNT);

		partyState.clear();

		assertNull(partyState.savedMembership(ACCOUNT));
	}

	@Test
	public void hostingForgetsIt()
	{
		partyState.rememberMembership(ad, null, false, ACCOUNT);

		partyState.setHosting(ad, "host-key");

		assertNull(partyState.savedMembership(ACCOUNT));
	}

	@Test
	public void ignoresAPartyWithNothingToRejoinItBy()
	{
		ad.setInviteCode(null);

		partyState.rememberMembership(ad, null, false, ACCOUNT);

		assertNull(partyState.savedMembership(ACCOUNT));
	}

	@Test
	public void ignoresAMembershipWithNoAccountToOwnIt()
	{
		partyState.rememberMembership(ad, null, false, -1L);

		assertNull(partyState.savedMembership(-1L));
	}
}
