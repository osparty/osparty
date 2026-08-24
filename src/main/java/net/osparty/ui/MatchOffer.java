package net.osparty.ui;

import java.util.function.Consumer;
import net.osparty.model.Advertisement;

/**
 * One party put in front of a player who has <em>Find me a party</em> on, and the three things they
 * can do about it. The Search tab decides what to offer and what each answer means; the plugin only
 * decides where to show it, so the in-game card and the sidebar banner drive the same offer.
 */
public interface MatchOffer
{
	Advertisement ad();

	/**
	 * Apply to it. Not an invite — the host still approves, exactly as if the player had clicked Apply
	 * on the card in the panel. {@code chooser} answers the role question wherever they accepted.
	 */
	void join(RoleChooser chooser, Consumer<String> status);

	/** Not this one. Remembered for the session, so it is never offered again. */
	void dismiss();

	/** Stop looking altogether. */
	void stopLooking();
}
