package net.osparty.tools;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import net.osparty.enums.BossDefence;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Varbits;
import net.runelite.client.plugins.specialcounter.SpecialCounterUpdate;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;

/**
 * Tracks the live defence level of the monster a party is draining with
 * defence-lowering special attacks. It consumes {@link SpecialCounterUpdate}
 * events from RuneLite's Special Attack Counter plugin, which already aggregates
 * the local player's <em>and</em> party members' qualifying specs — so the
 * computed defence reflects the whole party's draining, not just our own.
 *
 * <p>The drain formulas and base-defence data are ported from the community
 * Party Defence Tracker plugin. Raid (CoX) party-size scaling is applied; the
 * Challenge Mode multiplier is not, so CM defence reads low.
 */
@Singleton
public class DefenceTracker
{
	private static final int COX_SCALED_PARTY_SIZE_VARBIT = 9540;

	private final Client client;

	/** -1 = nothing tracked. */
	private int bossIndex = -1;
	private String bossName = "";
	private double bossDef = -1;
	private double bossStartDef;
	private double minDef;

	private final List<SpecialCounterUpdate> pending = new ArrayList<>();

	@Value
	public static class DefenceState
	{
		int npcIndex;
		long current;
		long min;
		long base;
	}

	@Inject
	private DefenceTracker(Client client)
	{
		this.client = client;
	}

	/**
	 * Queue a special-counter update for processing on the next tick. Elder maul
	 * applies its large reduction before other weapons landing the same tick, so
	 * it's ordered first (mirrors the reference plugin).
	 */
	public void queue(SpecialCounterUpdate event)
	{
		if (event.getWeapon() == SpecialWeapon.ELDER_MAUL)
		{
			pending.add(0, event);
		}
		else
		{
			pending.add(event);
		}
	}

	/** Client thread. */
	public void onGameTick()
	{
		for (SpecialCounterUpdate event : pending)
		{
			process(event);
		}
		pending.clear();

		if (bossIndex != -1)
		{
			NPC npc = npcByIndex(bossIndex);
			if (npc == null || npc.isDead() || npc.getHealthRatio() == 0)
			{
				reset();
			}
		}
	}

	private void process(SpecialCounterUpdate event)
	{
		int index = event.getNpcIndex();
		NPC npc = npcByIndex(index);
		if (npc == null || npc.getName() == null)
		{
			return;
		}
		String name = npc.getName();
		if (BossDefence.forName(name) == null && bossIndex != index)
		{
			return; // not a tracked monster
		}
		if (bossIndex != index)
		{
			setBoss(name, index);
		}
		if (event.getWorld() == client.getWorld())
		{
			calculateDefence(event.getWeapon(), event.getHit());
		}
	}

	private void setBoss(String name, int index)
	{
		BossDefence boss = BossDefence.forName(name);
		bossName = name;
		bossIndex = index;
		bossDef = boss != null ? boss.getBaseDef() : 0;
		minDef = boss != null ? boss.getMinDef() : 0;

		// In CoX, the boss defence is scaled up by the (scaled) party size.
		if (boss != null && client.getVarbitValue(Varbits.IN_RAID) == 1 && isCoxBoss(name))
		{
			int partySize = Math.max(1, client.getVarbitValue(COX_SCALED_PARTY_SIZE_VARBIT));
			bossDef = (int) (bossDef * (((int) Math.sqrt(partySize - 1) + ((partySize - 1) * 7 / 10 + 100)) / 100.0));
		}
		bossStartDef = bossDef;
	}

	private void calculateDefence(SpecialWeapon weapon, int hit)
	{
		double base = BossDefence.baseDefenceOf(bossName);
		switch (weapon)
		{
			case DRAGON_WARHAMMER:
				if (hit > 0)
				{
					bossDef -= bossDef * .30;
				}
				break;
			case ELDER_MAUL:
				if (hit > 0)
				{
					bossDef -= bossDef * .35;
				}
				break;
			case BANDOS_GODSWORD:
				if (hit > 0)
				{
					// Corp / undowned Bloat take double the BGS drain.
					boolean doubled = bossName.equalsIgnoreCase("Corporeal Beast")
						|| bossName.equalsIgnoreCase("Pestilent Bloat");
					bossDef -= doubled ? hit * 2 : hit;
				}
				break;
			case ARCLIGHT:
			case DARKLIGHT:
				if (hit > 0)
				{
					bossDef -= base * (isDemon(bossName) ? .10 : .05);
				}
				break;
			case EMBERLIGHT:
				if (hit > 0)
				{
					bossDef -= base * (isDemon(bossName) ? .15 : .05);
				}
				break;
			case BARRELCHEST_ANCHOR:
				bossDef -= hit * .10;
				break;
			case BONE_DAGGER:
			case DORGESHUUN_CROSSBOW:
				if (bossDef >= base)
				{
					bossDef -= hit;
				}
				break;
			case ACCURSED_SCEPTRE:
				if (hit > 0 && bossDef > base * .85)
				{
					bossDef = base * .85;
				}
				break;
			default:
				return; // weapon doesn't drain defence
		}
		bossDef = Math.max(bossDef, minDef);
	}

	private static boolean isDemon(String name)
	{
		return name.equalsIgnoreCase("K'ril Tsutsaroth")
			|| name.equalsIgnoreCase("Abyssal Sire")
			|| name.equalsIgnoreCase("Yama");
	}

	private static boolean isCoxBoss(String name)
	{
		switch (name)
		{
			case "Abyssal portal":
			case "Deathly mage":
			case "Deathly ranger":
			case "Great Olm":
			case "Great Olm (Left claw)":
			case "Great Olm (Right claw)":
			case "Ice Demon":
			case "Skeletal Mystic":
			case "Tekton":
			case "Tekton (enraged)":
			case "Vasa Nistirio":
			case "Lizardman shaman":
				return true;
			default:
				return false;
		}
	}

	private NPC npcByIndex(int index)
	{
		for (NPC npc : client.getNpcs())
		{
			if (npc != null && npc.getIndex() == index)
			{
				return npc;
			}
		}
		return null;
	}

	public DefenceState state()
	{
		if (bossIndex == -1 || bossDef < 0)
		{
			return null;
		}
		return new DefenceState(bossIndex, Math.round(bossDef), Math.round(minDef), Math.round(bossStartDef));
	}

	public void reset()
	{
		bossIndex = -1;
		bossName = "";
		bossDef = -1;
		bossStartDef = 0;
		minDef = 0;
		pending.clear();
	}
}
