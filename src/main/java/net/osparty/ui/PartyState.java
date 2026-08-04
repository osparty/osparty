package net.osparty.ui;

import net.osparty.OSPartyConfig;
import net.osparty.model.Advertisement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.runelite.client.config.ConfigManager;

/**
 * Single source of truth for the one party the player is in (host or member), held as the
 * {@link Advertisement} that describes it. Also holds the persisted host credential sent on
 * host-only mutations, and the marker that lets a joined party be resumed after a restart the way a
 * hosted one already is. EDT-only, so no synchronisation.
 */
class PartyState
{
	private static final String KEY_HOST_KEY = "hostKey";
	private static final String KEY_HOST_KEY_AD = "hostKeyPartyId";
	/** Persisted with the credential so a resumed host keeps advertising the CoX layout. */
	private static final String KEY_ADVERTISE_LAYOUT = "hostAdvertiseLayout";

	/** The party we were last an admitted member of, and what we were in it. See {@link #rememberMembership}. */
	private static final String KEY_MEMBER_PARTY = "memberPartyId";
	private static final String KEY_MEMBER_CODE = "memberPartyCode";
	private static final String KEY_MEMBER_ROLE = "memberPartyRole";
	private static final String KEY_MEMBER_LEARNER = "memberPartyLearner";
	private static final String KEY_MEMBER_ACCOUNT = "memberPartyAccount";
	private static final String KEY_MEMBER_SEEN = "memberPartySeenAt";

	/**
	 * How long after we were last in a party we will still put ourselves back into it. The same window a
	 * host has, because it is the same one: an advertisement outlives the client that hosts it by its TTL,
	 * and a party nobody is advertising any more is not one to go back to.
	 */
	private static final long RESUME_WINDOW_MS = 90_000;
	/** How often the marker's clock is rewritten while the party runs; the rest only when it changes. */
	private static final long RESUME_TOUCH_MS = 10_000;

	private final ConfigManager configManager;

	private Advertisement currentAd;
	private boolean host;
	private boolean advertiseLayout;
	/** Secret authorising host-only API mutations for the party we host; null otherwise. */
	private String hostKey;
	/** What the persisted membership marker says, so touching it rewrites the clock and nothing else. */
	private String memberAdId;
	private String memberRole;
	private boolean memberLearner;
	private long memberSeenAt;
	private final List<Runnable> listeners = new ArrayList<>();

