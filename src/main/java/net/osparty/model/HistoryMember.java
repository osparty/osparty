package net.osparty.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One member as recorded in a {@link PartyHistoryEntry}: their display {@code name} and stable
 * {@code accountHash} (as in {@link net.osparty.model.Member}), plus the times they were seen to
 * join and leave the party <em>while the local player was in it</em>.
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
	private long accountHash;

	/** Epoch millis we first observed this member; {@code 0} means "unknown / party start". */
	private long joinedAt;

	/** Epoch millis we first observed this member gone; {@code 0} means still present. */
	private long leftAt;

	/** @return true while this member is still in the party (has not been seen to leave). */
	public boolean isPresent()
	{
		return leftAt == 0;
	}
}
