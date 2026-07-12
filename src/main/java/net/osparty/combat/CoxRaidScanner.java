package net.osparty.combat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.InstanceTemplates;
import net.runelite.api.NullObjectID;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.Varbits;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.raids.RaidRoom;
import net.runelite.client.plugins.raids.RoomType;

/**
 * Resolves the full Chambers of Xeric room layout while in a raid.
 *
 * <p>A single scene scan only sees the rooms loaded around the player, so the
 * whole raid can't be read directly. Like RuneLite's core Raids plugin, we scan
 * what's visible into a 16-slot grid, build a partial room-type code, and match
 * it against a database of all known layouts to recover every room's position.
 * The combat-room rotation is then solved from the known rooms via the four
 * fixed CoX rotations; rooms still not identified read as "Unknown (...)".
 *
 * <p>The layout database and matcher are reimplemented here (rather than reusing
 * RuneLite's {@code LayoutSolver}) on purpose: that class keeps its layouts in a
 * mutable static list rebuilt on every construction, so instantiating our own
 * would duplicate the entries the Raids plugin already built and break its
 * single-match guarantee. The matcher is the WooxSolo raids-layout algorithm.
 */
@Slf4j
@Singleton
public class CoxRaidScanner
{
	private static final int LOBBY_PLANE = 3;
	private static final int SECOND_FLOOR_PLANE = 2;
	private static final int ROOMS_PER_PLANE = 8;
	private static final int ROOMS_PER_X = 4;
	private static final int ROOM_MAX_SIZE = 32;
	private static final int ROOM_COUNT = 16;
	private static final int SCENE_SIZE = Constants.SCENE_SIZE;

	/** Known CoX layouts, "floor0.floor1 - #dirs0#dirs1" (WooxSolo raids-layout data). */
	private static final String[] CODES =
	{
		"FSCCP.PCSCF - #WNWSWN#ESEENW", "FSCCS.PCPSF - #WSEEEN#WSWNWS",
		"FSCPC.CSCPF - #WNWWSE#EENWWW", "SCCFC.PSCSF - #EEENWW#WSEEEN",
		"SCCFP.CCSPF - #NESEEN#WSWNWS", "SCFCP.CCSPF - #ESEENW#ESWWNW",
		"SCFCP.CSCFS - #ENEESW#ENWWSW", "SCFCPC.CSPCSF - #ESWWNWS#NESENES",
		"SCFPC.CSPCF - #WSWWNE#WSEENE", "SCFPC.PCCSF - #WSEENE#WWWSEE",
		"SCFPC.SCPCF - #NESENE#WSWWNE", "SCPFC.CCPSF - #NWWWSE#WNEESE",
		"SCPFC.CSPCF - #NEEESW#WWNEEE", "SCPFC.CSPSF - #WWSEEE#NWSWWN",
		"SCSPF.CCSPF - #ESWWNW#ESENES", "SFCCP.CSCPF - #WNEESE#NWSWWN",
		"SFCCS.PCPSF - #ENWWSW#ENESEN", "SPCFC.CSPCF - #WWNEEE#WSWNWS",
		"SPCFC.SCCPF - #ESENES#WWWNEE", "SPSFP.CCCSF - #NWSWWN#ESEENW",
		"SCFCP.CSCPF - #ENESEN#WWWSEE", "SCPFC.PCSCF - #WNEEES#NWSWNW",
		"SFCCPC.PCSCPF - #WSEENES#WWWNEEE", "FSPCC.PSCCF - #WWWSEE#ENWWSW",
		"FSCCP.PCSCF - #ENWWWS#NEESEN", "SCPFC.CCSSF - #NEESEN#WSWWNE",
	};
	private static final Pattern REGEX = Pattern.compile("^([A-Z]*)\\.([A-Z]*) - #([A-Z]*)#([A-Z]*)$");

