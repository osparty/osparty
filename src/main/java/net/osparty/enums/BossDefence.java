package net.osparty.enums;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import lombok.Getter;
import net.osparty.tools.DefenceTracker;

/**
 * Base combat stats for monsters whose defence can be drained by special attacks,
 * used by {@link DefenceTracker} to compute the live defence level.
 *
 * <p>Every column is sourced from the OSRS Wiki DPS calculator
 * (github.com/weirdgloop/osrs-dps-calc): the levels from {@code cdn/json/monsters.json}
 * {@code skills}, {@code baseMagicDef} from {@code defensive.magic}, and {@code minDef}
 * from the calculator's {@code getDefenceFloor}. Bosses with several stat blocks use the
 * version that can actually be drained (noted per entry where it matters).
 *
 * <p>Attack and Strength are here only because a Bandos godsword spec that drains
 * Defence to zero spills the leftover damage into Strength, then Attack, then Magic.
 *
 * <p>A monster's magic-defence roll is {@code (9 + Magic level) * (Magic-def bonus + 64)}.
 * A handful of monsters ({@link Flag#MAGIC_USES_DEFENCE}) roll magic defence off their
 * Defence level instead of their Magic level, so draining their Defence also
 * lowers their magic defence.
 */
@Getter
public enum BossDefence
{
	// name, Defence, Magic, Attack, Strength, magic-def bonus, Defence floor, flags
	ABYSSAL_PORTAL("Abyssal portal", 176, 176, 1, 1, 60, 0, Flag.COX_MAGIC_IS_DEFENSIVE),
	// The Sire's magic-def bonus drops to -40 once it's stunned in phase 3; 20 covers the rest.
	ABYSSAL_SIRE("Abyssal Sire", 250, 200, 180, 136, 20, 0),
	AKKHA("Akkha", 80, 100, 100, 140, 10, 70),
	AKKHAS_SHADOW("Akkha's Shadow", 30, 100, 100, 140, 10, 0),
	ALCHEMICAL_HYDRA("Alchemical Hydra", 100, 260, 100, 100, 150, 0),
	ARAXXOR("Araxxor", 135, 190, 320, 320, 237, 90),
	ARTIO("Artio", 150, 90, 250, 270, 0, 0),
	BA_BA("Ba-Ba", 80, 100, 150, 160, 280, 60),
	CALLISTO("Callisto", 225, 140, 350, 300, 0, 0),
	CALVARION("Calvar'ion", 225, 178, 250, 250, 198, 0),
	CERBERUS("Cerberus", 100, 220, 220, 220, 65, 0),
	CHAOS_ELEMENTAL("Chaos Elemental", 270, 270, 270, 270, 70, 0),
	COMMANDER_ZILYANA("Commander Zilyana", 300, 300, 280, 196, 100, 0),
	// The ejected warden core attacked in Wardens phase 2.
	CORE("<col=00ffff>Core</col>", 100, 190, 300, 150, -30, 0),
	CORPOREAL_BEAST("Corporeal Beast", 310, 350, 320, 320, 150, 0),
	DAGANNOTH_PRIME("Dagannoth Prime", 255, 255, 255, 255, 255, 0),
	DAGANNOTH_REX("Dagannoth Rex", 255, 0, 255, 255, 10, 0),
	DAGANNOTH_SUPREME("Dagannoth Supreme", 128, 255, 255, 255, 255, 0),
	DEATHLY_MAGE("Deathly mage", 155, 210, 1, 1, 0, 0),
	DEATHLY_RANGER("Deathly ranger", 155, 155, 1, 1, 0, 0, Flag.COX_MAGIC_IS_DEFENSIVE),
	// Wardens phase 3 (the drainable one); the phase-2 core is tracked as CORE.
	ELIDINIS_WARDEN("Elidinis' Warden", 150, 150, 150, 150, 20, 120),
	GENERAL_GRAARDOR("General Graardor", 250, 80, 280, 350, 298, 0),
	GIANT_MOLE("Giant Mole", 200, 200, 200, 200, 80, 0),
	GREAT_OLM("Great Olm", 150, 250, 250, 250, 200, 0),
	GREAT_OLM_LEFT_CLAW("Great Olm (Left claw)", 175, 175, 250, 250, 200, 0, Flag.COX_MAGIC_IS_DEFENSIVE),
	GREAT_OLM_RIGHT_CLAW("Great Olm (Right claw)", 175, 87, 250, 250, 50, 0, Flag.COX_MAGIC_IS_DEFENSIVE),
	ICE_DEMON("Ice Demon", 160, 390, 1, 1, 40, 0, Flag.MAGIC_USES_DEFENCE),
	// The Queen's magic-def bonus is 10 while airborne; 100 is the crawling (meleeable) form.
	KALPHITE_QUEEN("Kalphite Queen", 300, 150, 300, 300, 100, 0),
	KEPHRI("Kephri", 80, 125, 0, 0, 200, 60),
	KING_BLACK_DRAGON("King Black Dragon", 240, 240, 240, 240, 80, 0),
	KREE_ARRA("Kree'arra", 260, 200, 300, 200, 200, 0),
	KRIL_TSUTSAROTH("K'ril Tsutsaroth", 270, 200, 340, 300, 80, 0),
	LIZARDMAN_SHAMAN("Lizardman shaman", 140, 130, 120, 120, 50, 0),
	NEX("Nex", 260, 230, 315, 200, 300, 250),
	NYLOCAS_VASILIAS("Nylocas Vasilias", 50, 50, 400, 350, 0, 0),
	OBELISK("<col=00ffff>Obelisk</col>", 100, 100, 200, 150, 50, 60),
	PESTILENT_BLOAT("Pestilent Bloat", 100, 150, 250, 340, 600, 0),
	// Muspah's magic-def bonus is phase-dependent (34 melee, 437 most phases); using the common 437.
	PHANTOM_MUSPAH("Phantom Muspah", 200, 150, 280, 280, 437, 0),
	PHOSANIS_NIGHTMARE("Phosani's Nightmare", 150, 150, 150, 150, 600, 120),
	SARACHNIS("Sarachnis", 150, 150, 200, 240, 150, 0),
	SCORPIA("Scorpia", 180, 1, 250, 150, 44, 0),
	SKELETAL_MYSTIC("Skeletal Mystic", 187, 140, 140, 140, 140, 0),
	SKOTIZO("Skotizo", 200, 280, 240, 250, 80, 0),
	SOTETSEG("Sotetseg", 200, 250, 250, 250, 30, 100),
	SPINDEL("Spindel", 225, 235, 200, 130, 205, 0),
	TEKTON("Tekton", 205, 205, 390, 390, 0, 0, Flag.COX_MAGIC_IS_DEFENSIVE),
	TEKTON_ENRAGED("Tekton (enraged)", 205, 205, 390, 390, 0, 0, Flag.COX_MAGIC_IS_DEFENSIVE),
	THE_HUEYCOATL("The Hueycoatl", 125, 50, 150, 50, 200, 120),
	THE_MAIDEN_OF_SUGADINTI("The Maiden of Sugadinti", 200, 350, 350, 350, 0, 0),
	THE_NIGHTMARE("The Nightmare", 150, 150, 150, 150, 600, 120),
	TUMEKENS_WARDEN("Tumeken's Warden", 150, 150, 150, 150, 20, 120),
	TZKAL_ZUK("TzKal-Zuk", 260, 150, 350, 600, 350, 0),
	TZTOK_JAD("TzTok-Jad", 480, 480, 640, 960, 0, 0),
	// Vardorvis has no Defence level at all, so only his magic defence can move.
	VARDORVIS("Vardorvis", 0, 215, 280, 0, 580, 0),
	VASA("Vasa Nistirio", 175, 230, 1, 1, 400, 0),
	VENENATIS("Venenatis", 321, 300, 300, 200, 300, 0),
	// Phase 3 (the long damage phase); Verzik ignores defence drains entirely, hence the floor.
	VERZIK_VITUR("Verzik Vitur", 150, 300, 400, 400, 100, 150, Flag.MAGIC_USES_DEFENCE),
	VETION("Vet'ion", 395, 300, 430, 430, 250, 0),
	VORKATH("Vorkath", 214, 150, 560, 308, 240, 0),
	XARPUS("Xarpus", 250, 220, 1, 1, 0, 0),
	YAMA("Yama", 225, 250, 320, 350, 0, 145),
	ZEBAK("Zebak", 70, 100, 250, 140, 200, 50),
	// Zulrah's magic-def bonus is form-dependent (-45 serpentine, 0 magma, 300 tanzanite); using 0.
	// A single-value entry can't follow the rotation, so the Eye of ayak drain won't read exactly per form.
	ZULRAH("Zulrah", 300, 300, 1, 1, 0, 0);

