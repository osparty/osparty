package net.osparty.party;

import lombok.Value;
import net.runelite.api.vars.AccountType;

/**
 * One line of party chat. A peer's is rebuilt from an inbound frame by the live-party backend, under the
 * name the server holds for them rather than one the frame could claim; our own is posted by
 * {@link LivePartyBackend#sendChat} once the frame has gone out, since the server never echoes a sender
 * its own line. Posted on RuneLite's {@code EventBus}, where {@code PartyChat} puts it in the chatbox.
 */
@Value
public class PartyChatEvent
{
	/** Who said it — ours when {@link LivePartyBackend#isForLocalMember} says so. */
	long memberId;
	String name;
	/** As their live snapshot reports it, for the icon in front of their name; null before one has arrived. */
	AccountType accountType;
	String text;
}
