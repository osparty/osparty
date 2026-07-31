package net.osparty.api;

import net.osparty.model.Advertisement;

/** A party invite pushed to us by the backend: the ad to join and who sent it. */
public final class PartyInvite
{
	private final Advertisement ad;
	private final String fromName;

	public PartyInvite(Advertisement ad, String fromName)
	{
		this.ad = ad;
		this.fromName = fromName;
	}

	public Advertisement getAd()
	{
		return ad;
	}

	/** The player who invited us (host name when the sender didn't identify). May be null. */
	public String getFromName()
	{
		return fromName;
	}
}
