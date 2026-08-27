package net.osparty.model;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Field names on both models are the wire, so a field the server sends and this side never declares is
 * dropped in silence. These cover the ones that used to be: the host's public id, which a transfer
 * moves without touching the member list, and the delta-only fields the merge had no home for.
 */
public class AdvertisementDeltaTest
{
	private final Gson gson = new Gson();

	@Test
	public void readsHostPlayerIdOffTheWireRatherThanTheRoster()
	{
		Advertisement ad = gson.fromJson(
			"{\"id\":\"p1\",\"host\":\"NewHost\",\"hostPlayerId\":\"NEW0-HOST-0000\","
				+ "\"members\":[{\"name\":\"OldHost\",\"playerId\":\"OLD0-HOST-0000\"}]}",
			Advertisement.class);

		assertEquals("NEW0-HOST-0000", ad.getHostPlayerId());
	}

	/** A server that predates the field sends no id, and member zero is the only guess available. */
	@Test
	public void fallsBackToMemberZeroWhenTheServerSendsNoId()
	{
		Advertisement ad = gson.fromJson(
			"{\"id\":\"p1\",\"host\":\"Host\",\"members\":[{\"name\":\"Host\",\"playerId\":\"HOST-0000-0000\"}]}",
			Advertisement.class);

		assertEquals("HOST-0000-0000", ad.getHostPlayerId());
	}

	@Test
	public void hasNoIdWhenThereIsNothingToReadItFrom()
	{
		Advertisement ad = new Advertisement();
		assertNull(ad.getHostPlayerId());
	}

	/**
	 * The transfer case end to end: the roster still opens with the outgoing host, so a merge that
	 * dropped the id would leave block and favourite matching pointed at the wrong player.
	 */
	@Test
	public void mergingATransferMovesTheIdOffTheOutgoingHost()
	{
		Advertisement ad = new Advertisement();
		ad.setId("p1");
		ad.setHost("OldHost");
		ad.setHostPlayerId("OLD0-HOST-0000");
		ad.setMembers(Collections.singletonList(new Member("OldHost", "OLD0-HOST-0000")));

		AdvertisementDelta delta = gson.fromJson(
			"{\"id\":\"p1\",\"host\":\"NewHost\",\"hostPlayerId\":\"NEW0-HOST-0000\"}", AdvertisementDelta.class);
		delta.applyTo(ad);

		assertEquals("NewHost", ad.getHost());
		assertEquals("NEW0-HOST-0000", ad.getHostPlayerId());
	}

	@Test
	public void mergesTheFieldsThatOnlyEverArriveAsADelta()
	{
		Advertisement ad = new Advertisement();
		ad.setId("p1");

		AdvertisementDelta delta = gson.fromJson(
			"{\"id\":\"p1\",\"node\":\"pod-b\",\"requiredRoles\":[\"melee\",\"melee\"],"
				+ "\"hostRole\":\"mage\",\"learner\":true,\"teacher\":true}",
			AdvertisementDelta.class);
		delta.applyTo(ad);

		assertEquals("pod-b", ad.getNode());
		assertEquals(Arrays.asList("melee", "melee"), ad.getRequiredRoles());
		assertEquals("mage", ad.getHostRole());
		assertTrue(ad.isLearner());
		assertTrue(ad.isTeacher());
	}

	@Test
	public void leavesUnsentFieldsAlone()
	{
		Advertisement ad = new Advertisement();
		ad.setId("p1");
		ad.setHostPlayerId("HOST-0000-0000");
		ad.setNode("pod-a");
		ad.setHostRole("mage");
		ad.setLearner(true);

		AdvertisementDelta delta = gson.fromJson("{\"id\":\"p1\",\"size\":4}", AdvertisementDelta.class);
		delta.applyTo(ad);

		assertEquals(4, ad.getSize());
		assertEquals("HOST-0000-0000", ad.getHostPlayerId());
		assertEquals("pod-a", ad.getNode());
		assertEquals("mage", ad.getHostRole());
		assertTrue(ad.isLearner());
		assertFalse(ad.isTeacher());
	}
}
