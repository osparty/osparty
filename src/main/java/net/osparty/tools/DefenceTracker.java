package net.osparty.tools;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import net.osparty.OSPartyConfig;
import net.osparty.enums.BossDefence;
import net.osparty.enums.SpecWeapon;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Varbits;
import net.runelite.api.gameval.VarbitID;

/**
 * Tracks the live defence of the monster a party is draining with defence-lowering
 * special attacks. Both the physical Defence level and the magic-defence roll are
 * tracked. Drains are supplied by {@link SpecialAttackTracker} for both the local
 * player and party members (via the party bus), so the computed values reflect the
 * whole party's draining, not just our own.
 *
 * <p>The drain formulas and CoX party-size and Challenge Mode scaling mirror the OSRS
 * Wiki DPS calculator (github.com/weirdgloop/osrs-dps-calc, {@code lib/scaling/DefenceReduction.ts}
 * and {@code lib/scaling/ChambersOfXeric.ts}), including its integer truncation.
 *
 * <p>Magic defence follows the OSRS roll {@code (9 + Magic level) * (Magic-def bonus + 64)}.
 * Accursed sceptre and Seercull drain the Magic <em>level</em>; the Eye of ayak drains
 * the Magic-defence <em>bonus</em>. A few monsters ({@link BossDefence.Flag#MAGIC_USES_DEFENCE})
 * roll magic defence off their Defence level, so their physical drains lower it too.
 */
@Singleton
public class DefenceTracker
{
	private static final int COX_SCALED_PARTY_SIZE_VARBIT = 9540;

	private final Client client;
	private final OSPartyConfig config;

	/** -1 = nothing tracked. */
	private int bossIndex = -1;
	private String bossName = "";
	private long bossDef = -1;
	private long bossStartDef;
	private long minDef;

	/** Only tracked so an overkill Bandos godsword spec can spill through them into Magic. */
	private long atkLevel;
	private long strLevel;

	private long magicLevel;
	private long magicStartLevel;
	private long magicDefBonus;
	private long magicStartDefBonus;
	private boolean magicUsesDefence;
	/** The accursed sceptre's curse doesn't stack, so it only ever lands once per monster. */
	private boolean accursedApplied;
	/** True once a special attack has actually landed on the tracked monster. */
	private boolean drained;

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
		/** The magic-defence bonus itself, which is what the Eye of ayak drains. */
		long magicDef;
		long magicBaseDef;
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
	private DefenceTracker(Client client, OSPartyConfig config)
	{
		this.client = client;
		this.config = config;
	}

	/**
	 * Queue a defence-draining special attack for processing on the next tick.
	 * Elder maul applies its large reduction before other weapons landing the same
	 * tick, so it's ordered first (mirrors the reference plugin).
	 */
	public void queue(SpecWeapon weapon, int npcIndex, int hit, int world)
	{
		if (weapon == null)
		{
			return; // a newer OSParty broadcast a weapon this version doesn't know
		}
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

		if (config.defenceAlwaysShow())
		{
			followInteractingTarget();
		}
	}

	/**
	 * With "show before any spec" on, display the monster we're attacking at its starting
	 * levels. Once anything has actually been drained we stop following our target, so the
	 * drained monster stays on screen until it dies even if we look away from it.
	 */
	private void followInteractingTarget()
	{
		if (drained)
		{
			return;
		}
		NPC target = interactingNpc();
		if (target == null || target.getName() == null || BossDefence.forName(target.getName()) == null)
		{
			if (bossIndex != -1)
			{
				reset();
			}
			return;
		}
		if (target.getIndex() != bossIndex)
		{
			setBoss(target.getName(), target.getIndex());
		}
	}

