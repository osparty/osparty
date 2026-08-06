package net.osparty.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One member as recorded in a {@link PartyHistoryEntry}: their display {@code name}, their
 * {@code playerId} (as in {@link net.osparty.model.Member} — a public, non-reversible id, stable across
 * a rename), and the times they were seen to join and leave the party <em>while the local player was in
 * it</em>.
 *
 * <p>{@code accountHash} is kept on the class only so a file written before {@code playerId} existed
 * still deserialises; nothing here writes a non-zero value into it any more, and {@link PartyHistoryService}
 * zeroes any it finds in an on-disk file the moment it loads one (see {@code PartyHistoryService#load}).
 * History used to be the one place the raw account hash of everyone you had ever partied with sat in
 * plaintext on disk indefinitely — worth clearing even though it was never sent anywhere from here, since
 * a compromised machine could otherwise read it off this file for every player it names.
 *
 * <p>Unlike the live {@link net.osparty.model.Member}, members here are never deleted when they
 * leave — they are flagged instead, so the history keeps a record of everyone who passed through.
 * A member is considered {@linkplain #isPresent() present} while {@link #leftAt} is {@code 0}; a
 * non-zero {@code leftAt} is the epoch-millis moment we first saw them gone. {@code joinedAt} is
 * when we first saw them (approximated to the party's record time for the initial roster, and for
 * rows migrated from the pre-timestamp on-disk format where it is {@code 0}).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HistoryMember
{
	private String name;
	/** @deprecated read-only, for rows written before {@link #playerId} existed; see the class doc. */
	@Deprecated
	private long accountHash;

	/** Epoch millis we first observed this member; {@code 0} means "unknown / party start". */
	private long joinedAt;

	/** Epoch millis we first observed this member gone; {@code 0} means still present. */
	private long leftAt;

	/** This member's public, non-reversible id, or {@code null} when the source didn't have one. */
	private String playerId;

	public boolean isPresent()
	{
		return leftAt == 0;
	}
}
