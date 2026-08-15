package net.osparty.tools;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.osparty.OSPartyConfig;
import net.osparty.model.Activity;
import net.osparty.party.LivePartyBackend;
import net.osparty.party.PlayerNames;
import net.runelite.api.Client;
import net.runelite.api.FriendsChatManager;
import net.runelite.api.Player;

/**
 * Notices when the player is standing in a group that has not been advertised, and answers two questions
 * about it for the rest of the plugin.
 *
 * <p>Most groups are not formed on the board. They are formed in a Discord, and what reaches the game is
 * "join my friends chat, world 330" — after which the party is standing together at an activity with
 * nothing hosted anywhere. Hosting is then a second organising step for people who already have their
 * group, so it does not happen, and the plugin has nothing to offer them.
 *
 * <p>This is entirely local. Nothing here is sent anywhere: it reads the friends chat, the loaded map
 * regions and the players in the scene, and publishes two answers from them.
 *
 * <ul>
 *   <li><b>Is there a group worth hosting a party for?</b> — a friends chat we own, at an activity, with
 *       someone from that chat on screen. {@link #setOnSuggest} fires and the panel offers to start one.
 *       Only the chat's owner is asked, because prompting everyone standing there would produce several
 *       competing parties for one group.</li>
 *   <li><b>Is this player standing with us?</b> — {@link #isStandingWith}, which is what lets a host accept
 *       applicants out of their own chat without inspecting each one by hand.</li>
 * </ul>
 *
 * <p>{@link #tick()} runs on the client thread; the answers are published as volatile snapshots because the
 * panel reads them on the EDT.
 */
@Slf4j
@Singleton
public class GroupDetector
{
	private final Client client;
	private final OSPartyConfig config;
	private final LivePartyBackend liveParty;

	/**
	 * Groups the player has waved away, so a dismissed suggestion does not come back on the next tick. Held
	 * for the session rather than persisted: a friends chat at an activity is over long before the client is.
	 * Concurrent because the dismissal arrives from the panel while {@link #tick()} reads it.
	 */
	private final Set<String> dismissed = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** The group we last told anyone about, so the suggestion fires on the change rather than every tick. */
	private volatile Group suggested;
	/** Everyone from our friends chat our own scene can see, normalised. Read on the EDT. */
	private volatile Set<String> standingWith = Set.of();

	private volatile Consumer<Group> onSuggest = group -> { };
	private volatile Runnable onGone = () -> { };

	@Inject
	GroupDetector(Client client, OSPartyConfig config, LivePartyBackend liveParty)
	{
		this.client = client;
		this.config = config;
		this.liveParty = liveParty;
	}

	/** A group standing together in the game: what they are at, and the friends chat they are organised in. */
	@Getter
	public static final class Group
	{
		private final Activity activity;
		private final String friendsChat;
		/** How many of the chat are on screen with us, which is as much of the group as we can see. */
		private final int standingWith;

		Group(Activity activity, String friendsChat, int standingWith)
		{
			this.activity = activity;
			this.friendsChat = friendsChat;
			this.standingWith = standingWith;
		}

		/** The key a dismissal is remembered under: this chat, at this activity. */
		String key()
		{
			return activity.getId() + '@' + PlayerNames.normalize(friendsChat);
		}
	}

	/** Called when a group appears that has no party, so the panel can offer to start one. EDT-marshalled. */
	public void setOnSuggest(Consumer<Group> onSuggest)
	{
		this.onSuggest = onSuggest == null ? group -> { } : onSuggest;
	}

	/** Called when that group is gone — the chat, the activity, or the people in it. */
	public void setOnGone(Runnable onGone)
	{
		this.onGone = onGone == null ? () -> { } : onGone;
	}

