package net.osparty.enums;

import net.osparty.OSPartyConfig;

/**
 * What the host's client does when a player on the block list applies to their party.
 * Configurable via {@link OSPartyConfig#blockedApplicantAction()}.
 */
public enum BlockedApplicantAction
{
	WARN("Warn (don't reject)"),
	REJECT_NOTIFY("Auto-reject + notify"),
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