	/** The four fixed CoX combat-room rotations, used to fill unscouted combat rooms. */
	private static final List<List<RaidRoom>> ROTATIONS = Arrays.asList(
		Arrays.asList(RaidRoom.TEKTON, RaidRoom.VASA, RaidRoom.GUARDIANS, RaidRoom.MYSTICS,
			RaidRoom.SHAMANS, RaidRoom.MUTTADILES, RaidRoom.VANGUARDS, RaidRoom.VESPULA),
		Arrays.asList(RaidRoom.TEKTON, RaidRoom.MUTTADILES, RaidRoom.GUARDIANS, RaidRoom.VESPULA,
			RaidRoom.SHAMANS, RaidRoom.VASA, RaidRoom.VANGUARDS, RaidRoom.MYSTICS),
		Arrays.asList(RaidRoom.VESPULA, RaidRoom.VANGUARDS, RaidRoom.MUTTADILES, RaidRoom.SHAMANS,
			RaidRoom.MYSTICS, RaidRoom.GUARDIANS, RaidRoom.VASA, RaidRoom.TEKTON),
		Arrays.asList(RaidRoom.MYSTICS, RaidRoom.VANGUARDS, RaidRoom.VASA, RaidRoom.SHAMANS,
			RaidRoom.VESPULA, RaidRoom.GUARDIANS, RaidRoom.MUTTADILES, RaidRoom.TEKTON));

	/** A known layout: room positions (0-15) in raid order, each with a type symbol. */
	private static final class CoxLayout
	{
		final List<int[]> ordered = new ArrayList<>(); // {position, symbol}
		final Map<Integer, Character> byPosition = new HashMap<>();

		void add(int position, char symbol)
		{
			ordered.add(new int[]{position, symbol});
			byPosition.put(position, symbol);
		}
	}

	private final Client client;
	private final List<CoxLayout> layouts = new ArrayList<>();

	/** Accumulated rooms by grid index (null = not yet scanned). */
	private final RaidRoom[] rooms = new RaidRoom[ROOM_COUNT];
	private boolean haveBase;
	/**
	 * Lobby south-west tile in world coordinates. The anchor is only valid for the
	 * scene it was measured in: a scene reload (e.g. climbing the raid stairs) shifts
	 * the world base, so we also record that base ({@link #anchorSceneBaseX}/Y) and
	 * re-locate the lobby whenever it changes rather than projecting stale tiles.
	 */
	private int lobbyBaseX;
	private int lobbyBaseY;
	/** Scene base (client.getBaseX()/Y) the lobby anchor was captured against. */
	private int anchorSceneBaseX;
	private int anchorSceneBaseY;
	private int baseX;
	private int baseY;
	private CoxLayout solvedLayout;
	private String cachedLayout;

	@Inject
	private CoxRaidScanner(Client client)
	{
		this.client = client;
		buildLayouts();
	}

	/** Scan the scene and (re)solve the layout; resets when not in a raid. Client thread. */
	public void update()
	{
		int inRaid = client.getVarbitValue(Varbits.IN_RAID);
		GameState state = client.getGameState();
		boolean sceneNull = client.getScene() == null;
		if (inRaid != 1 || state != GameState.LOGGED_IN || sceneNull)
		{
			// TEMP DIAG: why did we bail/reset? Watch this line when clicking the stairs.
			logState("bail inRaid=" + inRaid + " state=" + state + " sceneNull=" + sceneNull);
			reset();
			return;
		}

		// A scene reload (climbing the raid stairs) shifts the world base, which
		// invalidates the lobby anchor captured against the old scene: projecting the
		// room grid with it would scan the wrong tiles and corrupt the solved layout.
		// Detect the base change and re-locate the lobby against the current scene. The
		// solved layout and accumulated rooms are raid-stable, so they are kept.
		if (haveBase && (client.getBaseX() != anchorSceneBaseX || client.getBaseY() != anchorSceneBaseY))
		{
			logState("re-anchor (scene reload: base moved)");
			haveBase = false;
		}

		if (!haveBase && !locateLobby())
		{
			logState("no-anchor (locateLobby failed)");
			return;
		}
		scanRooms();

		if (solvedLayout == null)
		{
			CoxLayout layout = findLayout(toCode());
			if (layout == null)
			{
				logState("unsolved code=[" + toCode() + "]");
				return; // not enough scanned to uniquely match yet - keep accumulating
			}
			solvedLayout = layout;
			fillUnsolvedRooms(layout);
		}

		RaidRoom[] combat = combatRooms(solvedLayout);
		solveRotation(combat);
		setCombatRooms(solvedLayout, combat);
		cachedLayout = orderedRooms(solvedLayout);
		logState("cached=[" + cachedLayout + "]");
	}

