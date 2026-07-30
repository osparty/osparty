package net.osparty.tools;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.osparty.OSPartyConfig;
import net.osparty.enums.SpecWeapon;
import net.osparty.party.LivePartyBackend;
import net.osparty.party.SpecDrainMessage;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Hitsplat;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.FakeXpDrop;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;

/**
 * Self-contained detection of the local player's defence-draining special
 * attacks. It watches the special-attack energy drop, identifies the equipped
 * {@link SpecWeapon}, waits for the resulting hitsplat on the interacting NPC,
 * then feeds {@link DefenceTracker} and broadcasts a {@link SpecDrainMessage} so
 * every party member aggregates the whole group's draining.
 *
 * <p>This replicates the relevant detection from RuneLite's Special Attack
 * Counter plugin so OSParty no longer needs that plugin installed or enabled.
 * The event methods are driven from the plugin's {@code @Subscribe} handlers,
 * matching how {@link DefenceTracker} is wired.
 */
@Singleton
public class SpecialAttackTracker
{
	private final Client client;
	private final ClientThread clientThread;
	private final DefenceTracker defenceTracker;
	/** Where a drain is broadcast, and what tells us whether an inbound one is our own echo. */
	private final LivePartyBackend liveParty;
	private final OSPartyConfig config;

	private int specialPercentage = -1;
	private int lastHitpointsXp = -1;
	private int lastHpChangeCycle;

	private SpecWeapon specWeapon;
	private NPC specTarget;
	private boolean specHpChange;
	private int hitsplatTick;
	private final List<Hitsplat> hitsplats = new ArrayList<>();

	@Inject
	private SpecialAttackTracker(Client client, ClientThread clientThread,
		DefenceTracker defenceTracker, LivePartyBackend liveParty, OSPartyConfig config)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.defenceTracker = defenceTracker;
		this.liveParty = liveParty;
		this.config = config;
	}

	public void reset()
	{
		specialPercentage = -1;
		lastHitpointsXp = -1;
		clearSpec();
	}

	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() == Skill.HITPOINTS)
		{
			if (event.getXp() > lastHitpointsXp)
			{
				lastHpChangeCycle = client.getGameCycle();
			}
			lastHitpointsXp = event.getXp();
		}
	}

	public void onFakeXpDrop(FakeXpDrop event)
	{
		if (event.getSkill() == Skill.HITPOINTS)
		{
			lastHpChangeCycle = client.getGameCycle();
		}
	}

	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarpId() != VarPlayerID.SA_ENERGY)
		{
			return;
		}

		int percentage = event.getValue();
		// Energy only dropping (spending it) signals a special attack.
		if (specialPercentage == -1 || percentage >= specialPercentage)
		{
			specialPercentage = percentage;
			return;
		}
		specialPercentage = percentage;

		// getInteracting() isn't valid yet this cycle; defer, capturing the current
		// server tick now for the hitsplat-timing calculation.
		final int serverTicks = client.getTickCount();
		clientThread.invokeLater(() ->
		{
			specWeapon = equippedSpecWeapon();
			if (specWeapon == null)
			{
				return;
			}
			Actor target = client.getLocalPlayer().getInteracting();
			specTarget = target instanceof NPC ? (NPC) target : null;
			specHpChange = lastHpChangeCycle == client.getGameCycle();
			hitsplatTick = serverTicks + getHitDelay(specWeapon, target);
		});
	}

	public void onHitsplatApplied(HitsplatApplied event)
	{
		Hitsplat hitsplat = event.getHitsplat();
		if (!hitsplat.isMine() || event.getActor() == client.getLocalPlayer())
		{
			return;
		}
		if (specTarget == null || event.getActor() != specTarget)
		{
			return;
		}
		if (hitsplatTick == client.getTickCount())
		{
			hitsplats.add(hitsplat);
		}
	}

	public void onNpcDespawned(NpcDespawned event)
	{
		if (specTarget == event.getNpc())
		{
			specTarget = null;
		}
	}

	/** Client thread; run before {@link DefenceTracker#onGameTick()} so this tick's drain lands. */
	public void onGameTick()
	{
		if (specWeapon == null || specTarget == null)
		{
			return;
		}

		int tick = client.getTickCount();
		if (specWeapon == SpecWeapon.ELDER_MAUL)
		{
			// Elder maul drains immediately without waiting for the hitsplat; a
			// hitpoints change this cycle tells us it landed rather than missed.
			recordDrain(specWeapon, specHpChange ? 1 : 0, specTarget);
			clearSpec();
		}
		else if (tick == hitsplatTick)
		{
			if (specWeapon == SpecWeapon.TONALZTICS_OF_RALOS)
			{
				// Two glaives, so two hitsplats. The drain depends on how many of them
				// landed rather than on the damage, so report the count as the hit.
				if (hitsplats.size() >= 2)
				{
					Hitsplat last = hitsplats.get(hitsplats.size() - 1);
					Hitsplat secondToLast = hitsplats.get(hitsplats.size() - 2);
					int landed = Math.min(last.getAmount(), 1) + Math.min(secondToLast.getAmount(), 1);
					recordDrain(specWeapon, landed, specTarget);
				}
			}
			// The weapon hitsplat is last, after same-tick splats from venge/thralls.
			else if (!hitsplats.isEmpty())
			{
				recordDrain(specWeapon, hitsplats.get(hitsplats.size() - 1).getAmount(), specTarget);
			}
			clearSpec();
		}
		else if (tick > hitsplatTick)
		{
			// Missed the expected hitsplat tick; give up on this spec.
			clearSpec();
		}
	}

	/** A party member's drain, received over the live socket. */
	public void onSpecDrain(SpecDrainMessage message)
	{
		if (message.getWorld() != client.getWorld())
		{
			return;
		}
		if (liveParty.isForLocalMember(message.getMemberId()))
		{
			return; // our own broadcast echoed back to us; queuing it would double-count our spec
		}
		defenceTracker.queue(message.getWeapon(), message.getNpcIndex(), message.getHit(), message.getWorld());
	}

	private void recordDrain(SpecWeapon weapon, int hit, NPC target)
	{
		if (!liveParty.isConnected() && !config.defenceOutsideParty())
		{
			return;
		}

		int world = client.getWorld();
		int npcIndex = target.getIndex();
		defenceTracker.queue(weapon, npcIndex, hit, world);

		// Unconditional: the backend drops it when we are not in a party, and a drain landed outside one
		// has nobody to tell anyway.
		liveParty.sendSpecDrain(npcIndex, weapon.name(), hit, world);
	}

	private void clearSpec()
	{
		specWeapon = null;
		specTarget = null;
		hitsplats.clear();
	}

	private SpecWeapon equippedSpecWeapon()
	{
		ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
		if (equipment == null)
		{
			return null;
		}
		Item weapon = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		if (weapon == null)
		{
			return null;
		}
		for (SpecWeapon specialWeapon : SpecWeapon.values())
		{
			if (specialWeapon.matches(weapon.getId()))
			{
				return specialWeapon;
			}
		}
		return null;
	}

	private int getHitDelay(SpecWeapon weapon, Actor target)
	{
		Player player = client.getLocalPlayer();
		if (target == null || player == null)
		{
			return 1;
		}
		WorldPoint playerWp = player.getWorldLocation();
		WorldArea targetArea = target.getWorldArea();
		if (playerWp == null || targetArea == null)
		{
			return 1;
		}
		return weapon.getHitDelay(targetArea.distanceTo(playerWp));
	}
}
