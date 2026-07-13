package net.osparty.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.osparty.service.PartyHistoryService;

/**
 * One row of the local party history: a party the player was part of, recorded the
 * moment they entered it (whether they hosted it or joined as a member). Persisted as
 * JSON by {@link PartyHistoryService}, so it stays a plain data holder — the display
 * title is derived from {@link net.osparty.model.Activity} at render time.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PartyHistoryEntry
{
	/** The advertised party id, used to de-duplicate a party across restarts. May be {@code null}. */
	private String partyId;

	/** Activity id (see {@link net.osparty.model.Activity#getId()}). */
	private String activity;

	/** The party host's name (the player's own name when {@link #hosted}). */
	private String host;

	/** True when the player hosted this party; false when they joined as a member. */
	private boolean hosted;

	/** Currently-present member count (updated as members join/leave) and the party's capacity. */
	private int size;
	private int capacity;

	/** Difficulty hints used only for the display title (CM/HM, or ToA invocation). */
	private boolean hardMode;
	private int invocation;

	/** Epoch millis when the player entered the party. */
	private long joinedAt;

	/**
	 * Everyone who was in the party while the local player was (host first, then in the order they
	 * were first seen). Members who leave are flagged via {@link HistoryMember#getLeftAt()} rather
	 * than removed, so this is a running record rather than a point-in-time snapshot. May be
	 * {@code null} or empty for older history rows written before members were captured.
	 */
	private List<HistoryMember> members;
}
