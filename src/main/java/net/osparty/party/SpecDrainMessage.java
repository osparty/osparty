package net.osparty.party;

import lombok.EqualsAndHashCode;
import lombok.Value;
import net.osparty.enums.SpecWeapon;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Broadcast whenever the local player lands a defence-draining special attack,
 * so every member's defence tracker reflects the whole party's draining rather
 * than only our own. Mirrors the shape of RuneLite Special Attack Counter's
 * update but is carried over OSParty's own party bus, so we don't depend on that
 * plugin. The sender's {@code memberId} and target {@code world} let receivers
 * drop their own echo and specs from other worlds.
 */
@Value
@EqualsAndHashCode(callSuper = true)
public class SpecDrainMessage extends PartyMemberMessage
{
	int npcIndex;
	SpecWeapon weapon;
	int hit;
	int world;
}
