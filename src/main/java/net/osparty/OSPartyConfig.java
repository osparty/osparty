package net.osparty;

import java.awt.Color;

import net.osparty.enums.BlockedApplicantAction;
import net.osparty.enums.DefenceOverlayPosition;
import net.osparty.enums.SceneFontSize;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;

@ConfigGroup(OSPartyConfig.GROUP)
public interface OSPartyConfig extends Config
{
	String GROUP = "osparty";

	@ConfigSection(
		name = "Panel & browsing",
		description = "How the OSParty panel presents itself, and what you see while looking for a party.",
		position = 1,
		closedByDefault = true
	)
	String PANEL = "panel";

	@ConfigSection(
		name = "Hosting",
		description = "Settings that apply when you're the one running the party.",
		position = 2,
		closedByDefault = true
	)
	String HOSTING = "hosting";

	@ConfigSection(
		name = "Notifications",
		description = "How OSParty tells you about party events: chatbox, desktop, invites and join requests.",
		position = 3,
		closedByDefault = true
	)
	String NOTIFICATIONS = "notifications";

	@ConfigSection(
		name = "Event sounds",
		description = "Optional sound effects for party events (ready checks, kicks, friends-chat requests). All off by default.",
		position = 4,
		closedByDefault = true
	)
	String SOUNDS = "sounds";

	@ConfigSection(
		name = "Privacy & safety",
		description = "What you share with the party, and warnings about who you're playing with.",
		position = 5,
		closedByDefault = true
	)
	String PRIVACY = "privacy";

	@ConfigSection(
		name = "Map pings",
		description = "Tile pings you and your party draw on the game scene.",
		position = 6,
		closedByDefault = true
	)
	String MAP_PINGS = "mapPings";

	@ConfigSection(
		name = "Learner & teacher markers",
		description = "Name icons and tile markers for party members tagged as a learner or teacher.",
		position = 7,
		closedByDefault = true
	)
	String MARKERS = "markers";

	@ConfigSection(
		name = "Defence tracker",
		description = "Show the live defence of a monster the party is draining with special attacks.",
		position = 8,
		closedByDefault = true
	)
	String DEFENCE = "defence";

	// ---- Panel & browsing ----

	String SIDE_PANEL_PRIORITY = "sidePanelPriority";

	@Range(min = 0, max = 20)
	@ConfigItem(
		keyName = SIDE_PANEL_PRIORITY,
		name = "Side panel priority",
		description = "Where the OSParty icon sits in the RuneLite sidebar. Lower # = higher up, higher # = further down.",
		position = 1,
		section = PANEL
	)
	default int sidePanelPriority()
	{
		return 7;
	}

	@ConfigItem(
		keyName = "showDiscordBadges",
		name = "Discord role badges",
		description = "Show Discord role badges (developer, content creator, beta tester, backer) next to party hosts in Search and next to members in your party.",
		position = 2,
		section = PANEL
	)
	default boolean showDiscordBadges()
	{
		return true;
	}

	@Range(min = 1, max = 500)
	@ConfigItem(
		keyName = "partyHistoryLimit",
		name = "Party history size",
		description = "How many past parties to keep in the History tab. Older entries are dropped once the limit is reached.",
		position = 3,
		section = PANEL
	)
	default int partyHistoryLimit()
	{
		return 50;
	}

	@ConfigItem(
		keyName = "showBlockedParties",
		name = "Show blocked parties",
		description = "Show parties whose host is on your block list, greyed out, instead of hiding them from search.",
		position = 4,
		section = PANEL
	)
	default boolean showBlockedParties()
	{
		return false;
	}

	@ConfigItem(
		keyName = "learnerRaidToggle",
		name = "Enable learner raid toggle",
		description = "Show an \"I'm a learner\" checkbox when applying to a raid (ToA/ToB/CoX), so you can mark "
			+ "yourself as a learner. Turn off to hide it during role/raid selection.",
		position = 5,
		section = PANEL
	)
	default boolean learnerRaidToggle()
	{
		return true;
	}

	// ---- Hosting ----

	@Range(min = 1, max = 100)
	@ConfigItem(
		keyName = "defaultCapacity",
		name = "Default party size",
		description = "Capacity pre-filled in the create-party form.",
		position = 1,
		section = HOSTING
	)
	default int defaultCapacity()
	{
		return 3;
	}

