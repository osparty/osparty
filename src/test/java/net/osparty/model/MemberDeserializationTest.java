package net.osparty.model;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Confirms the client reads a party's members exactly as the API sends them -- by public id, never by
 * account hash -- and still tolerates the legacy bare-string form.
 */
public class MemberDeserializationTest
{
	private final Gson gson = new Gson();

	@Test
	public void readsHostPlayerIdFromTheAd()
	{
		String json = "{\"id\":\"p1\",\"host\":\"protodefend\",\"hostPlayerId\":\"HOST-0000-0001\","
			+ "\"members\":[{\"name\":\"protodefend\",\"playerId\":\"HOST-0000-0001\"}]}";
		Advertisement ad = gson.fromJson(json, Advertisement.class);

		assertEquals("protodefend", ad.getMembers().get(0).getName());
		assertEquals("HOST-0000-0001", ad.getMembers().get(0).getPlayerId());
		assertEquals("HOST-0000-0001", ad.getHostPlayerId());
	}

	/** A server that still sends the hash is read past: nothing here keeps another player's account hash. */
	@Test
	public void ignoresAnAccountHashAnOlderServerStillSends()
	{
		String json = "{\"id\":\"p1\",\"host\":\"protodefend\",\"hostAccountHash\":123456789012,"
			+ "\"members\":[{\"name\":\"protodefend\",\"accountHash\":123456789012,\"playerId\":\"HOST-0000-0001\"}]}";
		Advertisement ad = gson.fromJson(json, Advertisement.class);

		assertEquals("HOST-0000-0001", ad.getMembers().get(0).getPlayerId());
		assertEquals("HOST-0000-0001", ad.getHostPlayerId());
		assertFalse(gson.toJson(ad).contains("123456789012"));
	}

	@Test
	public void toleratesLegacyStringMembers()
	{
		String json = "{\"id\":\"p2\",\"host\":\"x\",\"members\":[\"x\"]}";
		Advertisement ad = gson.fromJson(json, Advertisement.class);

		assertEquals("x", ad.getMembers().get(0).getName());
		assertNull(ad.getHostPlayerId());
	}

	@Test
	public void readsServerAssertedBadges()
	{
		String json = "{\"id\":\"p3\",\"host\":\"x\","
			+ "\"members\":[{\"name\":\"x\",\"playerId\":\"X000-0000-0000\",\"badges\":[\"developer\",\"backer\"]},"
			+ "{\"name\":\"y\",\"playerId\":\"Y000-0000-0000\"}]}";
		Advertisement ad = gson.fromJson(json, Advertisement.class);

		assertEquals(java.util.List.of("developer", "backer"), ad.getMembers().get(0).getBadges());
		assertNull(ad.getMembers().get(1).getBadges());
	}

	@Test
	public void badgesSurviveRoundTripAndAreOmittedWhenAbsent()
	{
		String withBadges = gson.toJson(new Member("x", java.util.List.of("developer"), "X000-0000-0000"));
		assertEquals(java.util.List.of("developer"), gson.fromJson(withBadges, Member.class).getBadges());

		String without = gson.toJson(new Member("x", "X000-0000-0000"));
		assertFalse(without.contains("badges"));
	}

	/** What the host reports about its party: name and id, and the id only when there is one. */
	@Test
	public void writesMembersByIdAndNeverByHash()
	{
		String withId = gson.toJson(new Member("x", "X000-0000-0000"));
		assertTrue(withId.contains("\"playerId\":\"X000-0000-0000\""));
		assertFalse(withId.contains("accountHash"));

		String withoutId = gson.toJson(new Member("x"));
		assertEquals("{\"name\":\"x\"}", withoutId);
	}

	@Test
	public void readsPlayerIdFromMembers()
	{
		String json = "{\"id\":\"p4\",\"host\":\"x\","
			+ "\"members\":[{\"name\":\"x\",\"playerId\":\"abc123\"},"
			+ "{\"name\":\"y\"}]}";
		Advertisement ad = gson.fromJson(json, Advertisement.class);

		assertEquals("abc123", ad.getMembers().get(0).getPlayerId());
		assertNull(ad.getMembers().get(1).getPlayerId());
	}
}
