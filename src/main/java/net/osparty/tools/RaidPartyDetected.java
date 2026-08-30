package net.osparty.tools;

import lombok.Value;
import net.osparty.model.Activity;

/** A raid party the local player just made in-game, with whatever the game said about it. */
@Value
public class RaidPartyDetected
{
	Activity activity;
	/** CM / hard mode when the game said so, false when it said not, null when it never said. */
	Boolean hardMode;
	/** Tombs of Amascut invocation level; 0 elsewhere. */
	int invocation;
	/** The size chosen at the board, or 0 when the game did not offer one. */
	int preferredSize;
	/** The size a Chambers raid is scaled to ("4"), or "" when unscaled or not Chambers. */
	String coxScale;

	/** "HMT", "CoX CM", "ToA (150)" -- the party as the board would title it. */
	public String label()
	{
		return activity.displayName(Boolean.TRUE.equals(hardMode), invocation);
	}
}
