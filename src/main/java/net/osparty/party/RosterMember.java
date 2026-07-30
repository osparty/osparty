package net.osparty.party;

import lombok.Value;

/**
 * One seat in the live party, as the UI reads it: who they are, where they stand, and the last self-report
 * they sent.
 *
 * <p>{@link #data} is null until they have sent one — a member is seated the moment the owner admits them,
 * which is before their first live update arrives.
 */
@Value
public class RosterMember
{
	long memberId;
	String name;
	PartyStatus status;
	PlayerUpdate data; // nullable until they sync
	boolean local;
	boolean online; // recently heard from (or ourselves)
}