	private NPC interactingNpc()
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return null;
		}
		Actor target = local.getInteracting();
		return target instanceof NPC ? (NPC) target : null;
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
			drained = true;
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
		atkLevel = boss != null ? boss.getBaseAtk() : 0;
		strLevel = boss != null ? boss.getBaseStr() : 0;
		magicLevel = boss != null ? boss.getBaseMagic() : 0;
		magicDefBonus = boss != null ? boss.getBaseMagicDef() : 0;
		magicUsesDefence = boss != null && boss.has(BossDefence.Flag.MAGIC_USES_DEFENCE);
		accursedApplied = false;
		drained = false;

		// In CoX, the boss's combat levels are scaled up by the (scaled) party size and again
		// in Challenge Mode, but the magic-defence bonus is not. Defence always scales as a
		// defensive stat; Magic counts as defensive for a few monsters and offensive for the rest.
		if (boss != null && client.getVarbitValue(Varbits.IN_RAID) == 1 && isCoxBoss(name))
		{
			int partySize = Math.max(1, client.getVarbitValue(COX_SCALED_PARTY_SIZE_VARBIT));
			int n = partySize - 1;
			int defensivePct = 100 + (int) Math.sqrt(n) + n * 7 / 10;
			int offensivePct = 100 + (int) Math.sqrt(n) * 7 + n;
			boolean magicIsDefensive = boss.has(BossDefence.Flag.COX_MAGIC_IS_DEFENSIVE);

			bossDef = bossDef * defensivePct / 100;
			magicLevel = magicLevel * (magicIsDefensive ? defensivePct : offensivePct) / 100;

			if (client.getVarbitValue(VarbitID.RAIDS_CHALLENGE_MODE) == 1)
			{
				// Tekton is given a smaller defensive bump than everything else so that
				// specs still land; offensive stats always take the flat 50%.
				int cmDefencePct = isTekton(name) ? (partySize < 4 ? 20 : 35) : 50;
				bossDef = addPercent(bossDef, cmDefencePct);
				magicLevel = addPercent(magicLevel, magicIsDefensive ? cmDefencePct : 50);
			}
		}
		bossStartDef = bossDef;
		magicStartLevel = magicLevel;
		magicStartDefBonus = magicDefBonus;
	}

	private void calculateDefence(SpecWeapon weapon, int hit)
	{
		// Percentage drains are of the monster's *starting* Defence, which for a CoX
		// boss is the party-scaled value rather than the raw table entry.
		long base = bossStartDef;
		switch (weapon)
		{
			case DRAGON_WARHAMMER:
				if (hit > 0)
				{
					bossDef -= bossDef * 3 / 10;
				}
				break;
			case ELDER_MAUL:
				if (hit > 0)
				{
					bossDef -= bossDef * 35 / 100;
				}
				break;
			case BANDOS_GODSWORD:
				if (hit > 0)
				{
					// Corp / undowned Bloat take double the BGS drain.
					boolean doubled = bossName.equalsIgnoreCase("Corporeal Beast")
						|| bossName.equalsIgnoreCase("Pestilent Bloat");
					drainBandos(doubled ? hit * 2L : hit);
				}
				break;
			case TONALZTICS_OF_RALOS:
				// Ralos' Rise throws two glaives; each one that lands takes an eighth of
				// the target's current Magic level off its Defence. hit is the number of
				// glaives that connected, not damage.
				for (int i = 0; i < hit; i++)
				{
					bossDef -= magicLevel / 8;
				}
				break;
			case ARCLIGHT:
			case DARKLIGHT:
				if (hit > 0)
				{
					bossDef -= base * (isDemon(bossName) ? 2 : 1) / 20 + 1;
				}
				break;
			case EMBERLIGHT:
				if (hit > 0)
				{
					bossDef -= base * (isDemon(bossName) ? 3 : 1) / 20 + 1;
				}
				break;
			case BARRELCHEST_ANCHOR:
				bossDef -= hit / 10;
				break;
			case BONE_DAGGER:
			case DORGESHUUN_CROSSBOW:
				if (bossDef >= base)
				{
					bossDef -= hit;
				}
				break;
			case ACCURSED_SCEPTRE:
				// Condemn takes 15% off the Defence and Magic levels the monster has right
				// now, so landing it after a warhammer drains more than landing it first.
				// The curse doesn't stack, so only the first one to land does anything.
				if (hit > 0 && !accursedApplied)
				{
					accursedApplied = true;
					bossDef = bossDef * 17 / 20;
					magicLevel = magicLevel * 17 / 20;
				}
				break;
			case SEERCULL:
				// Soulshot lowers Magic level by the damage dealt, and stacks.
				magicLevel -= hit;
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

	/**
	 * The Bandos godsword drains Defence by the damage dealt, and any damage left over
	 * once Defence bottoms out rolls on into Strength, then Attack, then Magic. A skill
	 * that stops short of zero — including because it hit its floor — ends the spill.
	 */
	private void drainBandos(long damage)
	{
		long start = bossDef;
		bossDef = Math.max(minDef, bossDef - damage);
		damage = bossDef > 0 ? 0 : damage - start;

		if (damage > 0)
		{
			start = strLevel;
			strLevel = Math.max(0, strLevel - damage);
			damage = strLevel > 0 ? 0 : damage - start;
		}
		if (damage > 0)
		{
			start = atkLevel;
			atkLevel = Math.max(0, atkLevel - damage);
			damage = atkLevel > 0 ? 0 : damage - start;
		}
		if (damage > 0)
		{
			magicLevel = Math.max(0, magicLevel - damage);
		}
	}

	/** Integer percentage increase, truncated, as the game applies it. */
	private static long addPercent(long value, int percent)
	{
		return value + value * percent / 100;
	}

	private static boolean isTekton(String name)
	{
		return name.equals("Tekton") || name.equals("Tekton (enraged)");
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
		long roll = (9 + (magicUsesDefence ? bossDef : magicLevel)) * (magicDefBonus + 64);
		long baseRoll = (9 + (magicUsesDefence ? bossStartDef : magicStartLevel)) * (magicStartDefBonus + 64);
		return new DefenceState(bossIndex, bossDef, minDef, bossStartDef, roll, baseRoll,
			magicDefBonus, magicStartDefBonus);
	}

	public void reset()
	{
		bossIndex = -1;
		bossName = "";
		bossDef = -1;
		bossStartDef = 0;
		minDef = 0;
		atkLevel = 0;
		strLevel = 0;
		magicLevel = 0;
		magicStartLevel = 0;
		magicDefBonus = 0;
		magicStartDefBonus = 0;
		magicUsesDefence = false;
		accursedApplied = false;
		drained = false;
		pending.clear();
	}
}
