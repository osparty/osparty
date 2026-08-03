package net.osparty.party;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * The short wire names the server actually sends. A field this side spells out in full is one Gson silently
 * leaves at its default: an update whose member id reads as 0 belongs to nobody on the roster, so the peer it
 * describes stays data-less forever — which is what makes an applicant invisible to the host.
 */
public class LivePartyFrameWireTest
{
	private final Gson gson = new Gson();

	@Test
	public void aggregatedUpdatesCarryTheMemberTheyDescribe()
	{
		String json = "{\"t\":\"mu\",\"u\":[{\"m\":7,\"s\":{\"hp\":31}},{\"m\":9,\"s\":{\"hp\":12}}]}";

		LivePartyChannel.Frame frame = gson.fromJson(json, LivePartyChannel.Frame.class);

		assertEquals("mu", frame.type);
		assertNotNull(frame.updates);
		assertEquals(2, frame.updates.size());
		assertEquals(7, frame.updates.get(0).memberId);
		assertEquals(31, frame.updates.get(0).state.get("hp").getAsInt());
		assertEquals(9, frame.updates.get(1).memberId);
	}

	@Test
	public void rosterAndWelcomeCarryTheirIdsToo()
	{
		LivePartyChannel.Frame welcome =
			gson.fromJson("{\"t\":\"welcome\",\"m\":4,\"status\":\"PENDING\"}", LivePartyChannel.Frame.class);
		assertEquals(Long.valueOf(4), welcome.memberId);

		LivePartyChannel.Frame roster = gson.fromJson(
			"{\"t\":\"roster\",\"host\":\"Zezima\",\"members\":[{\"m\":4,\"name\":\"Alt\",\"status\":\"PENDING\"}]}",
			LivePartyChannel.Frame.class);
		assertEquals(4, roster.members.get(0).memberId);
		assertEquals("PENDING", roster.members.get(0).status);
	}
}
