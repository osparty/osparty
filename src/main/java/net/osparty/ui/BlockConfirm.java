package net.osparty.ui;

import java.awt.Component;
import net.osparty.service.BlockListService;
import net.osparty.service.FavoritesService;

/**
 * Shared confirmation for blocking a player. Blocking is a rare, easily-misclicked action with
 * several side effects (parties hidden from Search, applicant handling, favourite cleared), so
 * every block entry point routes through here first. Unblocking is not confirmed.
 */
final class BlockConfirm
{
	private BlockConfirm()
	{
	}

	/**
	 * Ask the user to confirm blocking {@code name}, spelling out the consequences.
	 *
	 * @return true if the user confirmed and the caller should proceed with the block.
	 */
	static boolean confirm(Component parent, String name)
	{
		String who = ConfirmDialog.escape(name == null ? "this player" : name);
		String body = "Block <b>" + who + "</b>?<br><br>"
			+ "While they are blocked:"
			+ "<ul style='margin-top:2px;margin-left:14px'>"
			+ "<li>Their parties are hidden from Search (unless <i>Show blocked parties</i> is turned on).</li>"
			+ "<li>If they apply to a party you host you'll be warned, or they'll be auto-declined, "
			+ "per your <i>Blocked applicant</i> setting.</li>"
			+ "<li>They're removed from your favourites (a player can't be both).</li>"
			+ "</ul>"
			+ "You can undo this any time from the <b>Blocked</b> tab.";

		return ConfirmDialog.ask(parent, "Block " + (name == null ? "player" : name), body);
	}

	/**
	 * Toggle {@code rsn}'s block state: confirm first when blocking, and drop a conflicting favourite.
	 *
	 * @return true when the state changed, false when the user cancelled the confirmation.
	 */
	static boolean toggle(Component parent, BlockListService blockList, FavoritesService favorites,
		String playerId, String rsn)
	{
		boolean wasBlocked = blockList.isBlocked(playerId, rsn);
		// Confirm the consequences before blocking, but let unblocking happen instantly.
		if (!wasBlocked && !confirm(parent, rsn))
		{
			return false;
		}
		blockList.toggle(playerId, rsn);
		if (!wasBlocked && favorites != null && favorites.isFavorite(playerId, rsn))
		{
			favorites.toggle(playerId, rsn); // blocking and favouriting are mutually exclusive
		}
		return true;
	}
}
