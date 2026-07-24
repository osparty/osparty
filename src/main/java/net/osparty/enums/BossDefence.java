package net.osparty.enums;

import lombok.Getter;
import net.osparty.tools.DefenceTracker;

/**
 * Base combat stats for monsters whose defence can be drained by special attacks,
 * used by {@link DefenceTracker} to compute the live defence level.
 *
 * <p>{@code baseDef}/{@code minDef} (physical defence) are ported from the
 * community "Party Defence Tracker" plugin (github.com/Hyftar/party-defence-tracker,
 * BSD-2). {@code baseMagic} (the monster's Magic level) and {@code baseMagicDef}
 * (its Magic-defence bonus) are sourced from the OSRS Wiki DPS calculator data
 * (github.com/weirdgloop/osrs-dps-calc) and drive the magic-defence tracking.
 *
 * <p>A monster's magic-defence roll is {@code (9 + Magic level) * (Magic-def bonus + 64)}.
 * A handful of monsters ({@code magicUsesDefence}) roll magic defence off their
 * Defence level instead of their Magic level, so draining their Defence also
 * lowers their magic defence.
 */
@Getter
public enum BossDefence
{
	ABYSSAL_PORTAL("Abyssal portal", 176, 176, 0, 60),
	ABYSSAL_SIRE("Abyssal Sire", 250, 200, 0, 20),
	AKKHA("Akkha", 80, 100, 0, 10),
	AKKHAS_SHADOW("Akkha's Shadow", 30, 100, 0, 10),
	ALCHEMICAL_HYDRA("Alchemical Hydra", 100, 260, 0, 150),
	ARTIO("Artio", 150, 90, 0, 0),
	BA_BA("Ba-Ba", 80, 100, 60, 280),
	CALLISTO("Callisto", 225, 140, 0, 0),
	CALVARION("Calvar'ion", 225, 178, 0, 198),
	CERBERUS("Cerberus", 110, 220, 0, 65),
	CHAOS_ELEMENTAL("Chaos Elemental", 270, 270, 0, 70),
	COMMANDER_ZILYANA("Commander Zilyana", 300, 300, 0, 100),
	CORE("<col=00ffff>Core</col>", 0, 0, 0, 0),
	CORPOREAL_BEAST("Corporeal Beast", 310, 350, 0, 150),
	DAGANNOTH_PRIME("Dagannoth Prime", 255, 255, 0, 255),
	DAGANNOTH_REX("Dagannoth Rex", 255, 0, 0, 10),
	DAGANNOTH_SUPREME("Dagannoth Supreme", 128, 255, 0, 255),
	DEATHLY_MAGE("Deathly mage", 155, 210, 0, 0),
	DEATHLY_RANGER("Deathly ranger", 155, 155, 0, 0),
	ELIDINIS_WARDEN("Elidinis' Warden", 150, 190, 120, -30),
	GENERAL_GRAARDOR("General Graardor", 250, 80, 0, 298),
	GIANT_MOLE("Giant Mole", 200, 200, 0, 80),
	GREAT_OLM("Great Olm", 150, 250, 0, 200),
	GREAT_OLM_LEFT_CLAW("Great Olm (Left claw)", 175, 175, 0, 200),
	GREAT_OLM_RIGHT_CLAW("Great Olm (Right claw)", 175, 87, 0, 50),
	ICE_DEMON("Ice Demon", 160, 390, 0, 40, true),
	KALPHITE_QUEEN("Kalphite Queen", 300, 150, 0, 100),
	KEPHRI("Kephri", 80, 125, 60, 200),
	KING_BLACK_DRAGON("King Black Dragon", 240, 240, 0, 80),
	KREE_ARRA("Kree'arra", 260, 200, 0, 200),
	KRIL_TSUTSAROTH("K'ril Tsutsaroth", 270, 200, 0, 80),
	LIZARDMAN_SHAMAN("Lizardman shaman", 210, 130, 0, 50),
	NEX("Nex", 260, 230, 0, 300),
	NYLOCAS_VASILIAS("Nylocas Vasilias", 50, 50, 0, 0),
	OBELISK("<col=00ffff>Obelisk</col>", 100, 0, 60, 0),
	PESTILENT_BLOAT("Pestilent Bloat", 100, 150, 0, 600),
	// Muspah's magic-def bonus is phase-dependent (34 melee, 437 most phases); using the common 437.
	PHANTOM_MUSPAH("Phantom Muspah", 200, 150, 0, 437),
	SARACHNIS("Sarachnis", 150, 150, 0, 150),
	SCORPIA("Scorpia", 180, 1, 0, 44),
	SKELETAL_MYSTIC("Skeletal Mystic", 187, 140, 0, 140),
	SKOTIZO("Skotizo", 200, 280, 0, 80),
	SOTETSEG("Sotetseg", 200, 250, 100, 30),
	SPINDEL("Spindel", 225, 235, 0, 205),
	TEKTON("Tekton", 205, 205, 0, 0),
	TEKTON_ENRAGED("Tekton (enraged)", 205, 205, 0, 0),
	THE_MAIDEN_OF_SUGADINTI("The Maiden of Sugadinti", 200, 350, 0, 0),
	TUMEKENS_WARDEN("Tumeken's Warden", 150, 190, 120, -30),
	TZKAL_ZUK("TzKal-Zuk", 260, 150, 0, 350),
	TZTOK_JAD("TzTok-Jad", 480, 480, 0, 0),
	VASA("Vasa Nistirio", 175, 230, 0, 400),
	VENENATIS("Venenatis", 321, 300, 0, 300),
	VETION("Vet'ion", 395, 300, 0, 250),
	VORKATH("Vorkath", 214, 150, 0, 240),
	XARPUS("Xarpus", 250, 220, 0, 0),
	YAMA("Yama", 225, 250, 145, 0),
	ZEBAK("Zebak", 70, 100, 50, 200),
	// Zulrah's magic-def bonus is form-dependent (-45 serpentine, 0 magma, 300 tanzanite); using 0.
	// A single-value entry can't follow the rotation, so the Eye of ayak drain won't read exactly per form.
	ZULRAH("Zulrah", 300, 300, 0, 0);

	private final String npcName;
	private final double baseDef;
	private final double baseMagic;
	private final double minDef;
	private final double baseMagicDef;
	/** True if the monster rolls magic defence off its Defence level rather than its Magic level. */
	private final boolean magicUsesDefence;

	BossDefence(String npcName, double baseDef, double baseMagic, double minDef, double baseMagicDef)
	{
		this(npcName, baseDef, baseMagic, minDef, baseMagicDef, false);
	}

	BossDefence(String npcName, double baseDef, double baseMagic, double minDef, double baseMagicDef,
		boolean magicUsesDefence)
	{
		this.npcName = npcName;
		this.baseDef = baseDef;
		this.baseMagic = baseMagic;
		this.minDef = minDef;
		this.baseMagicDef = baseMagicDef;
		this.magicUsesDefence = magicUsesDefence;
	}

	public static BossDefence forName(String name)
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

	public static double baseDefenceOf(String name)
	{
		BossDefence boss = forName(name);
		return boss != null ? boss.baseDef : 0;
	}
}