	/**
	 * Re-read what is around us. Cheap enough for every tick: the reads are all client-side, and the
	 * callbacks only fire when the answer changes.
	 */
	public void tick()
	{
		standingWith = readStandingWith();

		Group group = suggestable();
		Group last = suggested;
		if (group == null)
		{
			if (last != null)
			{
				suggested = null;
				onGone.run();
			}
			return;
		}
		if (last != null && last.key().equals(group.key()))
		{
			return; // same group; the offer is already on screen
		}
		suggested = group;
		log.debug("Group detected: {} at {} ({} standing with us)",
			group.getFriendsChat(), group.getActivity().getId(), group.getStandingWith());
		onSuggest.accept(group);
	}

	/**
	 * Whether {@code name} is in our friends chat and in our scene right now — the two things that, taken
	 * together, mean this is someone we are already standing at the activity with.
	 *
	 * <p>Answered from the last tick's snapshot so the panel can ask on the EDT. A tick out of date is the
	 * right resolution for a question about who is standing next to you.
	 */
	public boolean isStandingWith(String name)
	{
		if (name == null || name.isEmpty())
		{
			return false;
		}
		return standingWith.contains(PlayerNames.normalize(name));
	}

	/** How many of our friends chat are on screen with us. */
	public int standingWithCount()
	{
		return standingWith.size();
	}

	/** Wave away the current suggestion, and do not offer this group again this session. */
	public void dismissCurrent()
	{
		Group group = suggested;
		if (group != null)
		{
			dismissed.add(group.key());
			suggested = null;
		}
		onGone.run();
	}

	/** Forget the dismissals; a fresh login is a fresh session as far as this is concerned. */
	public void reset()
	{
		dismissed.clear();
		suggested = null;
		standingWith = Set.of();
	}

	/**
	 * The group we should be offering to host a party for, or null when there is nothing to offer: the
	 * setting is off, we are already in a party, we are not in a friends chat of our own at an activity, or
	 * nobody from it is actually here.
	 */
	private Group suggestable()
	{
		if (!config.suggestParty() || liveParty.isInParty())
		{
			return null;
		}
		String owner = friendsChatOwner();
		Player local = client.getLocalPlayer();
		if (owner == null || local == null || local.getName() == null)
		{
			return null;
		}
		// Only the chat's owner is asked. In a group put together on Discord that is the person who organised
		// it, and prompting the other four would offer four more parties for the group they are already in.
		if (!PlayerNames.normalize(owner).equals(PlayerNames.normalize(local.getName())))
		{
			return null;
		}
		Activity activity = Activity.nearby(client.getMapRegions());
		if (activity == null)
		{
			return null;
		}
		int here = standingWith.size();
		if (here == 0)
		{
			// A chat open while walking past an activity is not a group. Waiting for someone from it to be on
			// screen costs nothing and keeps the offer for the times it is actually one.
			return null;
		}
		Group group = new Group(activity, owner, here);
		return dismissed.contains(group.key()) ? null : group;
	}

	/**
	 * The players in our scene who are also in our friends chat, normalised.
	 *
	 * <p>The friends-chat test is the game's own ({@code Player#isFriendsChatMember}), so this never has to
	 * walk the channel's member list, and a stranger standing next to us is not part of the answer.
	 */
	private Set<String> readStandingWith()
	{
		if (friendsChatOwner() == null)
		{
			return Set.of();
		}
		Player local = client.getLocalPlayer();
		Set<String> names = new HashSet<>();
		for (Player player : client.getPlayers())
		{
			if (player == null || player == local || !player.isFriendsChatMember())
			{
				continue;
			}
			String name = player.getName();
			if (name != null && !name.isEmpty())
			{
				names.add(PlayerNames.normalize(name));
			}
		}
		return names;
	}

	private String friendsChatOwner()
	{
		FriendsChatManager friendsChat = client.getFriendsChatManager();
		if (friendsChat == null)
		{
			return null;
		}
		String owner = friendsChat.getOwner();
		return owner == null || owner.isEmpty() ? null : owner;
	}
}
