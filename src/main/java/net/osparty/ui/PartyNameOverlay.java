package net.osparty.ui;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.osparty.OSPartyConfig;
import net.osparty.party.LivePartyBackend;
import net.osparty.party.PlayerNames;
import net.osparty.party.RosterMember;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.party.PartyService;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Draws each party member's name above their head in the scene, so you can pick them out of a crowd.
 * Your own player is skipped; you know where you are.
 */
public class PartyNameOverlay extends Overlay
{
	/** Height above the player model to place the name, matching RuneLite's own name overlays. */
	private static final int NAME_HEIGHT = 40;

	private final Client client;
	private final LivePartyBackend liveParty;
	private final OSPartyConfig config;
	private final PlayerIndicators indicators;
	/** Raw scene name -> matching key. Scene names repeat every frame, so normalising is cached. */
	private final Map<String, String> normalizedNames = new HashMap<>();
	private Set<String> members = Collections.emptySet();
	private int membersTick = -1;

	public PartyNameOverlay(Client client, LivePartyBackend liveParty, OSPartyConfig config,
		PluginManager pluginManager, ConfigManager configManager, PartyService partyService)
	{
		this.client = client;
		this.liveParty = liveParty;
		this.config = config;
		this.indicators = new PlayerIndicators(client, pluginManager, configManager, partyService);
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.partyNameIndicators() || !liveParty.isInParty())
		{
			return null;
		}
		Set<String> members = memberNames();
		if (members.isEmpty())
		{
			return null;
		}

		Player local = client.getLocalPlayer();
		for (Player player : client.getPlayers())
		{
			if (player == null || player == local || player.getName() == null)
			{
				continue;
			}
			if (!members.contains(normalized(player.getName())))
			{
				continue;
			}
			// Player Indicators names sit at exactly this height, so leave theirs alone rather than
			// printing the same name through it.
			if (indicators.namesOverhead(player))
			{
				continue;
			}
			// The nbsp RuneLite uses for spaces in names draws as a missing-glyph box in the overlay font.
			String name = player.getName().replace(' ', ' ');
			Point location = player.getCanvasTextLocation(graphics, name, player.getLogicalHeight() + NAME_HEIGHT);
			if (location != null)
			{
				OverlayUtil.renderTextLocation(graphics, location, name, config.partyNameColor());
			}
		}
		return null;
	}

	/**
	 * Match keys for every seated member except us. The roster is rebuilt and sorted on every call, so it
	 * is only re-read once a game tick rather than once a frame.
	 */
	private Set<String> memberNames()
	{
		int tick = client.getTickCount();
		if (tick == membersTick)
		{
			return members;
		}
		membersTick = tick;
		Set<String> names = new HashSet<>();
		for (RosterMember member : liveParty.roster())
		{
			if (member == null || member.isLocal() || member.getName() == null)
			{
				continue;
			}
			names.add(PlayerNames.normalize(member.getName()));
		}
		members = names;
		return names;
	}

	private String normalized(String name)
	{
		String key = normalizedNames.get(name);
		if (key == null)
		{
			if (normalizedNames.size() > 512)
			{
				normalizedNames.clear();
			}
			key = PlayerNames.normalize(name);
			normalizedNames.put(name, key);
		}
		return key;
	}
}
