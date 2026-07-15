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
		name = "General",
		description = "Core OSParty settings.",
		position = 1,
		closedByDefault = true
	)
	String GENERAL = "general";

	@ConfigSection(
		name = "Defence tracker",
		description = "Show the live defence of a monster the party is draining with special attacks.",
		position = 13,
		closedByDefault = true
	)
	String DEFENCE = "defence";

	@ConfigSection(
		name = "Event sounds",
		description = "Optional sound effects for party events (ready checks, kicks, friends-chat requests). All off by default.",
		position = 14,
		closedByDefault = true
	)
	String MEME_MODE = "memeMode";

	@ConfigSection(
		name = "Scene overlays",
		description = "Tile pings, learner/teacher markers and defence text drawn on the game scene.",
		position = 15,
		closedByDefault = true
	)
	String SCENE = "scene";

	@ConfigItem(
		keyName = "defaultCapacity",
		name = "Default party size",
		description = "Capacity pre-filled in the create-party form.",
		position = 1,
		section = GENERAL
	)
	default int defaultCapacity()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "runeWatch",
		name = "RuneWatch warnings",
		description = "Warn when a party member or applicant is on the RuneWatch / We Do Raids scammer watchlist.",
		position = 2,
		section = GENERAL
	)
	default boolean runeWatch()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showBlockedParties",
		name = "Show blocked parties",
		description = "Show parties whose host is on your block list, greyed out, instead of hiding them from search.",
		position = 14,
		section = GENERAL
	)
	default boolean showBlockedParties()
	{
		return false;
	}

	@ConfigItem(
		keyName = "blockedApplicantAction",
		name = "Blocked applicant",
		description = "What to do when a player on your block list applies to your party: warn you (you decide), auto-reject and notify, or auto-reject silently.",
		position = 15,
		section = GENERAL
	)
	default BlockedApplicantAction blockedApplicantAction()
	{
		return BlockedApplicantAction.WARN;
	}

	@Range(min = 1, max = 500)
	@ConfigItem(
		keyName = "partyHistoryLimit",
		name = "Party history size",
		description = "How many past parties to keep in the History tab. Older entries are dropped once the limit is reached.",
		position = 16,
		section = GENERAL
	)
	default int partyHistoryLimit()
	{
		return 50;
	}

	@ConfigItem(
		keyName = "showDiscordBadges",
		name = "Discord role badges",
		description = "Show Discord role badges (developer, content creator, beta tester, backer) next to party hosts in Search and next to members in your party.",
		position = 17,
		section = GENERAL
	)
	default boolean showDiscordBadges()
	{
		return true;
	}

	@ConfigItem(
		keyName = "inGamePrompts",
		name = "In-game join prompts",
		description = "As a host, show Accept/Decline for new applicants in the in-game chatbox (not just the side panel).",
		position = 3,
		section = GENERAL
	)
	default boolean inGamePrompts()
	{
		return true;
	}

	@ConfigItem(
		keyName = "inviteDisplay",
		name = "Friend invites",
		description = "How to surface a party invite from a friend: blink the OSParty sidebar button, post an "
			+ "in-game chat line, both, or ignore invites entirely.",
		position = 5,
		section = GENERAL
	)
	default net.osparty.enums.InviteDisplay inviteDisplay()
	{
		return net.osparty.enums.InviteDisplay.BOTH;
	}

	@ConfigItem(
		keyName = "desktopNotifications",
		name = "Desktop notifications",
		description = "Also send a desktop notification for OSParty events (party invites, friends-chat "
			+ "requests, new applicants, ready checks). Off by default.",
		position = 6,
		section = GENERAL
	)
	default boolean desktopNotifications()
	{
		return false;
	}

	@ConfigItem(
		keyName = "receiveFriendsChatRequests",
		name = "Friends-chat join requests",
		description = "Allow party hosts to ask you (via an on-screen popup) to join their friends chat. Turn off to ignore these requests.",
		position = 4,
		section = GENERAL
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
		position = 4,
		section = GENERAL
	)
	default int fcRequestDurationSecs()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "learnerRaidToggle",
		name = "Enable learner raid toggle",
		description = "Show an \"I'm a learner\" checkbox when applying to a raid (ToA/ToB/CoX), so you can mark "
			+ "yourself as a learner. Turn off to hide it during role/raid selection.",
		position = 4,
		section = GENERAL
	)
	default boolean learnerRaidToggle()
	{
		return true;
	}

	@ConfigItem(
		keyName = "pings",
		name = "Map pings",
		description = "Show party members' tile pings on screen, and let you ping tiles for the party to see.",
		position = 5,
		section = GENERAL
	)
	default boolean pings()
	{
		return true;
	}

	@ConfigItem(
		keyName = "pingHotkey",
		name = "Ping hotkey",
		description = "Hold this key and left-click a tile to ping it for the whole party.",
		position = 6,
		section = GENERAL
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
		position = 7,
		section = GENERAL
	)
	default Color pingColor()
	{
		return new Color(0, 255, 255);
	}

	@ConfigItem(
		keyName = "learnerTeacherIcons",
		name = "Learner/teacher name icons",
		description = "Show an icon by the name of party members tagged as a learner or teacher. Untagged members get nothing.",
		position = 8,
		section = GENERAL
	)
	default boolean learnerTeacherIcons()
	{
		return true;
	}

	@ConfigItem(
		keyName = "learnerTeacherTiles",
		name = "Learner/teacher tile markers",
		description = "Highlight the tile of party members tagged as a learner or teacher. Untagged members get nothing.",
		position = 9,
		section = GENERAL
	)
	default boolean learnerTeacherTiles()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "teacherColor",
		name = "Teacher colour",
		description = "Colour of the teacher tile marker.",
		position = 10,
		section = GENERAL
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
		position = 11,
		section = GENERAL
	)
	default Color learnerColor()
	{
		return new Color(80, 200, 255);
	}

	@ConfigItem(
		keyName = "skipDisbandConfirm",
		name = "Skip disband confirmation",
		description = "Don't ask for confirmation before disbanding a party you host.",
		position = 12,
		section = GENERAL
	)
	default boolean skipDisbandConfirm()
	{
		return false;
	}

	@ConfigItem(
		keyName = "chatboxNotifications",
		name = "Chatbox notifications",
		description = "Post OSParty event messages (applicant pings, friends-chat requests, ready checks, etc.) to your in-game chatbox.",
		position = 13,
		section = GENERAL
	)
	default boolean chatboxNotifications()
	{
		return true;
	}

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

	@ConfigItem(
		keyName = "readyCheckSound",
		name = "Ready-check sounds",
		description = "Play sounds for ready checks (when one starts, and when everyone is ready).",
		position = 5,
		section = MEME_MODE
	)
	default boolean readyCheckSound()
	{
		return false;
	}

	@ConfigItem(
		keyName = "kickSound",
		name = "Kick sound",
		description = "Play a sound when you are kicked from a party.",
		position = 7,
		section = MEME_MODE
	)
	default boolean kickSound()
	{
		return false;
	}

	@ConfigItem(
		keyName = "friendsChatRequestSound",
		name = "Friends-chat request sound",
		description = "Play a sound when a host asks you to join their friends chat.",
		position = 8,
		section = MEME_MODE
	)
	default boolean friendsChatRequestSound()
	{
		return false;
	}

	@ConfigItem(
		keyName = "applicantOverlayMax",
		name = "Max applicants shown",
		description = "Maximum applicants listed in the in-game applicant overlay before a \"+N more\" line.",
		position = 1,
		section = SCENE
	)
	default int applicantOverlayMax()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "markerTileMaxAlpha",
		name = "Marker tile fill opacity",
		description = "Maximum opacity (0-255) of the learner/teacher tile fill. The configured colour's own alpha still applies if lower.",
		position = 2,
		section = SCENE
	)
	default int markerTileMaxAlpha()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "pingAnimMs",
		name = "Ping duration (ms)",
		description = "How long a map ping animates and stays visible.",
		position = 3,
		section = SCENE
	)
	default int pingAnimMs()
	{
		return 2000;
	}

	@ConfigItem(
		keyName = "markerNameOffset",
		name = "Marker icon height offset",
		description = "Vertical offset of the learner/teacher icon above a member's overhead name.",
		position = 4,
		section = SCENE
	)
	default int markerNameOffset()
	{
		return 40;
	}
}
