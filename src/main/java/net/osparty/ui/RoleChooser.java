package net.osparty.ui;

import java.util.List;
import java.util.function.Consumer;
import net.osparty.model.Activity;
import net.osparty.model.Advertisement;
import net.osparty.model.Role;

/**
 * Asks the player which role they'll fill on the way into a party, wherever they answered from.
 * The join flow hands off to one of these instead of popping its own desktop dialog, so an invite
 * accepted in-game can be finished in-game.
 *
 * <p>Answering is asynchronous and may land on any thread: {@code onPicked} takes the chosen
 * {@link Role#getId()}, or {@code null} when the player backed out.
 */
public interface RoleChooser
{
	void choose(Advertisement ad, Activity activity, List<Role> options, Consumer<String> onPicked);

	/**
	 * There is no role to settle after all, because the join needs none or never got that far. Tear
	 * down whatever the chooser is showing.
	 */
	void dismiss();
}