	public enum Flag
	{
		/** The monster rolls magic defence off its Defence level rather than its Magic level. */
		MAGIC_USES_DEFENCE,
		/** In CoX the monster's Magic level scales with the defensive party-size factor, not the offensive one. */
		COX_MAGIC_IS_DEFENSIVE
	}

	private final String npcName;
	private final int baseDef;
	private final int baseMagic;
	private final int baseAtk;
	private final int baseStr;
	private final int baseMagicDef;
	private final int minDef;
	private final Set<Flag> flags;

	BossDefence(String npcName, int baseDef, int baseMagic, int baseAtk, int baseStr, int baseMagicDef, int minDef,
		Flag... flags)
	{
		this.npcName = npcName;
		this.baseDef = baseDef;
		this.baseMagic = baseMagic;
		this.baseAtk = baseAtk;
		this.baseStr = baseStr;
		this.baseMagicDef = baseMagicDef;
		this.minDef = minDef;
		this.flags = flags.length == 0 ? EnumSet.noneOf(Flag.class) : EnumSet.copyOf(Arrays.asList(flags));
	}

	public boolean has(Flag flag)
	{
		return flags.contains(flag);
	}

	/** The entry whose npcName contains {@code name}, so a partial in-game name still matches. */
	public static BossDefence matchingNpcName(String name)
	{
		if (name == null)
		{
			return null;
		}
		for (BossDefence boss : values())
		{
			if (boss.npcName.contains(name))
			{
				return boss;
			}
		}
		return null;
	}
}