	PartyState(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	Advertisement getCurrentAd()
	{
		return currentAd;
	}

	/** @return the host credential for the party we host, or null when not hosting / unknown. */
	String getHostKey()
	{
		return hostKey;
	}

	boolean isHost()
	{
		return host;
	}

	boolean isAdvertiseLayout()
	{
		return advertiseLayout;
	}

	void setAdvertiseLayout(boolean advertiseLayout)
	{
		this.advertiseLayout = advertiseLayout;
		// Persisted so resumeHosting() can restore it after a restart.
		configManager.setConfiguration(OSPartyConfig.GROUP, KEY_ADVERTISE_LAYOUT, advertiseLayout);
	}

	boolean isInParty()
	{
		return currentAd != null;
	}

	void addListener(Runnable listener)
	{
		listeners.add(listener);
	}

	/** Host a freshly created party with its new credential, persisting it for resume. */
	void setHosting(Advertisement ad, String hostKey)
	{
		currentAd = ad;
		host = true;
		this.hostKey = hostKey;
		configManager.setConfiguration(OSPartyConfig.GROUP, KEY_HOST_KEY_AD, ad.getId());
		configManager.setConfiguration(OSPartyConfig.GROUP, KEY_HOST_KEY, hostKey);
		// A host resumes from its own advertisement; the membership marker would only compete with it.
		forgetMembership();
		fire();
	}

	/** Resume hosting after a restart, recovering the saved credential for this ad. */
	void resumeHosting(Advertisement ad)
	{
		currentAd = ad;
		host = true;
		this.hostKey = loadHostKey(ad.getId());
		forgetMembership();
		// Restore the layout-advertising choice, but only when the saved key is for this ad.
		this.advertiseLayout = hostKey != null && Boolean.parseBoolean(
			configManager.getConfiguration(OSPartyConfig.GROUP, KEY_ADVERTISE_LAYOUT));
		fire();
	}

	private String loadHostKey(String adId)
	{
		String savedAd = configManager.getConfiguration(OSPartyConfig.GROUP, KEY_HOST_KEY_AD);
		if (adId != null && adId.equals(savedAd))
		{
			return configManager.getConfiguration(OSPartyConfig.GROUP, KEY_HOST_KEY);
		}
		return null;
	}

	void setMember(Advertisement ad)
	{
		currentAd = ad;
		host = false;
		advertiseLayout = false;
		hostKey = null;
		forgetHostKey();
		fire();
	}

	/**
	 * Note that we are an admitted member of {@code ad}, so a client that goes away mid-party can put us
	 * back into it on the next login instead of leaving us to apply again and wait on the host.
	 *
	 * <p>Called repeatedly while the party runs, because what a resume is measured against is the last
	 * moment we were in it — a client that dies leaves the marker stamped with when it was last alive.
	 * Only an admitted member is worth remembering: a pending applicant that resumed would be claiming an
	 * admission the host never gave it.
	 */
	void rememberMembership(Advertisement ad, String role, boolean learner, long accountHash)
	{
		if (ad == null || ad.getId() == null || ad.getInviteCode() == null || accountHash == -1L)
		{
			return;
		}
		long now = System.currentTimeMillis();
		boolean same = ad.getId().equals(memberAdId) && Objects.equals(role, memberRole)
			&& learner == memberLearner;
		if (same && now - memberSeenAt < RESUME_TOUCH_MS)
		{
			return;
		}
		if (!same)
		{
			memberAdId = ad.getId();
			memberRole = role;
			memberLearner = learner;
			configManager.setConfiguration(OSPartyConfig.GROUP, KEY_MEMBER_PARTY, ad.getId());
			configManager.setConfiguration(OSPartyConfig.GROUP, KEY_MEMBER_CODE, ad.getInviteCode());
			configManager.setConfiguration(OSPartyConfig.GROUP, KEY_MEMBER_ROLE, role == null ? "" : role);
			configManager.setConfiguration(OSPartyConfig.GROUP, KEY_MEMBER_LEARNER, learner);
			configManager.setConfiguration(OSPartyConfig.GROUP, KEY_MEMBER_ACCOUNT, Long.toString(accountHash));
		}
		memberSeenAt = now;
		configManager.setConfiguration(OSPartyConfig.GROUP, KEY_MEMBER_SEEN, Long.toString(now));
	}

	/**
	 * The party this account was in moments ago, or null when there is nothing to go back to: no marker,
	 * one another account left behind, or one old enough that we are no longer coming straight back.
	 */
	Membership savedMembership(long accountHash)
	{
		String partyId = configManager.getConfiguration(OSPartyConfig.GROUP, KEY_MEMBER_PARTY);
		String code = configManager.getConfiguration(OSPartyConfig.GROUP, KEY_MEMBER_CODE);
		if (partyId == null || partyId.isEmpty() || code == null || code.isEmpty())
		{
			return null;
		}
		// Whoever was logged in when the marker was written is the only one it speaks for; an alt logging
		// in next must not be dropped into somebody else's party.
		if (accountHash == -1L
			|| accountHash != parseLong(configManager.getConfiguration(OSPartyConfig.GROUP, KEY_MEMBER_ACCOUNT)))
		{
			return null;
		}
		if (System.currentTimeMillis()
			- parseLong(configManager.getConfiguration(OSPartyConfig.GROUP, KEY_MEMBER_SEEN)) > RESUME_WINDOW_MS)
		{
			forgetMembership();
			return null;
		}
		String role = configManager.getConfiguration(OSPartyConfig.GROUP, KEY_MEMBER_ROLE);
		return new Membership(partyId, code, role == null || role.isEmpty() ? null : role,
			Boolean.parseBoolean(configManager.getConfiguration(OSPartyConfig.GROUP, KEY_MEMBER_LEARNER)));
	}

	/** Step down from host to member after transferring the party; drops and unpersists the host key. */
	void demoteToMember(Advertisement ad)
	{
		setMember(ad);
	}

	/** Replace the current ad (e.g. after a roster change), keeping the role. */
	void update(Advertisement ad)
	{
		currentAd = ad;
		fire();
	}

	void clear()
	{
		currentAd = null;
		host = false;
		advertiseLayout = false;
		hostKey = null;
		forgetHostKey();
		// Every way out of a party comes through here — left, kicked, disbanded, or swapped for another
		// one — and none of them is a party to be put back into.
		forgetMembership();
		fire();
	}

	/** Drop the persisted host credential so a party we no longer host can't be resumed later. */
	private void forgetHostKey()
	{
		configManager.unsetConfiguration(OSPartyConfig.GROUP, KEY_HOST_KEY);
		configManager.unsetConfiguration(OSPartyConfig.GROUP, KEY_HOST_KEY_AD);
		configManager.unsetConfiguration(OSPartyConfig.GROUP, KEY_ADVERTISE_LAYOUT);
	}

	/** Drop the membership marker, so a party we are out of is not one we come back to. */
	private void forgetMembership()
	{
		memberAdId = null;
		memberRole = null;
		memberLearner = false;
		memberSeenAt = 0;
		configManager.unsetConfiguration(OSPartyConfig.GROUP, KEY_MEMBER_PARTY);
		configManager.unsetConfiguration(OSPartyConfig.GROUP, KEY_MEMBER_CODE);
		configManager.unsetConfiguration(OSPartyConfig.GROUP, KEY_MEMBER_ROLE);
		configManager.unsetConfiguration(OSPartyConfig.GROUP, KEY_MEMBER_LEARNER);
		configManager.unsetConfiguration(OSPartyConfig.GROUP, KEY_MEMBER_ACCOUNT);
		configManager.unsetConfiguration(OSPartyConfig.GROUP, KEY_MEMBER_SEEN);
	}

	private void fire()
	{
		for (Runnable listener : listeners)
		{
			listener.run();
		}
	}

	private static long parseLong(String value)
	{
		try
		{
			return value == null ? 0 : Long.parseLong(value.trim());
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	/** Enough of a party we were in to go back into it: which one, and what we were in it. */
	static final class Membership
	{
		private final String partyId;
		private final String inviteCode;
		private final String role;
		private final boolean learner;

		Membership(String partyId, String inviteCode, String role, boolean learner)
		{
			this.partyId = partyId;
			this.inviteCode = inviteCode;
			this.role = role;
			this.learner = learner;
		}

		String getPartyId()
		{
			return partyId;
		}

		String getInviteCode()
		{
			return inviteCode;
		}

		String getRole()
		{
			return role;
		}

		boolean isLearner()
		{
			return learner;
		}
	}
}
