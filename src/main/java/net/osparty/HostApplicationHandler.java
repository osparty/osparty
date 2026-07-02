package net.osparty;

import net.osparty.model.Activity;
import net.osparty.model.Applicant;
import java.util.List;

/**
 * Lets the host-facing UI mirror incoming applications in-game: an overlay
 * listing every pending applicant, and one-off chatbox pings as they arrive or
 * are resolved.
 */
public interface HostApplicationHandler
{
	void setPendingApplicants(List<Applicant> applicants, Activity activity);

	void announceApplicant(Applicant applicant, Activity activity);

	void announceResolved(Applicant applicant, Activity activity, boolean accepted);

	/** A block-listed applicant was auto-declined; post a chat line so the host knows. */
	void announceAutoDeclinedBlocked(Applicant applicant, Activity activity);
}
