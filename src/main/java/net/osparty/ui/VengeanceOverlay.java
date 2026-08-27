package net.osparty.ui;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.osparty.OSPartyConfig;
import net.osparty.party.LivePartyBackend;
import net.osparty.party.PartyStatus;
import net.osparty.party.PlayerNames;
import net.osparty.party.PlayerUpdate;
import net.osparty.party.RosterMember;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.gameval.SpriteID;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Draws the Vengeance icon on party members who have the spell up, where RuneLite's own party plugin puts
 * it: on the model, halfway up. Your own player is skipped; the spell's timer already tells you.
 */
public class VengeanceOverlay extends Overlay
{
	private final Client client;
	private final LivePartyBackend liveParty;
	private final OSPartyConfig config;
	private final SpriteManager spriteManager;
	/** Raw scene name -> matching key. Scene names repeat every frame, so normalising is cached. */
	private final Map<String, String> normalizedNames = new HashMap<>();
	private BufferedImage icon;
	private Set<String> vengeful = Collections.emptySet();
	private int vengefulTick = -1;

	public VengeanceOverlay(Client client, LivePartyBackend liveParty, OSPartyConfig config,
		SpriteManager spriteManager)
	{
		this.client = client;
		this.liveParty = liveParty;
		this.config = config;
		this.spriteManager = spriteManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.vengeanceIcons() || !liveParty.isInParty())
		{
			return null;
		}
		Set<String> members = vengefulMembers();
		if (members.isEmpty())
		{
			return null;
		}
		BufferedImage icon = icon();
		if (icon == null)
		{
			return null;
		}

		Player local = client.getLocalPlayer();
		for (Player player : client.getPlayers())
		{
			if (player == null || player == local || player.getName() == null
				|| !members.contains(normalized(player.getName())))
			{
				continue;
			}
			Point location = player.getCanvasImageLocation(icon, player.getLogicalHeight() / 2);
			if (location != null)
			{
				OverlayUtil.renderImageLocation(graphics, location, icon);
			}
		}
		return null;
	}

	/** The sprite, fetched from the game cache once it is loaded and kept from then on. */
	private BufferedImage icon()
	{
		if (icon == null)
		{
			icon = spriteManager.getSprite(SpriteID.LunarMagicOn.VENGEANCE_OTHER, 0);
		}
		return icon;
	}

	/**
	 * Match keys for every seated member with Vengeance up, except us. The roster is rebuilt and sorted on
	 * every call, so it is only re-read once a game tick rather than once a frame.
	 */
	private Set<String> vengefulMembers()
	{
		int tick = client.getTickCount();
		if (tick == vengefulTick)
		{
			return vengeful;
		}
		vengefulTick = tick;
		Set<String> names = new HashSet<>();
		for (RosterMember member : liveParty.roster())
		{
			PlayerUpdate data = member.getData();
			if (member.isLocal() || member.getStatus() == PartyStatus.PENDING || member.getName() == null
				|| data == null || !data.isVengeance())
			{
				continue;
			}
			names.add(PlayerNames.normalize(member.getName()));
		}
		vengeful = names;
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
