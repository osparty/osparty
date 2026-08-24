package net.osparty.ui;

import java.util.List;
import net.osparty.model.Advertisement;

/**
 * What to do about parties already running the thing the player is about to advertise. Raised on the
 * way through a create, so someone is waiting on the answer and all three of them finish the click.
 *
 * <p>The Create tab decides what each answer does; the plugin only decides where to ask.
 */
public interface SimilarParties
{
	/** The parties worth joining instead, closest to full first. Never empty. */
	List<Advertisement> matches();

	/** Apply to {@code ad} and abandon the create. The host still approves, as any application is. */
	void requestJoin(Advertisement ad, RoleChooser chooser);

	/** Advertise anyway. */
	void createAnyway();

	/** Advertise, and stop looking for similar parties on future creates. */
	void createAndStopAsking();
}