	@Range(min = 1, max = 20)
	@ConfigItem(
		keyName = "applicantOverlayMax",
		name = "Max applicants shown",
		description = "Maximum applicants listed in the in-game applicant overlay before a \"+N more\" line.",
		position = 2,
		section = HOSTING
	)
	default int applicantOverlayMax()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "blockedApplicantAction",
		name = "Blocked applicant",
		description = "What to do when a player on your block list applies to your party: warn you (you decide), auto-reject and notify, or auto-reject silently.",
		position = 3,
		section = HOSTING
	)
	default BlockedApplicantAction blockedApplicantAction()
	{
		return BlockedApplicantAction.WARN;
	}

	@ConfigItem(
		keyName = "skipDisbandConfirm",
		name = "Skip disband confirmation",
		description = "Don't ask for confirmation before disbanding a party you host.",
		position = 4,
		section = HOSTING
	)
	default boolean skipDisbandConfirm()
	{
		return false;
	}

	// ---- Notifications ----

	@ConfigItem(
		keyName = "chatboxNotifications",
		name = "Chatbox notifications",
		description = "Post OSParty event messages (applicant pings, friends-chat requests, ready checks, etc.) to your in-game chatbox.",
		position = 1,
		section = NOTIFICATIONS
	)
	default boolean chatboxNotifications()
	{
		return true;
	}

	@ConfigItem(
		keyName = "inGamePrompts",
		name = "In-game join prompts",
		description = "As a host, show Accept/Decline for new applicants in the in-game chatbox (not just the side panel).",
		position = 2,
		section = NOTIFICATIONS
	)
	default boolean inGamePrompts()
	{
		return true;
	}

	@ConfigItem(
		keyName = "desktopNotifications",
		name = "Desktop notifications",
		description = "Also send a desktop notification for OSParty events (party invites, friends-chat "
			+ "requests, new applicants, ready checks). Off by default.",
		position = 3,
		section = NOTIFICATIONS
	)
	default boolean desktopNotifications()
	{
		return false;
	}

	@ConfigItem(
		keyName = "inviteDisplay",
		name = "Friend invites",
		description = "How to surface a party invite from a friend: blink the OSParty sidebar button, post an "
			+ "in-game chat line, both, or ignore invites entirely.",
		position = 4,
		section = NOTIFICATIONS
	)
	default net.osparty.enums.InviteDisplay inviteDisplay()
	{
		return net.osparty.enums.InviteDisplay.BOTH;
	}

	@ConfigItem(
		keyName = "receiveFriendsChatRequests",
		name = "Friends-chat join requests",
		description = "Allow party hosts to ask you (via an on-screen popup) to join their friends chat. Turn off to ignore these requests.",
		position = 5,
		section = NOTIFICATIONS
	)
	default boolean receiveFriendsChatRequests()
	{
		return true;
	}

	@Range(min = 1, max = 30)
	@ConfigItem(
		keyName = "fcRequestDurationSecs",
		name = "Join-request popup duration (s)",
		description = "How long the friends-chat / notice-board join-request popup stays on screen before it disappears.",
		position = 6,
		section = NOTIFICATIONS
	)
	default int fcRequestDurationSecs()
	{
		return 3;
	}

	// ---- Event sounds ----

	@ConfigItem(
		keyName = "readyCheckSound",
		name = "Ready-check sounds",
		description = "Play sounds for ready checks (when one starts, and when everyone is ready).",
		position = 1,
		section = SOUNDS
	)
	default boolean readyCheckSound()
	{
		return false;
	}

	@ConfigItem(
		keyName = "friendsChatRequestSound",
		name = "Friends-chat request sound",
		description = "Play a sound when a host asks you to join their friends chat.",
		position = 2,
		section = SOUNDS
	)
	default boolean friendsChatRequestSound()
	{
		return false;
	}

	@ConfigItem(
		keyName = "kickSound",
		name = "Kick sound",
		description = "Play a sound when you are kicked from a party.",
		position = 3,
		section = SOUNDS
	)
	default boolean kickSound()
	{
		return false;
	}

	// ---- Privacy & safety ----

	@ConfigItem(
		keyName = "hideInventory",
		name = "Hide my inventory",
		description = "Don't share your inventory (including rune pouch contents) with other party members.",
		position = 1,
		section = PRIVACY
	)
	default boolean hideInventory()
	{
		return false;
	}

	@ConfigItem(
		keyName = "hideGear",
		name = "Hide my gear",
		description = "Don't share your equipped gear with other party members.",
		position = 2,
		section = PRIVACY
	)
	default boolean hideGear()
	{
		return false;
	}

	@ConfigItem(
		keyName = "runeWatch",
		name = "RuneWatch warnings",
		description = "Warn when a party member or applicant is on the RuneWatch / We Do Raids scammer watchlist.",
		position = 3,
		section = PRIVACY
	)
	default boolean runeWatch()
	{
		return true;
	}

	// ---- Map pings ----

	@ConfigItem(
		keyName = "pings",
		name = "Map pings",
		description = "Show party members' tile pings on screen, and let you ping tiles for the party to see.",
		position = 1,
		section = MAP_PINGS
	)
	default boolean pings()
	{
		return true;
	}

	@ConfigItem(
		keyName = "pingHotkey",
		name = "Ping hotkey",
		description = "Hold this key and left-click a tile to ping it for the whole party.",
		position = 2,
		section = MAP_PINGS
	)
	default Keybind pingHotkey()
	{
		return new Keybind(java.awt.event.KeyEvent.VK_BACK_QUOTE, 0);
	}

	@Alpha
	@ConfigItem(
		keyName = "pingColor",
		name = "Your ping colour",
		description = "Colour your own tile pings appear in (and the name label) for everyone in the party.",
		position = 3,
		section = MAP_PINGS
	)
	default Color pingColor()
	{
		return new Color(0, 255, 255);
	}

	@Range(min = 200, max = 5000)
	@ConfigItem(
		keyName = "pingAnimMs",
		name = "Ping duration (ms)",
		description = "How long a map ping animates and stays visible.",
		position = 4,
		section = MAP_PINGS
	)
	default int pingAnimMs()
	{
		return 2000;
	}

	// ---- Learner & teacher markers ----

	@ConfigItem(
		keyName = "learnerTeacherIcons",
		name = "Learner/teacher name icons",
		description = "Show an icon by the name of party members tagged as a learner or teacher. Untagged members get nothing.",
		position = 1,
		section = MARKERS
	)
	default boolean learnerTeacherIcons()
	{
		return true;
	}

	@ConfigItem(
		keyName = "learnerTeacherTiles",
		name = "Learner/teacher tile markers",
		description = "Highlight the tile of party members tagged as a learner or teacher. Untagged members get nothing.",
		position = 2,
		section = MARKERS
	)
	default boolean learnerTeacherTiles()
	{
		return true;
	}

	@Range(min = 0, max = 255)
	@ConfigItem(
		keyName = "markerTileMaxAlpha",
		name = "Marker tile fill opacity",
		description = "Maximum opacity (0-255) of the learner/teacher tile fill. The configured colour's own alpha still applies if lower.",
		position = 3,
		section = MARKERS
	)
	default int markerTileMaxAlpha()
	{
		return 60;
	}

	@Alpha
	@ConfigItem(
		keyName = "teacherColor",
		name = "Teacher colour",
		description = "Colour of the teacher tile marker.",
		position = 4,
		section = MARKERS
	)
	default Color teacherColor()
	{
		return new Color(255, 175, 45);
	}

	@Alpha
	@ConfigItem(
		keyName = "learnerColor",
		name = "Learner colour",
		description = "Colour of the learner tile marker.",
		position = 5,
		section = MARKERS
	)
	default Color learnerColor()
	{
		return new Color(80, 200, 255);
	}

	// ---- Defence tracker ----

	@ConfigItem(
		keyName = "defenceHpBar",
		name = "Show next to HP bar",
		description = "Display a monster's live defence on the scene, next to its health bar, as the party drains it. Needs the \"Special Attack Counter\" plugin enabled.",
		position = 1,
		section = DEFENCE
	)
	default boolean defenceHpBar()
	{
		return true;
	}

	@ConfigItem(
		keyName = "defenceHpBarPosition",
		name = "HP-bar position",
		description = "Where the scene defence display sits relative to the monster (only applies to the HP-bar display).",
		position = 2,
		section = DEFENCE
	)
	default DefenceOverlayPosition defenceHpBarPosition()
	{
		return DefenceOverlayPosition.ABOVE_HP_BAR;
	}

	@ConfigItem(
		keyName = "defenceInfoBox",
		name = "Show in status bar",
		description = "Display the monster's live defence as an info box in the status/info-box bar. Can be used together with, or instead of, the HP-bar display.",
		position = 3,
		section = DEFENCE
	)
	default boolean defenceInfoBox()
	{
		return false;
	}

	@ConfigItem(
		keyName = "defenceShowFullLevel",
		name = "Show full level",
		description = "For monsters with a minimum defence, show the full level instead of the amount above the minimum.",
		position = 4,
		section = DEFENCE
	)
	default boolean defenceShowFullLevel()
	{
		return false;
	}

	@Range(min = 0, max = 500)
	@ConfigItem(
		keyName = "defenceLowThreshold",
		name = "Low defence threshold",
		description = "Defence at or below this (above the minimum) is shown in the low-defence colour.",
		position = 5,
		section = DEFENCE
	)
	default int defenceLowThreshold()
	{
		return 10;
	}

	@Alpha
	@ConfigItem(
		keyName = "defenceHighColor",
		name = "High defence colour",
		description = "Colour when defence is above the low threshold.",
		position = 6,
		section = DEFENCE
	)
	default Color defenceHighColor()
	{
		return Color.WHITE;
	}

	@Alpha
	@ConfigItem(
		keyName = "defenceLowColor",
		name = "Low defence colour",
		description = "Colour when defence is at or below the low threshold.",
		position = 7,
		section = DEFENCE
	)
	default Color defenceLowColor()
	{
		return Color.YELLOW;
	}

	@Alpha
	@ConfigItem(
		keyName = "defenceCappedColor",
		name = "Capped defence colour",
		description = "Colour when defence is fully drained (at the monster's minimum).",
		position = 8,
		section = DEFENCE
	)
	default Color defenceCappedColor()
	{
		return Color.GREEN;
	}

	@ConfigItem(
		keyName = "defenceFontSize",
		name = "Scene text size",
		description = "Font size for the on-scene defence display.",
		position = 9,
		section = DEFENCE
	)
	default SceneFontSize defenceFontSize()
	{
		return SceneFontSize.SMALL;
	}

	@ConfigItem(
		keyName = "defenceTextPlate",
		name = "Scene text background",
		description = "Draw a translucent plate behind the scene defence text for legibility.",
		position = 10,
		section = DEFENCE
	)
	default boolean defenceTextPlate()
	{
		return false;
	}
}
