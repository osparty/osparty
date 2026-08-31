package net.osparty.tools;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.osparty.model.Activity;
import net.osparty.party.PlayerNames;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

/**
 * Notices when the local player makes a raid party in-game, so OSParty can offer to advertise it.
 *
 * <p>Each raid says so in its own terms. The Theatre and the Tombs publish a party status that goes from
 * nothing to "in a party"; Chambers publishes the id of the party the friends chat is forming, which every
 * member of the chat sees, party or not. None of them say who made it -- the lobby fills in neither the
 * roster names nor the Chambers leader flag -- so that is settled from what the player did and sees: a
 * click on the board's own Make party (or Advertise) button, or the party-details screen showing the
 * leader's Disband button where a member's shows Leave.
 *
 * <p>The offer goes out the moment ownership is settled. What the ad takes from the game -- mode, size,
 * challenge mode, scaling, invocations -- is chosen on the details screen after the party exists, so it
 * is read by {@link #snapshot} when the host answers the offer, which is when they have finished
 * choosing. Polled once a tick rather than fed from varbit events, because a login replays every varbit
 * and the replay must not read as the player doing something.
 */
@Slf4j
@Singleton
public class RaidPartyWatcher
{
	/** Ticks after login during which a change is the replay settling, not the player acting. */
	static final int LOGIN_GRACE_TICKS = 5;
	/** Ticks a new party is given to prove itself ours before it is taken for someone else's. */
	static final int OWNERSHIP_TICKS = 10;
	/**
	 * Ticks after leaving a Chambers raid during which the party id coming back is the same party: the id
	 * reads as "no party" for the length of a raid and returns once it ends.
	 */
	static final int RAID_EXIT_GRACE_TICKS = 50;
	/** Ticks for which a Make party click vouches for the party that appears after it. */
	static final int MAKE_PARTY_CLICK_TICKS = 100;

	private static final int NO_COX_PARTY = -1;
	private static final Pattern NUMBER = Pattern.compile("(\\d{1,3})");
	private static final Set<Activity> RAIDS = EnumSet.of(Activity.CHAMBERS_OF_XERIC,
		Activity.THEATRE_OF_BLOOD, Activity.TOMBS_OF_AMASCUT);

	private final Client client;
	private Consumer<RaidPartyDetected> listener;

	private boolean loggedIn;
	private int ticksLoggedIn;
	private int tick;
	private int lastTobStatus;
	private int lastToaStatus;
	private int lastCoxParty = NO_COX_PARTY;
	private int coxParty = NO_COX_PARTY;
	private int ticksSinceCoxRaid = RAID_EXIT_GRACE_TICKS + 1;

	private Activity armed;
	private int armedTicks;
	/** The details showed the leader's Disband button, which a member's copy of the same screen lacks. */
	private boolean leaderButtonSeen;
	private boolean coxAdvertised;
	/** The Chambers party already offered, so one party is offered once however often Advertise is clicked. */
	private int offeredCoxParty = NO_COX_PARTY;
	/** The raid whose Make party button was clicked last, and when. */
	private Activity madePartyAt;
	private int madePartyTick;

	/** Texts last seen on the details screens; they outlive the screen but not the party. */
	private String tobModeText;
	private String tobSizeText;
	/**
	 * The Tombs raid level as the details screen shows it ("Raid Level: 300 (19)"). Read because the
	 * raid-level varbit sits at 0 in the lobby even while the board already shows the party's level.
	 */
	private String toaLevelText;

	@Inject
	RaidPartyWatcher(Client client)
	{
		this.client = client;
	}

	/** Where detections go; called on the client thread. */
	public void setListener(Consumer<RaidPartyDetected> listener)
	{
		this.listener = listener;
	}

