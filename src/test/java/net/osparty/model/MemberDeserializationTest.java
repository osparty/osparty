package net.osparty.model;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/**
 * Confirms the client deserialises the host's accountHash out of a party's members list
 * exactly as the API sends it, and still tolerates the legacy bare-string form.
 */
public class MemberDeserializationTest
{
	private final Gson gson = new Gson();

	@Test
	public void readsHostAccountHashFromMembers()
	{
		String json = "{\"id\":\"p1\",\"host\":\"protodefend\","
			+ "\"members\":[{\"name\":\"protodefend\",\"accountHash\":123456789012}]}";
		Advertisement ad = gson.fromJson(json, Advertisement.class);

		assertEquals("protodefend", ad.getMembers().get(0).getName());
		assertEquals(123456789012L, ad.getMembers().get(0).getAccountHash());
		assertEquals(123456789012L, ad.getHostAccountHash());
	}

	@Test
	public void toleratesLegacyStringMembers()
	{
		String json = "{\"id\":\"p2\",\"host\":\"x\",\"members\":[\"x\"]}";
		Advertisement ad = gson.fromJson(json, Advertisement.class);

		assertEquals("x", ad.getMembers().get(0).getName());
		assertEquals(0L, ad.getHostAccountHash());
	}

	@Test
	public void readsServerAssertedBadges()
	{
		String json = "{\"id\":\"p3\",\"host\":\"x\","
			+ "\"members\":[{\"name\":\"x\",\"accountHash\":1,\"badges\":[\"developer\",\"backer\"]},"
			+ "{\"name\":\"y\",\"accountHash\":2}]}";
		Advertisement ad = gson.fromJson(json, Advertisement.class);

		assertEquals(java.util.List.of("developer", "backer"), ad.getMembers().get(0).getBadges());
		assertNull(ad.getMembers().get(1).getBadges());
	}

	@Test
	public void badgesSurviveRoundTripAndAreOmittedWhenAbsent()
	{
		String withBadges = gson.toJson(new Member("x", 1L, java.util.List.of("developer")));
		assertEquals(java.util.List.of("developer"), gson.fromJson(withBadges, Member.class).getBadges());

		String without = gson.toJson(new Member("x", 1L));
		assertFalse(without.contains("badges"));
	}

	@Test
	public void readsPlayerIdFromMembers()
	{
		String json = "{\"id\":\"p4\",\"host\":\"x\","
			+ "\"members\":[{\"name\":\"x\",\"accountHash\":1,\"playerId\":\"abc123\"},"
			+ "{\"name\":\"y\",\"accountHash\":2}]}";
		Advertisement ad = gson.fromJson(json, Advertisement.class);

		assertEquals("abc123", ad.getMembers().get(0).getPlayerId());
		assertNull(ad.getMembers().get(1).getPlayerId());
	}
}
