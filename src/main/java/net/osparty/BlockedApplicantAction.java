package net.osparty;

/**
 * What the host's client does when a player on the block list applies to their party.
 * Configurable via {@link OSPartyConfig#blockedApplicantAction()}.
 */
public enum BlockedApplicantAction
{
	/** Still show the applicant, but flagged as blocked so the host decides. */
	WARN("Warn (don't reject)"),
	/** Automatically decline, and post a chat line so the host knows. */
	REJECT_NOTIFY("Auto-reject + notify"),
	/** Automatically decline with no message. */
	REJECT_SILENT("Auto-reject silently");

	private final String label;

	BlockedApplicantAction(String label)
	{
		this.label = label;
	}

	public boolean rejects()
	{
		return this == REJECT_NOTIFY || this == REJECT_SILENT;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