	/** Read this tick's party state and move the detection along. Client thread. */
	public void update()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			loggedIn = false;
			ticksLoggedIn = 0;
			disarm();
			return;
		}
		tick++;
		int tob = client.getVarbitValue(VarbitID.TOB_CLIENT_PARTYSTATUS);
		int toa = client.getVarbitValue(VarbitID.TOA_CLIENT_PARTYSTATUS);
		coxParty = client.getVarpValue(VarPlayerID.RAIDS_PARTY_GROUPHOLDER);
		if (client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1)
		{
			ticksSinceCoxRaid = 0;
		}
		else if (ticksSinceCoxRaid <= RAID_EXIT_GRACE_TICKS)
		{
			ticksSinceCoxRaid++;
		}
		watchDetails();
		if (tob != 1)
		{
			tobModeText = null;
			tobSizeText = null;
		}
		if (toa != 1)
		{
			toaLevelText = null;
		}

		boolean settled = loggedIn && ticksLoggedIn >= LOGIN_GRACE_TICKS;
		loggedIn = true;
		if (ticksLoggedIn < LOGIN_GRACE_TICKS)
		{
			ticksLoggedIn++;
		}

		if (settled)
		{
			if (armed == null)
			{
				armIfCreated(tob, toa);
			}
			else if (dissolved(tob, toa))
			{
				log.debug("{} party dissolved before it could be offered", armed);
				disarm();
			}
			else
			{
				advance();
			}
		}

		lastTobStatus = tob;
		lastToaStatus = toa;
		if (coxParty == NO_COX_PARTY)
		{
			offeredCoxParty = NO_COX_PARTY;
		}
		lastCoxParty = coxParty;
	}

	/**
	 * The party as the game describes it right now. Read when the host answers the offer rather than when
	 * the party appeared, because the mode, size and invocations are chosen in between. Client thread.
	 */
	public RaidPartyDetected snapshot(Activity activity)
	{
		switch (activity)
		{
			case THEATRE_OF_BLOOD:
				return new RaidPartyDetected(activity, parseMode(tobModeText), 0, parseSize(tobSizeText, activity), "");
			case TOMBS_OF_AMASCUT:
				// The varbit is the live value but reads 0 in the lobby; the details screen's own line
				// ("Raid Level: 300 (19)") is what actually knows a fresh party's level.
				int varbitLevel = Math.max(0, client.getVarbitValue(VarbitID.TOA_CLIENT_RAID_LEVEL));
				return new RaidPartyDetected(activity, null,
					varbitLevel > 0 ? varbitLevel : parseLevel(toaLevelText), 0, "");
			case CHAMBERS_OF_XERIC:
				// The game remembers the last scaling, so a fresh party already has one; carrying it here
				// puts it in the ad from the start instead of leaving the board-sync to correct it.
				int scaling = client.getVarbitValue(VarbitID.RAIDS_SCALING);
				return new RaidPartyDetected(activity, client.getVarbitValue(VarbitID.RAIDS_CHALLENGE_MODE) == 1, 0,
					clampSize(client.getVarbitValue(VarbitID.RAIDS_LOBBY_PARTYSIZE), activity),
					scaling > 0 ? Integer.toString(scaling) : "");
			default:
				return null;
		}
	}

	/**
	 * A button on one of the raids' party boards was clicked. The Chambers Advertise button and every
	 * board's Make party button are the leader's alone, so either settles who made the party.
	 */
	public void onBoardClicked(Activity raid, String option, int componentId)
	{
		if (raid == Activity.CHAMBERS_OF_XERIC && componentId == InterfaceID.RaidsLobbyPartydetails.ADVERTISE)
		{
			onAdvertiseClicked();
			return;
		}
		if (option != null && option.trim().toLowerCase().startsWith("make"))
		{
			onMakePartyClicked(raid);
		}
	}

	/**
	 * The Advertise button on the Chambers party board was clicked. Only the leader has one, so this
	 * settles who made the party, and it counts as making the board even for a party that predates the
	 * login, which the party id alone would never have shown appearing.
	 */
	public void onAdvertiseClicked()
	{
		coxAdvertised = true;
		if (armed == null && loggedIn && coxParty != NO_COX_PARTY && coxParty != offeredCoxParty)
		{
			arm(Activity.CHAMBERS_OF_XERIC);
		}
	}

	/** Make party was clicked at {@code raid}'s board; the party that appears next is the player's own. */
	void onMakePartyClicked(Activity raid)
	{
		madePartyAt = raid;
		madePartyTick = tick;
		if (raid == Activity.CHAMBERS_OF_XERIC)
		{
			coxAdvertised = true;
		}
	}

	/** Forget everything, including that we were logged in; the next tick baselines afresh. */
	public void reset()
	{
		loggedIn = false;
		ticksLoggedIn = 0;
		lastTobStatus = 0;
		lastToaStatus = 0;
		lastCoxParty = NO_COX_PARTY;
		coxParty = NO_COX_PARTY;
		ticksSinceCoxRaid = RAID_EXIT_GRACE_TICKS + 1;
		offeredCoxParty = NO_COX_PARTY;
		madePartyAt = null;
		tobModeText = null;
		tobSizeText = null;
		toaLevelText = null;
		disarm();
	}

	/**
	 * Keep up with the party-details screens: the Theatre's mode and size and the Tombs' raid level while
	 * their screen is up, and the leader's button while a party is waiting to be proven ours.
	 */
	private void watchDetails()
	{
		for (Activity raid : RAIDS)
		{
			if (!isOpen(detailsRoot(raid)))
			{
				continue;
			}
			if (raid == Activity.THEATRE_OF_BLOOD)
			{
				tobModeText = textOr(InterfaceID.TobPartydetails.MODE, tobModeText);
				tobSizeText = textOr(InterfaceID.TobPartydetails.SIZE, tobSizeText);
			}
			if (raid == Activity.TOMBS_OF_AMASCUT)
			{
				toaLevelText = textOr(InterfaceID.ToaPartydetails.RAID_LEVEL, toaLevelText);
			}
			if (raid == armed && !leaderButtonSeen && leaderButtonShown(raid))
			{
				leaderButtonSeen = true;
			}
		}
	}

	private void armIfCreated(int tob, int toa)
	{
		if (tob == 1 && lastTobStatus == 0)
		{
			arm(Activity.THEATRE_OF_BLOOD);
		}
		else if (toa == 1 && lastToaStatus == 0)
		{
			arm(Activity.TOMBS_OF_AMASCUT);
		}
		else if (coxParty != NO_COX_PARTY && lastCoxParty == NO_COX_PARTY
			&& ticksSinceCoxRaid > RAID_EXIT_GRACE_TICKS)
		{
			arm(Activity.CHAMBERS_OF_XERIC);
		}
	}

	private boolean dissolved(int tob, int toa)
	{
		switch (armed)
		{
			case THEATRE_OF_BLOOD:
				return tob != 1;
			case TOMBS_OF_AMASCUT:
				return toa != 1;
			case CHAMBERS_OF_XERIC:
				return coxParty == NO_COX_PARTY;
			default:
				return true;
		}
	}

	private void arm(Activity activity)
	{
		armed = activity;
		armedTicks = 0;
		leaderButtonSeen = false;
		log.debug("{} party appeared at tick {}; waiting to see whether it is ours", activity, tick);
	}

	private void disarm()
	{
		armed = null;
		armedTicks = 0;
		leaderButtonSeen = false;
		coxAdvertised = false;
	}

	private void advance()
	{
		armedTicks++;
		if (owned(armed))
		{
			fire();
		}
		else if (armedTicks >= OWNERSHIP_TICKS)
		{
			log.debug("{} party showed no sign of being ours within {} ticks; not offering it", armed, armedTicks);
			disarm();
		}
	}

	private void fire()
	{
		Activity activity = armed;
		RaidPartyDetected detected = snapshot(activity);
		disarm();
		if (activity == Activity.CHAMBERS_OF_XERIC)
		{
			offeredCoxParty = coxParty;
		}
		Activity near = Activity.nearby(client.getMapRegions());
		if (near != null && near != activity)
		{
			log.debug("{} party appeared while standing at {}; ignoring it", activity, near);
			return;
		}
		log.debug("Offering {}", detected);
		if (listener != null)
		{
			listener.accept(detected);
		}
	}

	/** Whether anything so far says this party is the local player's. */
	private boolean owned(Activity activity)
	{
		if (nameMatches(activity) || clickedRecently(activity) || leaderButtonSeen)
		{
			return true;
		}
		return activity == Activity.CHAMBERS_OF_XERIC
			&& (coxAdvertised || client.getVarbitValue(VarbitID.RAIDS_CLIENT_ISLEADER) == 1);
	}

	/** Whether the first roster name is ours. The lobby leaves it empty; only a raid in progress fills it. */
	private boolean nameMatches(Activity activity)
	{
		String firstName;
		switch (activity)
		{
			case THEATRE_OF_BLOOD:
				firstName = client.getVarcStrValue(VarClientID.TOB_CLIENT_NAME0);
				break;
			case TOMBS_OF_AMASCUT:
				firstName = client.getVarcStrValue(VarClientID.TOA_CLIENT_NAME0);
				break;
			default:
				return false;
		}
		Player local = client.getLocalPlayer();
		String mine = local == null ? null : local.getName();
		return mine != null && firstName != null && !firstName.isEmpty()
			&& PlayerNames.normalize(Text.removeTags(firstName)).equals(PlayerNames.normalize(mine));
	}

	private boolean clickedRecently(Activity activity)
	{
		return madePartyAt == activity && tick - madePartyTick <= MAKE_PARTY_CLICK_TICKS;
	}

	/**
	 * Whether the details screen is the leader's: its action button says Disband (a member's says Leave),
	 * and Chambers gives the leader an Advertise button of their own.
	 */
	private boolean leaderButtonShown(Activity activity)
	{
		switch (activity)
		{
			case THEATRE_OF_BLOOD:
				return hasAction(client.getWidget(InterfaceID.TobPartydetails.ACTION), "disband");
			case TOMBS_OF_AMASCUT:
				return hasAction(client.getWidget(InterfaceID.ToaPartydetails.ACTION), "disband");
			case CHAMBERS_OF_XERIC:
				return hasAction(client.getWidget(InterfaceID.RaidsLobbyPartydetails.DISBAND), "disband")
					|| hasAction(client.getWidget(InterfaceID.RaidsLobbyPartydetails.ADVERTISE), "advertise");
			default:
				return false;
		}
	}

	private static boolean hasAction(Widget widget, String needle)
	{
		if (widget == null || widget.isHidden() || widget.getActions() == null)
		{
			return false;
		}
		for (String action : widget.getActions())
		{
			if (action != null && action.toLowerCase().contains(needle))
			{
				return true;
			}
		}
		return false;
	}

	private static int detailsRoot(Activity activity)
	{
		switch (activity)
		{
			case THEATRE_OF_BLOOD:
				return InterfaceID.TobPartydetails.UNIVERSE;
			case TOMBS_OF_AMASCUT:
				return InterfaceID.ToaPartydetails.UNIVERSE;
			default:
				return InterfaceID.RaidsLobbyPartydetails.UNIVERSE;
		}
	}

	private boolean isOpen(int componentId)
	{
		Widget widget = client.getWidget(componentId);
		return widget != null && !widget.isHidden();
	}

	/**
	 * The component's text, or its first child's: the Theatre's Mode and Preferred Size rows are clickable
	 * layers whose label ("Mode: <col=ffffff>Entry</col>") is a child, so the layer itself reads empty.
	 */
	private String textOr(int componentId, String previous)
	{
		Widget widget = client.getWidget(componentId);
		String text = widgetText(widget);
		if (text == null && widget != null)
		{
			text = widgetText(widget.getChild(0));
		}
		return text == null ? previous : text;
	}

	private static String widgetText(Widget widget)
	{
		String text = widget == null ? null : widget.getText();
		return text == null || text.isEmpty() ? null : Text.removeTags(text);
	}

	/** Hard mode when the details said so, normal for the modes that are not, null when they never said. */
	static Boolean parseMode(String text)
	{
		if (text == null)
		{
			return null;
		}
		String lower = text.toLowerCase();
		if (lower.contains("hard"))
		{
			return true;
		}
		if (lower.contains("normal") || lower.contains("story") || lower.contains("entry"))
		{
			return false;
		}
		return null;
	}

	/** The first number in a "Raid Level: 300 (19)" line, or 0. */
	static int parseLevel(String text)
	{
		if (text == null)
		{
			return 0;
		}
		Matcher matcher = NUMBER.matcher(text);
		return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
	}

	/** The first number in the text when it is a size the raid allows; 0 otherwise. */
	static int parseSize(String text, Activity activity)
	{
		if (text == null)
		{
			return 0;
		}
		Matcher matcher = NUMBER.matcher(text);
		return matcher.find() ? clampSize(Integer.parseInt(matcher.group(1)), activity) : 0;
	}

	private static int clampSize(int size, Activity activity)
	{
		return size >= activity.getMinPartySize() && size <= activity.getMaxPartySize() ? size : 0;
	}
}
