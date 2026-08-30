package net.osparty;

import java.awt.Color;

import net.osparty.enums.BlockedApplicantAction;
import net.osparty.enums.DefenceDrainFormat;
import net.osparty.enums.DefenceInfoBoxValue;
import net.osparty.enums.DefenceOverlayPosition;
import net.osparty.enums.DefenceThresholdUnit;
import net.osparty.enums.DefenceValueFormat;
import net.osparty.enums.InviteDisplay;
import net.osparty.enums.MagicDefenceDisplay;
import net.osparty.enums.PartyChatChannel;
import net.osparty.enums.RaidPartyAutoCreate;
import net.osparty.enums.SceneFontSize;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

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
		name = "Party chat",
		description = "Talk to your party from the game chatbox, the way you would in a clan or friends chat.",
		position = 6,
		closedByDefault = true
	)
	String CHAT = "chat";

	@ConfigSection(
		name = "Map pings",
		description = "Tile pings you and your party draw on the game scene.",
		position = 7,
		closedByDefault = true
	)
	String MAP_PINGS = "mapPings";

	@ConfigSection(
		name = "Player markers",
		description = "Names and Vengeance icons on party members in the scene, plus the icons and tile markers for learners and teachers.",
		position = 8,
		closedByDefault = true
	)
	String MARKERS = "markers";

	@ConfigSection(
		name = "Defence tracker",
		description = "Show the live defence of a monster the party is draining with special attacks.",
		position = 9,
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

	String SHOW_DISCORD_BADGES = "showDiscordBadges";

	@ConfigItem(
		keyName = SHOW_DISCORD_BADGES,
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

	String SIMILAR_PARTY_CHECK = "similarPartyCheck";

	@ConfigItem(
		keyName = SIMILAR_PARTY_CHECK,
		name = "Check for similar parties",
		description = "Before creating a party, look for one already running the same thing and offer to "
			+ "join it instead. Turned off by \"Create, don't ask again\" on that prompt.",
		position = 4,
		section = HOSTING
	)
	default boolean similarPartyCheck()
	{
		return true;
	}

	@ConfigItem(
		keyName = "skipDisbandConfirm",
		name = "Skip disband confirmation",
		description = "Don't ask for confirmation before disbanding a party you host.",
		position = 5,
		section = HOSTING
	)
	default boolean skipDisbandConfirm()
	{
		return false;
	}

	String RAID_PARTY_AUTO_CREATE = "raidPartyAutoCreate";

	@ConfigItem(
		keyName = RAID_PARTY_AUTO_CREATE,
		name = "Advertise in-game raid parties",
		description = "When you make a raid party at the Chambers of Xeric board, the Theatre of Blood notice "
			+ "board or the Tombs of Amascut obelisk: ask whether to advertise it on OSParty, always advertise "
			+ "it, or do nothing. Turned off by \"Don't ask again\" on that prompt.",
		position = 6,
		section = HOSTING
	)
	default RaidPartyAutoCreate raidPartyAutoCreate()
	{
		return RaidPartyAutoCreate.ASK;
	}

	@ConfigItem(
		keyName = "raidPartyPromptDisplay",
		name = "Raid party prompt",
		description = "Where the \"advertise this raid party?\" question is asked: the sidebar, an in-game card, or both.",
		position = 7,
		section = HOSTING
	)
	default InviteDisplay raidPartyPromptDisplay()
	{
		return InviteDisplay.BOTH;
	}

	@ConfigItem(
		keyName = "raidBoardSync",
		name = "Follow the in-game board",
		description = "While you host a raid party, keep its Chambers of Xeric scale and Tombs of Amascut "
			+ "invocation level in step with what is set on the in-game party board.",
		position = 8,
		section = HOSTING
	)
	default boolean raidBoardSync()
	{
		return true;
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
		description = "How to surface a party invite from a friend: blink the OSParty sidebar button, show an "
			+ "in-game Accept/Decline card, both, or ignore invites entirely.",
		position = 4,
		section = NOTIFICATIONS
	)
	default net.osparty.enums.InviteDisplay inviteDisplay()
	{
		return net.osparty.enums.InviteDisplay.BOTH;
	}

	@ConfigItem(
		keyName = "matchDisplay",
		name = "Parties found while looking",
		description = "How to surface a party that turns up while \"Find me a party\" is on: blink the OSParty "
			+ "sidebar button, show an in-game card, both, or don't offer them at all.",
		position = 5,
		section = NOTIFICATIONS
	)
	default net.osparty.enums.InviteDisplay matchDisplay()
	{
		return net.osparty.enums.InviteDisplay.BOTH;
	}

	@ConfigItem(
		keyName = "receiveFriendsChatRequests",
		name = "Friends-chat join requests",
		description = "Allow party hosts to ask you (via an on-screen popup) to join their friends chat. Turn off to ignore these requests.",
		position = 6,
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
		position = 7,
		section = NOTIFICATIONS
	)
	default int fcRequestDurationSecs()
	{
		return 3;
	}

	// ---- Event sounds ----

	String SOUND_VOLUME = "soundVolume";

	@ConfigItem(
		keyName = SOUND_VOLUME,
		name = "Volume",
		description = "How loud OSParty's own sounds play. The map ping is the game's anvil sound and follows the game's sound-effect volume instead.",
		position = 0,
		section = SOUNDS
	)
	@Range(max = 100)
	@Units(Units.PERCENT)
	default int soundVolume()
	{
		return 100;
	}

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

	@ConfigItem(
		keyName = "pingSound",
		name = "Ping sound",
		description = "Play a sound when a party member drops a map ping.",
		position = 4,
		section = SOUNDS
	)
	default boolean pingSound()
	{
		return false;
	}

	// ---- Privacy & safety ----

	String HIDE_INVENTORY = "hideInventory";

	@ConfigItem(
		keyName = HIDE_INVENTORY,
		name = "Hide my inventory",
		description = "Don't share your inventory (including rune pouch contents) with other party members.",
		position = 1,
		section = PRIVACY
	)
	default boolean hideInventory()
	{
		return false;
	}

	String HIDE_GEAR = "hideGear";

	@ConfigItem(
		keyName = HIDE_GEAR,
		name = "Hide my gear",
		description = "Don't share your equipped gear with other party members.",
		position = 2,
		section = PRIVACY
	)
	default boolean hideGear()
	{
		return false;
	}

	String RUNE_WATCH = "runeWatch";

	@ConfigItem(
		keyName = RUNE_WATCH,
		name = "RuneWatch warnings",
		description = "Warn when a party member or applicant is on the RuneWatch / We Do Raids scammer watchlist.",
		position = 3,
		section = PRIVACY
	)
	default boolean runeWatch()
	{
		return true;
	}

	// ---- Party chat ----

	@ConfigItem(
		keyName = "partyChat",
		name = "Party chat",
		description = "Send and receive party chat in the game chatbox. Type the prefix, a space and your message to talk to your party; the line never reaches public or clan chat.",
		position = 1,
		section = CHAT
	)
	default boolean partyChat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "partyChatPrefix",
		name = "Chat prefix",
		description = "What to type in front of a message to send it to your party, e.g. \"!p on my way\". Typed on its own it switches party chat mode on or off, where everything you type goes to the party. A prefix starting with / also works (the game strips the slash, so OSParty matches the rest on lines headed for clan or friends chat).",
		position = 2,
		section = CHAT
	)
	default String partyChatPrefix()
	{
		return "!p";
	}

	@ConfigItem(
		keyName = "partyChatChannel",
		name = "Show as",
		description = "Which chatbox tab party lines appear in: the Channel tab like a friends chat, the Clan tab, or the Game tab. RuneLite's chat commands (!kc, !pb, ...) only expand in the Channel and Clan tabs.",
		position = 3,
		section = CHAT
	)
	default PartyChatChannel partyChatChannel()
	{
		return PartyChatChannel.FRIENDS_CHAT;
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

	@ConfigItem(
		keyName = "pingOffscreenIndicator",
		name = "Off-screen ping arrows",
		description = "Show an arrow at the screen edge pointing toward pings that are off-screen or behind you.",
		position = 5,
		section = MAP_PINGS
	)
	default boolean pingOffscreenIndicator()
	{
		return true;
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

	@ConfigItem(
		keyName = "partyNameIndicators",
		name = "Party member names",
		description = "Draw the name of every party member above their head in the scene.",
		position = 6,
		section = MARKERS
	)
	default boolean partyNameIndicators()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "partyNameColor",
		name = "Party name colour",
		description = "Colour of the name drawn above party members.",
		position = 7,
		section = MARKERS
	)
	default Color partyNameColor()
	{
		return new Color(255, 152, 31);
	}

	@ConfigItem(
		keyName = "vengeanceIcons",
		name = "Vengeance icons",
		description = "Show the Vengeance spell icon on party members in the scene while they have it active.",
		position = 8,
		section = MARKERS
	)
	default boolean vengeanceIcons()
	{
		return true;
	}

	// ---- Defence tracker ----

	@ConfigItem(
		keyName = "defenceHpBar",
		name = "Show next to HP bar",
		description = "Display a monster's live defence on the scene, next to its health bar, as the party drains it.",
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

	@Range(min = -200, max = 200)
	@Units(Units.PIXELS)
	@ConfigItem(
		keyName = "defenceHpBarYOffset",
		name = "Vertical nudge",
		description = "Shift the scene defence display up by this many pixels from the chosen position (negative to shift it down).",
		position = 3,
		section = DEFENCE
	)
	default int defenceHpBarYOffset()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "defenceInfoBox",
		name = "Show in status bar",
		description = "Display the monster's live defence as an info box in the status/info-box bar. Can be used together with, or instead of, the HP-bar display.",
		position = 4,
		section = DEFENCE
	)
	default boolean defenceInfoBox()
	{
		return false;
	}

	@ConfigItem(
		keyName = "defenceInfoBoxValue",
		name = "Status bar shows",
		description = "Which number the info box shows: the current defence, the percent remaining, or the amount drained. Hover it for the full breakdown.",
		position = 5,
		section = DEFENCE
	)
	default DefenceInfoBoxValue defenceInfoBoxValue()
	{
		return DefenceInfoBoxValue.CURRENT;
	}

	@ConfigItem(
		keyName = "defenceAlwaysShow",
		name = "Show before any spec",
		description = "Show the defence of the monster you're attacking straight away, at its starting level, instead of waiting for the first defence-draining special attack to land.",
		position = 6,
		section = DEFENCE
	)
	default boolean defenceAlwaysShow()
	{
		return false;
	}

	@ConfigItem(
		keyName = "defenceValueFormat",
		name = "Defence shown as",
		description = "How a level is written on the scene: the current value (142), current over starting (142/200), the percent remaining (71%), or current with the percent (142 (71%)). Also applies to the magic-defence bonus and Magic level readouts.",
		position = 7,
		section = DEFENCE
	)
	default DefenceValueFormat defenceValueFormat()
	{
		return DefenceValueFormat.CURRENT;
	}

	@ConfigItem(
		keyName = "defenceDrainFormat",
		name = "Drain shown as",
		description = "What follows the down arrow: the amount drained so far, the percent drained, or nothing.",
		position = 8,
		section = DEFENCE
	)
	default DefenceDrainFormat defenceDrainFormat()
	{
		return DefenceDrainFormat.AMOUNT;
	}

	@ConfigItem(
		keyName = "defenceShowFullLevel",
		name = "Show full level",
		description = "For monsters with a minimum defence, show the full level instead of the amount above the minimum. Percentages follow the same choice.",
		position = 9,
		section = DEFENCE
	)
	default boolean defenceShowFullLevel()
	{
		return false;
	}

	@ConfigItem(
		keyName = "defenceShowIcons",
		name = "Show skill icons",
		description = "Draw the Defence and Magic skill icons in front of the scene readouts.",
		position = 10,
		section = DEFENCE
	)
	default boolean defenceShowIcons()
	{
		return true;
	}

	@ConfigItem(
		keyName = "defenceFontSize",
		name = "Scene text size",
		description = "Font size for the on-scene defence display.",
		position = 11,
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
		position = 12,
		section = DEFENCE
	)
	default boolean defenceTextPlate()
	{
		return false;
	}

	@Range(min = 0, max = 500)
	@ConfigItem(
		keyName = "defenceLowThreshold",
		name = "Low defence threshold",
		description = "Defence at or below this (above the minimum) is shown in the low-defence colour. Read in levels or as a percent, per the next setting.",
		position = 13,
		section = DEFENCE
	)
	default int defenceLowThreshold()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "defenceLowThresholdUnit",
		name = "Threshold unit",
		description = "Read the low defence threshold as a number of levels, or as a percent of the defence that can be drained (holds up in Chambers of Xeric, where starting levels scale with party size).",
		position = 14,
		section = DEFENCE
	)
	default DefenceThresholdUnit defenceLowThresholdUnit()
	{
		return DefenceThresholdUnit.LEVELS;
	}

	@Alpha
	@ConfigItem(
		keyName = "defenceHighColor",
		name = "High defence colour",
		description = "Colour when defence is above the low threshold.",
		position = 15,
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
		position = 16,
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
		position = 17,
		section = DEFENCE
	)
	default Color defenceCappedColor()
	{
		return Color.GREEN;
	}

	@Alpha
	@ConfigItem(
		keyName = "defenceDrainColor",
		name = "Drain colour",
		description = "Colour of the down arrow and the drained amount after it.",
		position = 18,
		section = DEFENCE
	)
	default Color defenceDrainColor()
	{
		return new Color(255, 80, 80);
	}

	@ConfigItem(
		keyName = "magicDefence",
		name = "Show magic defence",
		description = "Also show the monster's live magic defence as the party drains it with the accursed sceptre, Seercull, or Eye of ayak.",
		position = 19,
		section = DEFENCE
	)
	default boolean magicDefence()
	{
		return true;
	}

	@ConfigItem(
		keyName = "magicDefenceDisplay",
		name = "Magic defence as",
		description = "Show the magic-defence bonus (the number the Eye of ayak drains), the Magic level (drained by the accursed sceptre and Seercull), the percentage of the starting magic-defence roll (which reflects both), or bonus and percentage together.",
		position = 20,
		section = DEFENCE
	)
	default MagicDefenceDisplay magicDefenceDisplay()
	{
		return MagicDefenceDisplay.BONUS;
	}

	@ConfigItem(
		keyName = "magicDefenceSameRow",
		name = "Magic defence on same row",
		description = "Draw the magic-defence readout beside the Defence readout instead of on a second line.",
		position = 21,
		section = DEFENCE
	)
	default boolean magicDefenceSameRow()
	{
		return false;
	}

	@Alpha
	@ConfigItem(
		keyName = "magicDefenceColor",
		name = "Magic defence colour",
		description = "Colour of the magic-defence readout.",
		position = 22,
		section = DEFENCE
	)
	default Color magicDefenceColor()
	{
		return new Color(120, 180, 255);
	}

	@ConfigItem(
		keyName = "defenceOutsideParty",
		name = "Track outside a party",
		description = "Keep tracking your own defence drains when you aren't in a party. Turn this off to only show the defence tracker during party content.",
		position = 23,
		section = DEFENCE
	)
	default boolean defenceOutsideParty()
	{
		return true;
	}
}
