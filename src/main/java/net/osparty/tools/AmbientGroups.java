package net.osparty.tools;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
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
 * Finds the group the player is already in, so the party tools work for one nobody advertised.
 *
 * <p>Most groups are not formed on OSParty's board. They are formed in a Discord, and what reaches the game
 * is "join my friends chat, world 330" — after which everyone involved is standing together at an activity,
 * in one friends chat, with nothing on the board to say so. This watches for exactly that shape and attends
 * the <em>ambient room</em> for it: a live party keyed by the friends chat and the activity, which anyone
 * else running OSParty in the same group derives independently and lands in without a word being passed
 * between them.
 *
 * <p>The room key is derived, not issued, so it is not a secret — anyone who can join the friends chat can
 * compute it. That is why it grants nothing on its own: the server seats an attendee only once another
 * attendee reports standing next to it and it reports the same back, and until then it is sent no live state
 * at all. Standing there is the thing this cannot fake, because the only players a client ever reports are
 * the ones its own game scene can see.
 *
 * <p>What leaves this machine is therefore names, and only of players who are in the same friends chat as
 * the local player <em>and</em> in the same scene. A bystander is never named, and neither is anyone in the
 * friends chat who is somewhere else. Client thread only; {@link #tick()} is driven from the plugin's game
 * tick.
 */
@Slf4j
@Singleton
public class AmbientGroups
{
	/**
	 * How many players one report may name. Comfortably above any activity's party and below the server's own
	 * bound, so a client is never the reason a report is truncated in a group of a plausible size.
	 */
	static final int MAX_SIGHTED = 24;

	/**
	 * Bounds on the room's capacity, matching what the server clamps it to. The ceiling exists because an
	 * activity's maximum party size is sometimes an ambition rather than a group — Chambers of Xeric allows a
	 * hundred — and a room is not the right place to put a hundred people who merely share a friends chat.
	 */
	private static final int MIN_CAPACITY = 2;
	private static final int MAX_CAPACITY = 32;

	/**
	 * Bumped if the derivation ever changes. Two plugin versions computing different keys for one group would
	 * quietly split it into two rooms that cannot see each other, which looks exactly like the feature not
	 * working; a version tag at least makes that a clean break rather than a partial one.
	 */
	private static final String KEY_VERSION = "osparty:ambient:v1:";

	private final Client client;
	private final OSPartyConfig config;
	private final LivePartyBackend liveParty;

	/**
	 * Rooms the player has stepped out of, so leaving one does not simply rejoin it on the next tick. Held
	 * for the session rather than persisted: the group it names is a friends chat at an activity, which is
	 * over long before the client is.
	 */
	private final Set<String> dismissed = new HashSet<>();

	/** The room we attended, or null when we are not in an ambient one. */
	private String currentRoom;
	/** What we last reported seeing, so an unchanged report is not re-sent every tick. */
	private List<String> lastSighted = List.of();

	private Consumer<Group> onDetected = group -> { };
	private Runnable onEnded = () -> { };

	@Inject
	AmbientGroups(Client client, OSPartyConfig config, LivePartyBackend liveParty)
	{
		this.client = client;
		this.config = config;
		this.liveParty = liveParty;
	}

	/** A group detected in the game: its activity, the friends chat it is organised in, and its room key. */
	@Getter
	public static final class Group
	{
		private final Activity activity;
		private final String friendsChat;
		private final String room;
		/** Our own name at the moment we found the group — what the rest of it has to recognise us by. */
		private final String localName;

		Group(Activity activity, String friendsChat, String room, String localName)
		{
			this.activity = activity;
			this.friendsChat = friendsChat;
			this.room = room;
			this.localName = localName;
		}
	}

	/** Called when we attend a group's room, so the panel can show the party we are suddenly in. */
	public void setOnDetected(Consumer<Group> onDetected)
	{
		this.onDetected = onDetected == null ? group -> { } : onDetected;
	}

	/** Called when that group is over — the friends chat, the activity, or the setting went away. */
	public void setOnEnded(Runnable onEnded)
	{
		this.onEnded = onEnded == null ? () -> { } : onEnded;
	}

	/**
	 * Re-read the group we are in and act on the difference. Cheap enough for every tick: the reads are all
	 * client-side, and nothing is sent unless what we can see actually changed.
	 */
	public void tick()
	{
		if (!config.ambientGroups())
		{
			standDown();
			return;
		}
		// A party we hosted or applied to outranks anything detected — there is only ever one — and it is not
		// ours to leave on the strength of a friends chat.
		if (liveParty.isInParty() && !liveParty.isAmbient())
		{
			currentRoom = null;
			return;
		}

		Group group = detect();
		if (group == null || dismissed.contains(group.getRoom()))
		{
			standDown();
			return;
		}

		List<String> sighted = sightedGroupMembers();
		if (!group.getRoom().equals(currentRoom))
		{
			// Nobody from the group in sight is not a group yet. Waiting for one costs nothing — the first two
			// to stand together each see the other — and it keeps a room from being opened every time someone
			// in a friends chat happens to walk past an activity.
			if (sighted.isEmpty())
			{
				standDown();
				return;
			}
			standDown();
			currentRoom = group.getRoom();
			lastSighted = sighted;
			liveParty.attendGroup(group.getRoom(), group.getActivity().getId(),
				capacityFor(group.getActivity()), group.getLocalName(), sighted);
			log.debug("Ambient group: attending {} ({} at {})",
				group.getRoom(), group.getFriendsChat(), group.getActivity().getId());
			onDetected.accept(group);
			return;
		}

		if (!sighted.equals(lastSighted))
		{
			lastSighted = sighted;
			liveParty.reportSighted(sighted);
		}
	}

	/** Step out of the group we are in, and stay out of it for this session. */
	public void dismissCurrent()
	{
		if (currentRoom != null)
		{
			dismissed.add(currentRoom);
		}
		standDown();
	}

	/** Whether the party we are in is one this found rather than one the player chose. */
	public boolean isAttending()
	{
		return currentRoom != null;
	}

	/** Forget the dismissals; a fresh login is a fresh session as far as this is concerned. */
	public void reset()
	{
		dismissed.clear();
		currentRoom = null;
		lastSighted = List.of();
	}

	/**
	 * The group we are in right now, or null if we are not in one: a friends chat, at an activity, with a
	 * name of our own to be recognised by.
	 */
	private Group detect()
	{
		FriendsChatManager friendsChat = client.getFriendsChatManager();
		if (friendsChat == null)
		{
			return null;
		}
		String owner = friendsChat.getOwner();
		if (owner == null || owner.isEmpty())
		{
			return null;
		}
		Activity activity = Activity.nearby(client.getMapRegions());
		if (activity == null)
		{
			return null;
		}
		Player local = client.getLocalPlayer();
		if (local == null || local.getName() == null)
		{
			// Nothing to be vouched for by: a member the room cannot name cannot be seated. See
			// PartyRoom#promoteSighted on the server.
			return null;
		}
		return new Group(activity, owner, roomKey(activity.getId(), owner), local.getName());
	}

	/**
	 * Everyone our scene can see who is also in our friends chat, by name.
	 *
	 * <p>The friends-chat test is the game's own ({@code Player#isFriendsChatMember}), which is why this never
	 * has to walk the channel's member list — and why a player standing next to us who is not in our group is
	 * not named to anyone.
	 */
	private List<String> sightedGroupMembers()
	{
		Player local = client.getLocalPlayer();
		List<String> names = new ArrayList<>();
		for (Player player : client.getPlayers())
		{
			if (player == null || player == local || !player.isFriendsChatMember())
			{
				continue;
			}
			String name = player.getName();
			if (name == null || name.isEmpty())
			{
				continue;
			}
			names.add(name);
			if (names.size() >= MAX_SIGHTED)
			{
				break;
			}
		}
		// Sorted so an unchanged group is an unchanged report: the scene hands players back in whatever order
		// it holds them, and re-sending on a reshuffle would be one frame per tick for nothing.
		names.sort(String::compareTo);
		return names;
	}

	private void standDown()
	{
		if (currentRoom == null)
		{
			return;
		}
		currentRoom = null;
		lastSighted = List.of();
		if (liveParty.isAmbient())
		{
			liveParty.leave();
			onEnded.run();
		}
	}

	private static int capacityFor(Activity activity)
	{
		return Math.max(MIN_CAPACITY, Math.min(MAX_CAPACITY, activity.getMaxPartySize()));
	}

	/**
	 * The room key for a group: everyone in it derives the same one, and nobody outside it has to be told
	 * anything for that to work.
	 *
	 * <p>Hashed rather than sent as-is so the server is never handed the friends chat's name — it has no use
	 * for it, and a room key that reads as a player's name is one that says who is raiding with whom to anyone
	 * who ever sees it. The activity is in the key as well as the channel, so a group that finishes at one
	 * boss and starts at another is a new room rather than the old one carrying strangers into it.
	 */
	static String roomKey(String activityId, String friendsChatOwner)
	{
		String material = KEY_VERSION + activityId + ':' + PlayerNames.normalize(friendsChatOwner);
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(material.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 22);
		}
		catch (NoSuchAlgorithmException e)
		{
			// SHA-256 is required of every Java runtime; there is no sensible fallback and no way here.
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
