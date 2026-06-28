package net.osparty.combat;

import lombok.Getter;

/**
 * Base combat stats for monsters whose defence can be drained by special attacks,
 * used by {@link DefenceTracker} to compute the live defence level.
 *
 * <p>Data ported from the community "Party Defence Tracker" plugin
 * (github.com/Hyftar/party-defence-tracker, BSD-2). {@code baseDef} is the
 * monster's starting defence level, {@code minDef} the lowest it can be drained
 * to (0 for most).
 */
@Getter
public enum BossDefence
{
	ABYSSAL_PORTAL("Abyssal portal", 176, 176, 0),
	ABYSSAL_SIRE("Abyssal Sire", 250, 200, 0),
	AKKHA("Akkha", 80, 100, 0),
	AKKHAS_SHADOW("Akkha's Shadow", 30, 100, 0),
	ALCHEMICAL_HYDRA("Alchemical Hydra", 100, 260, 0),
	ARTIO("Artio", 150, 90, 0),
	BA_BA("Ba-Ba", 80, 100, 60),
	CALLISTO("Callisto", 225, 140, 0),
	CALVARION("Calvar'ion", 225, 178, 0),
	CERBERUS("Cerberus", 110, 220, 0),
	CHAOS_ELEMENTAL("Chaos Elemental", 270, 270, 0),
	COMMANDER_ZILYANA("Commander Zilyana", 300, 300, 0),
	CORE("<col=00ffff>Core</col>", 0, 0, 0),
	CORPOREAL_BEAST("Corporeal Beast", 310, 350, 0),
	DAGANNOTH_PRIME("Dagannoth Prime", 255, 255, 0),
	DAGANNOTH_REX("Dagannoth Rex", 255, 0, 0),
	DAGANNOTH_SUPREME("Dagannoth Supreme", 128, 244, 0),
	DEATHLY_MAGE("Deathly mage", 155, 210, 0),
	DEATHLY_RANGER("Deathly ranger", 155, 155, 0),
	ELIDINIS_WARDEN("Elidinis' Warden", 150, 190, 120),
	GENERAL_GRAARDOR("General Graardor", 250, 150, 0),
	GIANT_MOLE("Giant Mole", 200, 200, 0),
	GREAT_OLM("Great Olm", 150, 250, 0),
	GREAT_OLM_LEFT_CLAW("Great Olm (Left claw)", 175, 175, 0),
	GREAT_OLM_RIGHT_CLAW("Great Olm (Right claw)", 175, 87, 0),
	ICE_DEMON("Ice Demon", 160, 390, 0),
	KALPHITE_QUEEN("Kalphite Queen", 300, 150, 0),
	KEPHRI("Kephri", 80, 125, 60),
	KING_BLACK_DRAGON("King Black Dragon", 240, 240, 0),
	KREE_ARRA("Kree'arra", 260, 200, 0),
	KRIL_TSUTSAROTH("K'ril Tsutsaroth", 270, 200, 0),
	LIZARDMAN_SHAMAN("Lizardman shaman", 210, 130, 0),
	NEX("Nex", 260, 230, 0),
	NYLOCAS_VASILIAS("Nylocas Vasilias", 50, 50, 0),
	OBELISK("<col=00ffff>Obelisk</col>", 100, 100, 60),
	PESTILENT_BLOAT("Pestilent Bloat", 100, 150, 0),
	PHANTOM_MUSPAH("Phantom Muspah", 200, 150, 0),
	SARACHNIS("Sarachnis", 150, 150, 0),
	SCORPIA("Scorpia", 180, 1, 0),
	SKELETAL_MYSTIC("Skeletal Mystic", 187, 140, 0),
	SKOTIZO("Skotizo", 200, 280, 0),
	SOTETSEG("Sotetseg", 200, 250, 100),
	SPINDEL("Spindel", 225, 235, 0),
	TEKTON("Tekton", 205, 205, 0),
	TEKTON_ENRAGED("Tekton (enraged)", 205, 205, 0),
	THE_MAIDEN_OF_SUGADINTI("The Maiden of Sugadinti", 200, 350, 0),
	TUMEKENS_WARDEN("Tumeken's Warden", 150, 190, 120),
	TZKAL_ZUK("TzKal-Zuk", 260, 150, 0),
	TZTOK_JAD("TzTok-Jad", 480, 480, 0),
	VASA("Vasa Nistirio", 175, 230, 0),
	VENENATIS("Venenatis", 321, 300, 0),
	VETION("Vet'ion", 395, 300, 0),
	VORKATH("Vorkath", 214, 150, 0),
	XARPUS("Xarpus", 250, 220, 0),
	YAMA("Yama", 225, 250, 145),
	ZEBAK("Zebak", 70, 100, 50),
	ZULRAH("Zulrah", 300, 300, 0);

	private final String npcName;
	private final double baseDef;
	private final double baseMagic;
	private final double minDef;

	BossDefence(String npcName, double baseDef, double baseMagic, double minDef)
	{
		this.npcName = npcName;
		this.baseDef = baseDef;
		this.baseMagic = baseMagic;
		this.minDef = minDef;
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
