package net.osparty.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.osparty.model.Activity;
import net.osparty.model.Advertisement;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A hosted ad follows the board's settled value, once, and only where the two disagree. */
public class RaidBoardSyncTest
{
	private final Map<Integer, Integer> varbits = new HashMap<>();
	private final Map<Integer, Integer> varps = new HashMap<>();
	private final List<String> pushed = new ArrayList<>();
	private Advertisement ad;
	private RaidBoardSync sync;

	@Before
	public void setUp()
	{
		Client client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getVarbitValue(anyInt())).thenAnswer(inv -> varbits.getOrDefault(inv.<Integer>getArgument(0), 0));
		when(client.getVarpValue(anyInt())).thenAnswer(inv -> varps.getOrDefault(inv.<Integer>getArgument(0), 0));
		varps.put(VarPlayerID.RAIDS_PARTY_GROUPHOLDER, 7);

		ad = mock(Advertisement.class);
		when(ad.getId()).thenReturn("42");
		when(ad.getActivity()).thenReturn(Activity.CHAMBERS_OF_XERIC.getId());
		when(ad.getCoxScale()).thenReturn("");

		sync = new RaidBoardSync(client);
		sync.setHostedAd(() -> ad);
		sync.setListener((activity, scale, invocation) -> pushed.add(activity.getId() + ":" + scale + ":" + invocation));
	}

	private void ticks(int count)
	{
		for (int i = 0; i < count; i++)
		{
			sync.update();
		}
	}

	private void settle()
	{
		ticks(RaidBoardSync.SETTLE_TICKS + 1);
	}

	@Test
	public void scalingSetOnTheBoardUpdatesTheAdOnce()
	{
		varbits.put(VarbitID.RAIDS_SCALING, 4);
		settle();
		ticks(10);

		assertEquals(1, pushed.size());
		assertEquals("cox:4:0", pushed.get(0));
	}

	@Test
	public void aValueStillBeingSetIsNotPushed()
	{
		varbits.put(VarbitID.RAIDS_SCALING, 4);
		ticks(1);
		varbits.put(VarbitID.RAIDS_SCALING, 5);
		settle();

		assertEquals(1, pushed.size());
		assertEquals("cox:5:0", pushed.get(0));
	}

	@Test
	public void aBoardMatchingTheAdIsLeftAlone()
	{
		when(ad.getCoxScale()).thenReturn("4");
		varbits.put(VarbitID.RAIDS_SCALING, 4);
		settle();

		assertTrue(pushed.isEmpty());
	}

	@Test
	public void clearingTheScaleClearsTheAd()
	{
		when(ad.getCoxScale()).thenReturn("4");
		varbits.put(VarbitID.RAIDS_SCALING, 0);
		settle();

		assertEquals(1, pushed.size());
		assertEquals("cox::0", pushed.get(0));
	}

	@Test
	public void outsideAnInGamePartyNothingIsFollowed()
	{
		varps.put(VarPlayerID.RAIDS_PARTY_GROUPHOLDER, -1);
		varbits.put(VarbitID.RAIDS_SCALING, 4);
		settle();

		assertTrue(pushed.isEmpty());
	}

	@Test
	public void withoutAHostedAdNothingHappens()
	{
		sync.setHostedAd(() -> null);
		varbits.put(VarbitID.RAIDS_SCALING, 4);
		settle();

		assertTrue(pushed.isEmpty());
	}

	@Test
	public void theNextChangeFollowsOnceTheAdHasCaughtUp()
	{
		varbits.put(VarbitID.RAIDS_SCALING, 4);
		settle();
		when(ad.getCoxScale()).thenReturn("4");
		ticks(2);
		varbits.put(VarbitID.RAIDS_SCALING, 5);
		settle();

		assertEquals(2, pushed.size());
		assertEquals("cox:5:0", pushed.get(1));
	}

	@Test
	public void aNewAdStartsAfresh()
	{
		varbits.put(VarbitID.RAIDS_SCALING, 4);
		settle();
		assertEquals(1, pushed.size());

		when(ad.getId()).thenReturn("43");
		settle();
		assertEquals("the same board value is pushed to the new ad too", 2, pushed.size());
	}

	@Test
	public void tombsInvocationLevelFollowsTheObelisk()
	{
		when(ad.getActivity()).thenReturn(Activity.TOMBS_OF_AMASCUT.getId());
		when(ad.getInvocation()).thenReturn(0);
		varbits.put(VarbitID.TOA_CLIENT_PARTYSTATUS, 1);
		varbits.put(VarbitID.TOA_CLIENT_RAID_LEVEL, 150);
		settle();
		ticks(5);

		assertEquals(1, pushed.size());
		assertEquals("toa::150", pushed.get(0));
	}

	@Test
	public void otherRaidsAreNotFollowed()
	{
		when(ad.getActivity()).thenReturn(Activity.THEATRE_OF_BLOOD.getId());
		varbits.put(VarbitID.RAIDS_SCALING, 4);
		varbits.put(VarbitID.TOA_CLIENT_RAID_LEVEL, 150);
		settle();

		assertTrue(pushed.isEmpty());
	}
}
