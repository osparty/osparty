package net.osparty.model;

import com.google.gson.Gson;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The host publishes {@link PartyMeta} over the live room and members apply it onto the copy of the ad they
 * took when they applied, so a round trip has to survive serialisation and land every advertised field —
 * anything dropped here silently freezes at whatever the member saw on the search board.
 */
public class PartyMetaTest
{
	private final Gson gson = new Gson();

	@Test
	public void roundTripsEveryAdvertisedField()
	{
		Party original = new Party();
		original.setId("p1");
		original.setActivity("cox");
		original.setHost("Host");
		original.setDescription("chill run");
		original.setCapacity(5);
		original.setWorld("330");
		original.setMinKillCount(50);
		original.setMinHardModeKillCount(10);
		original.setLootRule("FFA");
		original.setPrivateParty(true);
		original.setIronmanOnly(true);
		original.setInvocation(300);
		original.setHardMode(true);
		original.setCoxScale("3+4");
		original.setRequiredRoles(Arrays.asList("melee", "ranged"));
		original.setHostRole("melee");
		original.setLearner(true);
		original.setTeacher(true);

		PartyMeta sent = gson.fromJson(gson.toJson(PartyMeta.from(original)), PartyMeta.class);
		assertEquals(PartyMeta.from(original), sent);

		// The member's stale copy: same party, settings as they were when it applied.
		Party stale = new Party();
		stale.setId("p1");
		stale.setActivity("cox");
		stale.setHost("Host");
		stale.setCapacity(2);
		stale.setWorld("301");
		sent.applyTo(stale);

		assertEquals(PartyMeta.from(original), PartyMeta.from(stale));
		// Identity fields are the ad's own and are never carried by the meta frame.
		assertEquals("p1", stale.getId());
		assertEquals("cox", stale.getActivity());
	}

	@Test
	public void carriesTheHostSoATransferReachesBystanders()
	{
		Party party = new Party();
		party.setHost("OldHost");
		PartyMeta fromNewHost = new PartyMeta();
		fromNewHost.setHost("NewHost");

		fromNewHost.applyTo(party);

		assertEquals("NewHost", party.getHost());
		assertTrue(!PartyMeta.from(party).equals(new PartyMeta()));
	}
}
