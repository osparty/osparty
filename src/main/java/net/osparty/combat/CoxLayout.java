package net.osparty.combat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.InstanceTemplates;
import net.runelite.api.NullObjectID;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.Varbits;
import net.runelite.api.WorldView;
import net.runelite.client.plugins.raids.RaidRoom;

/**
 * Reads the current Chambers of Xeric raid's room layout from the loaded scene
 * and renders it as a human-readable rotation (combat and puzzle rooms in order),
 * e.g. "Tekton, Crabs, Tightrope, Vasa, Vespula, Mystics, Muttadiles".
 *
 * <p>Scene-scanning logic adapted from RuneLite's core Raids plugin / the
 * community Party Defence Tracker (BSD-2). Returns null when not in a raid or the
 * layout isn't readable yet (e.g. the scene hasn't finished loading).
 */
public final class CoxLayout
{
	private static final int ROOM_MAX_SIZE = 32;
	private static final int LOBBY_PLANE = 3;
	private static final int SECOND_FLOOR_PLANE = 2;
	private static final int ROOMS_PER_PLANE = 8;
	private static final int ROOMS_PER_X = 4;
	private static final int ROOM_COUNT = 16;

	/** Rooms that aren't part of the "rotation" worth advertising. */
	private static final Set<RaidRoom> SKIP = EnumSet.of(
		RaidRoom.EMPTY, RaidRoom.START, RaidRoom.END, RaidRoom.SCAVENGERS, RaidRoom.FARMING);

	private CoxLayout()
	{
	}

	/** @return the readable raid layout, or null if not in a raid / not yet readable. Client thread. */
	public static String compute(Client client)
	{
		if (client.getVarbitValue(Varbits.IN_RAID) != 1)
		{
			return null;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null || wv.getScene() == null)
		{
			return null;
		}
		Point gridBase = findLobbyBase(wv);
		if (gridBase == null)
		{
			return null;
		}
		Integer lobbyIndex = findLobbyIndex(wv, gridBase);
		if (lobbyIndex == null)
		{
			return null;
		}

		List<String> rooms = new ArrayList<>();
		int baseX = lobbyIndex % ROOMS_PER_X;
		int baseY = lobbyIndex % ROOMS_PER_PLANE > (ROOMS_PER_X - 1) ? 1 : 0;
		Tile[][][] tiles = wv.getScene().getTiles();

		for (int i = 0; i < ROOM_COUNT; i++)
		{
			int gx = i % ROOMS_PER_X;
			int gy = i % ROOMS_PER_PLANE > (ROOMS_PER_X - 1) ? 1 : 0;
			int plane = i > (ROOMS_PER_PLANE - 1) ? SECOND_FLOOR_PLANE : LOBBY_PLANE;

			int x = gridBase.getX() + (gx - baseX) * ROOM_MAX_SIZE;
			int y = gridBase.getY() - (gy - baseY) * ROOM_MAX_SIZE;

			if (x < (1 - ROOM_MAX_SIZE) || x >= Constants.SCENE_SIZE)
			{
				continue;
			}
			x = Math.max(1, x);
			y = Math.max(1, y);

			Tile tile = tiles[plane][x][y];
			if (tile == null)
			{
				continue;
			}
			RaidRoom room = determineRoom(client, tile);
			if (!SKIP.contains(room))
			{
				rooms.add(room.getName());
			}
		}
		return rooms.isEmpty() ? null : String.join(", ", rooms);
	}

	private static Point findLobbyBase(WorldView wv)
	{
		Tile[][] tiles = wv.getScene().getTiles()[LOBBY_PLANE];
		for (int x = 0; x < Constants.SCENE_SIZE; x++)
		{
			for (int y = 0; y < Constants.SCENE_SIZE; y++)
			{
				if (tiles[x][y] == null || tiles[x][y].getWallObject() == null)
				{
					continue;
				}
				if (tiles[x][y].getWallObject().getId() == NullObjectID.NULL_12231)
				{
					return tiles[x][y].getSceneLocation();
				}
			}
		}
		return null;
	}

	private static Integer findLobbyIndex(WorldView wv, Point gridBase)
	{
		if (Constants.SCENE_SIZE <= gridBase.getX() + ROOM_MAX_SIZE
			|| Constants.SCENE_SIZE <= gridBase.getY() + ROOM_MAX_SIZE)
		{
			return null;
		}
		Tile[][] tiles = wv.getScene().getTiles()[LOBBY_PLANE];
		int y = tiles[gridBase.getX()][gridBase.getY() + ROOM_MAX_SIZE] == null ? 0 : 1;
		int x;
		if (tiles[gridBase.getX() + ROOM_MAX_SIZE][gridBase.getY()] == null)
		{
			x = 3;
		}
		else
		{
			for (x = 0; x < 3; x++)
			{
				int sceneX = gridBase.getX() - 1 - ROOM_MAX_SIZE * x;
				if (sceneX < 0 || tiles[sceneX][gridBase.getY()] == null)
				{
					break;
				}
			}
		}
		return x + y * ROOMS_PER_X;
	}

	private static RaidRoom determineRoom(Client client, Tile base)
	{
		int chunk = client.getTopLevelWorldView().getInstanceTemplateChunks()
			[base.getPlane()][base.getSceneLocation().getX() / 8][base.getSceneLocation().getY() / 8];
		InstanceTemplates template = InstanceTemplates.findMatch(chunk);
		if (template == null)
		{
			return RaidRoom.EMPTY;
		}
		switch (template)
		{
			case RAIDS_LOBBY:
			case RAIDS_START:
				return RaidRoom.START;
			case RAIDS_END:
				return RaidRoom.END;
			case RAIDS_SCAVENGERS:
			case RAIDS_SCAVENGERS2:
				return RaidRoom.SCAVENGERS;
			case RAIDS_SHAMANS:
				return RaidRoom.SHAMANS;
			case RAIDS_VASA:
				return RaidRoom.VASA;
			case RAIDS_VANGUARDS:
				return RaidRoom.VANGUARDS;
			case RAIDS_ICE_DEMON:
				return RaidRoom.ICE_DEMON;
			case RAIDS_THIEVING:
				return RaidRoom.THIEVING;
			case RAIDS_FARMING:
			case RAIDS_FARMING2:
				return RaidRoom.FARMING;
			case RAIDS_MUTTADILES:
				return RaidRoom.MUTTADILES;
			case RAIDS_MYSTICS:
				return RaidRoom.MYSTICS;
			case RAIDS_TEKTON:
				return RaidRoom.TEKTON;
			case RAIDS_TIGHTROPE:
				return RaidRoom.TIGHTROPE;
			case RAIDS_GUARDIANS:
				return RaidRoom.GUARDIANS;
			case RAIDS_CRABS:
				return RaidRoom.CRABS;
			case RAIDS_VESPULA:
				return RaidRoom.VESPULA;
			default:
				return RaidRoom.EMPTY;
		}
	}
}