	// ---- TEMP DIAG: log only on a state change so it isn't per-tick spam. Remove once fixed. ----
	private String lastLogged;

	private void logState(String msg)
	{
		String line = "haveBase=" + haveBase + " solved=" + (solvedLayout != null) + " " + msg;
		if (!line.equals(lastLogged))
		{
			lastLogged = line;
			log.info("[coxdiag] {}", line);
		}
	}

	/** @return the solved raid rotation (combat + puzzle rooms in order), or null. */
	public String layout()
	{
		return cachedLayout;
	}

	private void reset()
	{
		if (!haveBase && cachedLayout == null && solvedLayout == null)
		{
			return;
		}
		haveBase = false;
		solvedLayout = null;
		cachedLayout = null;
		Arrays.fill(rooms, null);
	}

	// ---- scanning (adapted from RaidsPlugin) ---------------------------------

	private boolean locateLobby()
	{
		Point base = findLobbyBase();
		if (base == null)
		{
			return false;
		}
		Integer lobbyIndex = findLobbyIndex(base);
		if (lobbyIndex == null)
		{
			return false;
		}
		// Capture the lobby anchor in world coordinates together with the scene base it
		// was measured against, so a later base shift (scene reload) is detected in
		// update() and triggers a re-locate instead of silently scanning wrong tiles.
		this.lobbyBaseX = client.getBaseX() + base.getX();
		this.lobbyBaseY = client.getBaseY() + base.getY();
		this.anchorSceneBaseX = client.getBaseX();
		this.anchorSceneBaseY = client.getBaseY();
		this.baseX = lobbyIndex % ROOMS_PER_X;
		this.baseY = lobbyIndex % ROOMS_PER_PLANE > (ROOMS_PER_X - 1) ? 1 : 0;
		haveBase = true;
		return true;
	}

	private void scanRooms()
	{
		Tile[][][] tiles = client.getScene().getTiles();
		for (int i = 0; i < ROOM_COUNT; i++)
		{
			int gx = i % ROOMS_PER_X;
			int gy = i % ROOMS_PER_PLANE > (ROOMS_PER_X - 1) ? 1 : 0;
			int plane = i > (ROOMS_PER_PLANE - 1) ? SECOND_FLOOR_PLANE : LOBBY_PLANE;

			int x = lobbyBaseX + (gx - baseX) * ROOM_MAX_SIZE - client.getBaseX();
			int y = lobbyBaseY - (gy - baseY) * ROOM_MAX_SIZE - client.getBaseY();

			if (x < (1 - ROOM_MAX_SIZE) || x >= SCENE_SIZE)
			{
				continue;
			}
			x = Math.max(1, x);
			y = Math.max(1, y);
			if (y >= SCENE_SIZE)
			{
				continue;
			}

			Tile tile = tiles[plane][x][y];
			if (tile == null)
			{
				continue;
			}
			RaidRoom scanned = determineRoom(tile);
			// Don't let a stray EMPTY clobber a room we already know.
			if (rooms[i] == null || scanned != RaidRoom.EMPTY)
			{
				rooms[i] = scanned;
			}
		}
	}

