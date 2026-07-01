package net.osparty.model;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

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
		Party party = gson.fromJson(json, Party.class);

		assertEquals("protodefend", party.getMembers().get(0).getName());
		assertEquals(123456789012L, party.getMembers().get(0).getAccountHash());
		assertEquals(123456789012L, party.getHostAccountHash());
	}

	@Test
	public void toleratesLegacyStringMembers()
	{
		String json = "{\"id\":\"p2\",\"host\":\"x\",\"members\":[\"x\"]}";
		Party party = gson.fromJson(json, Party.class);

		assertEquals("x", party.getMembers().get(0).getName());
		assertEquals(0L, party.getHostAccountHash());
	}
}
