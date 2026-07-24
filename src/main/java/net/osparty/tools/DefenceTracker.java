package net.osparty.tools;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import net.osparty.enums.BossDefence;
import net.osparty.enums.SpecWeapon;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Varbits;

/**
 * Tracks the live defence of the monster a party is draining with defence-lowering
 * special attacks. Both the physical Defence level and the magic-defence roll are
 * tracked. Drains are supplied by {@link SpecialAttackTracker} for both the local
 * player and party members (via the party bus), so the computed values reflect the
 * whole party's draining, not just our own.
 *
 * <p>The physical drain formulas and base-defence data are ported from the community
 * Party Defence Tracker plugin. Raid (CoX) party-size scaling is applied to the
 * Defence and Magic levels; the Challenge Mode multiplier is not, so CM reads low.
 *
 * <p>Magic defence follows the OSRS roll {@code (9 + Magic level) * (Magic-def bonus + 64)}.
 * Accursed sceptre and Seercull drain the Magic <em>level</em>; the Eye of ayak drains
 * the Magic-defence <em>bonus</em>. A few monsters ({@link BossDefence#isMagicUsesDefence()})
 * roll magic defence off their Defence level, so their physical drains lower it too.
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

	private double magicLevel;
	private double magicStartLevel;
	private double magicDefBonus;
	private double magicStartDefBonus;
	private boolean magicUsesDefence;

	private final List<Drain> pending = new ArrayList<>();

	@Value
	public static class DefenceState
	{
		int npcIndex;
		long current;
		long min;
		long base;
		/** Current magic-defence roll and its starting value; percent = magicRoll / magicBaseRoll. */
		long magicRoll;
		long magicBaseRoll;
	}

	/** One defence-draining special attack landed on an NPC, from any party member. */
	@Value
	private static class Drain
	{
		SpecWeapon weapon;
		int npcIndex;
		int hit;
		int world;
	}

	@Inject
	private DefenceTracker(Client client)
	{
		this.client = client;
	}

	/**
	 * Queue a defence-draining special attack for processing on the next tick.
	 * Elder maul applies its large reduction before other weapons landing the same
	 * tick, so it's ordered first (mirrors the reference plugin).
	 */
	public void queue(SpecWeapon weapon, int npcIndex, int hit, int world)
	{
		Drain drain = new Drain(weapon, npcIndex, hit, world);
		if (weapon == SpecWeapon.ELDER_MAUL)
		{
			pending.add(0, drain);
		}
		else
		{
			pending.add(drain);
		}
	}

	/** Client thread. */
	public void onGameTick()
	{
		for (Drain drain : pending)
		{
			process(drain);
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

	private void process(Drain drain)
	{
		int index = drain.getNpcIndex();
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
		if (drain.getWorld() == client.getWorld())
		{
			calculateDefence(drain.getWeapon(), drain.getHit());
		}
	}

	private void setBoss(String name, int index)
	{
		BossDefence boss = BossDefence.forName(name);
		bossName = name;
		bossIndex = index;
		bossDef = boss != null ? boss.getBaseDef() : 0;
		minDef = boss != null ? boss.getMinDef() : 0;
		magicLevel = boss != null ? boss.getBaseMagic() : 0;
		magicDefBonus = boss != null ? boss.getBaseMagicDef() : 0;
		magicUsesDefence = boss != null && boss.isMagicUsesDefence();

		// In CoX, the boss's combat levels are scaled up by the (scaled) party size.
		// This applies to the Defence and Magic levels but not to the magic-defence bonus.
		if (boss != null && client.getVarbitValue(Varbits.IN_RAID) == 1 && isCoxBoss(name))
		{
			int partySize = Math.max(1, client.getVarbitValue(COX_SCALED_PARTY_SIZE_VARBIT));
			double mult = ((int) Math.sqrt(partySize - 1) + ((partySize - 1) * 7 / 10 + 100)) / 100.0;
			bossDef = (int) (bossDef * mult);
			magicLevel = (int) (magicLevel * mult);
		}
		bossStartDef = bossDef;
		magicStartLevel = magicLevel;
		magicStartDefBonus = magicDefBonus;
	}

	private void calculateDefence(SpecWeapon weapon, int hit)
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
				// Condemn drains both Defence and Magic to at most 15% below their
				// starting level; it never stacks past that cap.
				if (hit > 0)
				{
					if (bossDef > base * .85)
					{
						bossDef = base * .85;
					}
					if (magicLevel > magicStartLevel * .85)
					{
						magicLevel = magicStartLevel * .85;
					}
				}
				break;
			case SEERCULL:
				// Soulshot lowers Magic level by the damage dealt, but only if the
				// level has not already been reduced (mirrors the bone-dagger rule).
				if (magicLevel >= magicStartLevel)
				{
					magicLevel -= hit;
				}
				break;
			case EYE_OF_AYAK:
				// Soul Rend lowers the Magic-defence bonus by the damage dealt,
				// stacking down to a floor of 0 (negative bonuses are left as-is).
				if (hit > 0 && magicDefBonus > 0)
				{
					magicDefBonus = Math.max(0, magicDefBonus - hit);
				}
				break;
			default:
				return; // weapon doesn't drain defence
		}
		bossDef = Math.max(bossDef, minDef);
		magicLevel = Math.max(magicLevel, 0);
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
		double roll = magicUsesDefence
			? (9 + bossDef) * (magicDefBonus + 64)
			: (9 + magicLevel) * (magicDefBonus + 64);
		double baseRoll = magicUsesDefence
			? (9 + bossStartDef) * (magicStartDefBonus + 64)
			: (9 + magicStartLevel) * (magicStartDefBonus + 64);
		return new DefenceState(bossIndex, Math.round(bossDef), Math.round(minDef), Math.round(bossStartDef),
			Math.round(roll), Math.round(baseRoll));
	}

	public void reset()
	{
		bossIndex = -1;
		bossName = "";
		bossDef = -1;
		bossStartDef = 0;
		minDef = 0;
		magicLevel = 0;
		magicStartLevel = 0;
		magicDefBonus = 0;
		magicStartDefBonus = 0;
		magicUsesDefence = false;
		pending.clear();
	}
}
