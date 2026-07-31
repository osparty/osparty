package net.osparty.party;

import lombok.Value;
import net.osparty.enums.SpecWeapon;

/**
 * Broadcast whenever a party member lands a defence-draining special attack, so every member's defence
 * tracker reflects the whole party's draining rather than only their own. Mirrors the shape of RuneLite
 * Special Attack Counter's update but is carried over OSParty's own live socket, so we don't depend on
 * that plugin. The sender's {@link #memberId} and target {@link #world} let receivers drop their own echo
 * and specs from other worlds.
 *
 * <p>Rebuilt from an inbound frame by the live-party backend and posted on RuneLite's {@code EventBus},
 * which is how {@code SpecialAttackTracker} still receives it unchanged.
 */
@Value
public class SpecDrainEvent
{
	/**
	 * The member that landed the spec, stamped by the backend from the frame's sender. A constructor
	 * parameter rather than a setter because this is a value type — it used to inherit a mutable
	 * {@code memberId} from RuneLite's party-message base class, which no longer applies.
	 */
	long memberId;

	int npcIndex;
	SpecWeapon weapon;
	int hit;
	int world;
}