	private Point findLobbyBase()
	{
		Tile[][] tiles = client.getScene().getTiles()[LOBBY_PLANE];
		for (int x = 0; x < SCENE_SIZE; x++)
		{
			for (int y = 0; y < SCENE_SIZE; y++)
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

	private Integer findLobbyIndex(Point base)
	{
		if (SCENE_SIZE <= base.getX() + ROOM_MAX_SIZE || SCENE_SIZE <= base.getY() + ROOM_MAX_SIZE)
		{
			return null;
		}
		Tile[][] tiles = client.getScene().getTiles()[LOBBY_PLANE];
		int y = tiles[base.getX()][base.getY() + ROOM_MAX_SIZE] == null ? 0 : 1;
		int x;
		if (tiles[base.getX() + ROOM_MAX_SIZE][base.getY()] == null)
		{
			x = 3;
		}
		else
		{
			for (x = 0; x < 3; x++)
			{
				int sceneX = base.getX() - 1 - ROOM_MAX_SIZE * x;
				if (sceneX < 0 || tiles[sceneX][base.getY()] == null)
				{
					break;
				}
			}
		}
		return x + y * ROOMS_PER_X;
	}

	private RaidRoom determineRoom(Tile base)
	{
		int chunk = client.getInstanceTemplateChunks()[base.getPlane()]
			[base.getSceneLocation().getX() / 8][base.getSceneLocation().getY() / 8];
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

	// ---- layout solving ------------------------------------------------------

	private String toCode()
	{
		StringBuilder sb = new StringBuilder(ROOM_COUNT);
		for (RaidRoom room : rooms)
		{
			sb.append(room == null ? ' ' : room.getType().getCode());
		}
		return sb.toString();
	}

	/** @return the unique layout matching the (partial) code, or null if 0 or many match. */
	private CoxLayout findLayout(String code)
	{
		CoxLayout solution = null;
		int matches = 0;
		for (CoxLayout layout : layouts)
		{
			boolean match = true;
			for (int i = 0; i < code.length(); i++)
			{
				Character symbol = layout.byPosition.get(i);
				char c = code.charAt(i);
				if (symbol != null && c != ' ' && c != symbol)
				{
					match = false;
					break;
				}
			}
			if (match)
			{
				solution = layout;
				matches++;
			}
		}
		return matches == 1 ? solution : null;
	}

	private void fillUnsolvedRooms(CoxLayout layout)
	{
		for (int[] entry : layout.ordered)
		{
			int position = entry[0];
			if (position < ROOM_COUNT && rooms[position] == null)
			{
				rooms[position] = unsolvedRoom((char) entry[1]);
			}
		}
	}

	private RaidRoom[] combatRooms(CoxLayout layout)
	{
		List<RaidRoom> combat = new ArrayList<>();
		for (int[] entry : layout.ordered)
		{
			RaidRoom room = roomAt(entry[0]);
			if (room != null && room.getType() == RoomType.COMBAT)
			{
				combat.add(room);
			}
		}
		return combat.toArray(new RaidRoom[0]);
	}

	private void setCombatRooms(CoxLayout layout, RaidRoom[] combat)
	{
		int index = 0;
		for (int[] entry : layout.ordered)
		{
			int position = entry[0];
			RaidRoom room = roomAt(position);
			if (room != null && room.getType() == RoomType.COMBAT && index < combat.length)
			{
				rooms[position] = combat[index++];
			}
		}
	}

	private String orderedRooms(CoxLayout layout)
	{
		StringBuilder sb = new StringBuilder();
		for (int[] entry : layout.ordered)
		{
			RaidRoom room = roomAt(entry[0]);
			if (room == null)
			{
				continue;
			}
			if (room.getType() == RoomType.COMBAT || room.getType() == RoomType.PUZZLE)
			{
				sb.append(room.getName()).append(", ");
			}
		}
		return sb.length() < 2 ? null : sb.substring(0, sb.length() - 2);
	}

	private RaidRoom roomAt(int position)
	{
		return position >= 0 && position < ROOM_COUNT ? rooms[position] : null;
	}

	private static RaidRoom unsolvedRoom(char symbol)
	{
		switch (symbol)
		{
			case '#':
				return RaidRoom.START;
			case '¤':
				return RaidRoom.END;
			case 'S':
				return RaidRoom.SCAVENGERS;
			case 'F':
				return RaidRoom.FARMING;
			case 'C':
				return RaidRoom.UNKNOWN_COMBAT;
			case 'P':
				return RaidRoom.UNKNOWN_PUZZLE;
			default:
				return RaidRoom.EMPTY;
		}
	}

	/** Fill unknown combat rooms by matching the known ones against the four rotations. */
	private static void solveRotation(RaidRoom[] combat)
	{
		if (combat == null)
		{
			return;
		}
		Integer start = null;
		int known = 0;
		for (int i = 0; i < combat.length; i++)
		{
			if (combat[i] == null || combat[i].getType() != RoomType.COMBAT || combat[i] == RaidRoom.UNKNOWN_COMBAT)
			{
				continue;
			}
			if (start == null)
			{
				start = i;
			}
			known++;
		}
		if (known < 2 || known == combat.length)
		{
			return;
		}

		List<RaidRoom> match = null;
		Integer index = null;
		for (List<RaidRoom> rotation : ROTATIONS)
		{
			compare:
			for (int i = 0; i < rotation.size(); i++)
			{
				if (combat[start] == rotation.get(i))
				{
					for (int j = start + 1; j < combat.length; j++)
					{
						if (combat[j].getType() != RoomType.COMBAT || combat[j] == RaidRoom.UNKNOWN_COMBAT)
						{
							continue;
						}
						if (combat[j] != rotation.get(Math.floorMod(i + j - start, rotation.size())))
						{
							break compare;
						}
					}
					if (match != null && match != rotation)
					{
						return; // ambiguous
					}
					index = i - start;
					match = rotation;
				}
			}
		}
		if (match == null)
		{
			return;
		}
		for (int i = 0; i < combat.length; i++)
		{
			if (combat[i] == null)
			{
				continue;
			}
			if (combat[i].getType() != RoomType.COMBAT || combat[i] == RaidRoom.UNKNOWN_COMBAT)
			{
				combat[i] = match.get(Math.floorMod(index + i, match.size()));
			}
		}
	}

	// ---- layout database (WooxSolo raids-layout algorithm) -------------------

	private void buildLayouts()
	{
		for (String code : CODES)
		{
			Matcher matcher = REGEX.matcher(code);
			if (!matcher.find())
			{
				continue;
			}
			int position = calcStart(matcher.group(3));
			CoxLayout layout = new CoxLayout();
			for (int floor = 0; floor < 2; floor++)
			{
				String symbols = matcher.group(1 + floor);
				String directions = matcher.group(3 + floor);
				for (int i = 0; i < directions.length(); i++)
				{
					char symbol = i == 0 ? '#' : symbols.charAt(i - 1);
					layout.add(position, symbol);
					position += dirToPosDelta(directions.charAt(i));
				}
				layout.add(position, '¤');
				position += 8;
			}
			layouts.add(layout);
		}
	}

	private int calcStart(String directions)
	{
		int startPos = 0;
		int position = 0;
		for (int i = 0; i < directions.length(); i++)
		{
			int delta = dirToPosDelta(directions.charAt(i));
			position += delta;
			if (position < 0 || position >= 8 || (position == 3 && delta == -1) || (position == 4 && delta == 1))
			{
				position -= delta;
				startPos -= delta;
			}
		}
		return startPos;
	}

	private int dirToPosDelta(char direction)
	{
		switch (direction)
		{
			case 'N':
				return -4;
			case 'E':
				return 1;
			case 'S':
				return 4;
			case 'W':
				return -1;
			default:
				return 0;
		}
	}
}
