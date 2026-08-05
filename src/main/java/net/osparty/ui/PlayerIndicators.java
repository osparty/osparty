package net.osparty.ui;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.party.PartyService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.playerindicators.PlayerIndicatorsConfig;
import net.runelite.client.plugins.playerindicators.PlayerIndicatorsConfig.HighlightSetting;
import net.runelite.client.plugins.playerindicators.PlayerNameLocation;

/**
 * Whether RuneLite's own Player Indicators plugin already draws a name above a player. It places
 * overhead names at exactly the height we use, so ours would print straight through its text.
 *
 * <p>The rules mirror that plugin's own highlight chain; there's no way to ask it what it drew.
 */
class PlayerIndicators
{
	private static final String PLUGIN_CLASS = "net.runelite.client.plugins.playerindicators.PlayerIndicatorsPlugin";

	private final Client client;
	private final PluginManager pluginManager;
	private final ConfigManager configManager;
	private final PartyService partyService;

	private Plugin plugin;
	private PlayerIndicatorsConfig config;
	private boolean unavailable;

	PlayerIndicators(Client client, PluginManager pluginManager, ConfigManager configManager,
		PartyService partyService)
	{
		this.client = client;
		this.pluginManager = pluginManager;
		this.configManager = configManager;
		this.partyService = partyService;
	}

	/** True when Player Indicators is on and would name {@code player} above their head. */
	boolean namesOverhead(Player player)
	{
		if (player == null || player.getName() == null || !enabled())
		{
			return false;
		}
		PlayerIndicatorsConfig indicators = config();
		// Only the above-head position collides; the model-centre/right ones sit halfway down the model.
		if (indicators == null || indicators.playerNamePosition() != PlayerNameLocation.ABOVE_HEAD)
		{
			return false;
		}
		return highlighted(player, indicators);
	}

	/** The highlight chain from PlayerIndicatorsService: the first setting that matches wins a colour. */
	private boolean highlighted(Player player, PlayerIndicatorsConfig indicators)
	{
		Player local = client.getLocalPlayer();
		if (player == local)
		{
			return on(indicators.highlightOwnPlayer());
		}
		if (partyService.isInParty() && on(indicators.highlightPartyMembers())
			&& partyService.getMemberByDisplayName(player.getName()) != null)
		{
			return true;
		}
		if (player.isFriend() && on(indicators.highlightFriends()))
		{
			return true;
		}
		if (player.isFriendsChatMember() && on(indicators.highlightFriendsChat()))
		{
			return true;
		}
		if (player.getTeam() > 0 && local != null && local.getTeam() == player.getTeam()
			&& on(indicators.highlightTeamMembers()))
		{
			return true;
		}
		if (player.isClanMember() && on(indicators.highlightClanMembers()))
		{
			return true;
		}
		return !player.isFriendsChatMember() && !player.isClanMember() && on(indicators.highlightOthers());
	}

	private boolean on(HighlightSetting setting)
	{
		if (setting == HighlightSetting.ENABLED)
		{
			return true;
		}
		return setting == HighlightSetting.PVP
			&& (client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1
			|| client.getVarbitValue(VarbitID.PVP_AREA_CLIENT) == 1);
	}

	private boolean enabled()
	{
		if (unavailable || pluginManager == null)
		{
			return false;
		}
		if (plugin == null)
		{
			for (Plugin candidate : pluginManager.getPlugins())
			{
				if (PLUGIN_CLASS.equals(candidate.getClass().getName()))
				{
					plugin = candidate;
					break;
				}
			}
			if (plugin == null)
			{
				unavailable = true;
				return false;
			}
		}
		return pluginManager.isPluginEnabled(plugin);
	}

	/** Live proxy over the plugin's settings, so switching a highlight off takes effect immediately. */
	private PlayerIndicatorsConfig config()
	{
		if (config == null && configManager != null)
		{
			config = configManager.getConfig(PlayerIndicatorsConfig.class);
		}
		return config;
	}
}
