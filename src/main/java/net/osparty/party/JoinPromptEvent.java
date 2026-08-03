package net.osparty.party;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Host -> member prompt telling a specific peer how to actually join the raid:
 * join the host's friends chat (CoX), apply on the Theatre of Blood notice board,
 * or apply on the Grouping Obelisk (ToA). The target client shows it as a brief,
 * self-dismissing in-game popup. It cannot move anyone.
 *
 * <p>Rebuilt from an inbound frame by the live-party backend and posted on RuneLite's {@code EventBus},
 * where the plugin's popup handler picks it up.
 */
@Data
@NoArgsConstructor
public class JoinPromptEvent
{
	/** The host that sent this, stamped by the backend from the frame's sender. */
	private long memberId;

	/** Ignored by everyone but this member. */
	private long targetMemberId;

	private String hostName;

	/** "FC" (default), "NOTICE_BOARD" or "OBELISK". */
	private String kind;

	/** The friends-chat name (only for kind "FC"). */
	private String friendsChat;
}
