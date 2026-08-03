package net.osparty.party;

/**
 * A member's standing in the live party.
 *
 * <p>{@link #PENDING} is an applicant the host has not yet admitted: they hold a connection and can be
 * seen, but they are not in the party and nothing they send fans out to anyone.
 */
public enum PartyStatus
{
	HOST, MEMBER, PENDING
}
