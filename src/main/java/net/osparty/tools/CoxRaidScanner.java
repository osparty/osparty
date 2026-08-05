package net.osparty.tools;

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
import net.runelite.api.gameval.VarbitID;
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
	/**
	 * Which grid slots the scanner has actually read a room out of, as opposed to filled in.
	 *
	 * <p>{@link #setCombatRooms} writes the rotation's answer for every combat slot back into
	 * {@code rooms}, which leaves a guess sitting there looking exactly like an observation. Counted as
	 * one, it makes {@link #solveRotation} believe the raid is fully known, and a solver that believes
	 * that never looks again — so a single wrong room stays wrong for the rest of the raid, and shows up
	 * as the same boss twice. Keeping the evidence separate lets the rotation be re-derived from scratch
	 * every tick, which is what lets a later scan correct an earlier guess.
	 */
	private final boolean[] observed = new boolean[ROOM_COUNT];
	private boolean haveBase;
	/**
	 * Lobby south-west tile in world coordinates, captured once. CoX is instanced, so
	 * a tile's world coordinates stay fixed for the whole raid even though the scene
	 * base shifts on a reload (e.g. climbing the stairs); re-projecting worldX minus
	 * the current base gives the right scene tile every scan. This mirrors RuneLite's
	 * Raids plugin, which stores the lobby world point once and reuses it.
	 */
	private int lobbyBaseX;
	private int lobbyBaseY;
	private int baseX;
	private int baseY;
	/** Scene base of the last full sweep that found no lobby; static objects can't appear mid-load. */
	private int emptySweepBaseX = Integer.MIN_VALUE;
	private int emptySweepBaseY = Integer.MIN_VALUE;
	private boolean roomsDirty;
	private CoxLayout solvedLayout;
	private String cachedLayout;
	/** Last seen raid party id; -1 until the client reports one. See {@link #onRaidPartyChanged}. */
	private int raidPartyId = -1;

	@Inject
	private CoxRaidScanner(Client client)
	{
		this.client = client;
		buildLayouts();
	}

	/** Scan the scene and (re)solve the layout; resets when not in a raid. Client thread. */
	public void update()
	{
		// A scene reload (e.g. climbing the raid stairs) briefly leaves the LOGGED_IN
		// state; skip those ticks but keep the solved raid, like RuneLite's plugin,
		// which persists the raid across scene loads and only clears it when the
		// player actually leaves.
		if (client.getGameState() != GameState.LOGGED_IN || client.getScene() == null)
		{
			return;
		}
		if (client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) != 1)
		{
			reset();
			return;
		}

		// The entrance-stairs "reload" re-rolls the raid into a fresh instance, which
		// moves the lobby to a new world coordinate (otherwise invariant for the
		// raid's lifetime - the same fact that lets scanRooms() project the grid
		// across scene reloads). Reset before scanning so this tick can't read tiles
		// against the stale anchor. onInRaidChanged() catches the same re-roll via
		// the IN_RAID varbit event, but in practice that event arrives after this
		// tick's poll, so this check is what stops a garbage scan (both were observed
		// firing on every reload). findLobbyBase() is a single tile lookup while the
		// anchor still holds, so running it every tick is cheap.
		if (haveBase)
		{
			Point lobby = findLobbyBase();
			if (lobby != null
				&& (client.getBaseX() + lobby.getX() != lobbyBaseX
					|| client.getBaseY() + lobby.getY() != lobbyBaseY))
			{
				log.debug("CoX raid re-rolled (lobby moved); rescouting layout");
				reset();
			}
		}

		if (!haveBase && !locateLobby())
		{
			return;
		}
		scanRooms();
		resolve();
	}

	/**
	 * Match the layout if it isn't matched yet, then re-derive the rotation from what has been observed.
	 *
	 * <p>Package-private so the solve can be driven from a known set of room readings, without standing up
	 * a scene to read them out of.
	 */
	void resolve()
	{
		if (solvedLayout == null)
		{
			CoxLayout layout = findLayout(toCode());
			if (layout == null)
			{
				return; // not enough scanned to uniquely match yet - keep accumulating
			}
			solvedLayout = layout;
			fillUnsolvedRooms(layout);
			roomsDirty = true;
		}

		// Nothing new was scanned, so the rotation and the layout string would come out identical.
		if (!roomsDirty)
		{
			return;
		}
		roomsDirty = false;
		RaidRoom[] combat = combatRooms(solvedLayout);
		if (!solveRotation(combat))
		{
			// The rooms in hand fit no rotation, so they did not all come from the raid we are standing in
			// — a re-roll the resets missed, or a scan taken against a stale anchor. Nothing here is worth
			// keeping: drop it and scout again from the lobby, as RuneLite's Raids plugin does when its
			// own scan stops matching a layout.
			log.debug("CoX rooms fit no rotation; discarding the scout and rescouting");
			reset();
			return;
		}
		setCombatRooms(solvedLayout, combat);
		cachedLayout = orderedRooms(solvedLayout);
	}

	/**
	 * Record a room reading as {@link #scanRooms()} would have made it. Visible for testing, which is the
	 * only thing that has a raid to describe but no scene to scan.
	 */
	void observe(int position, RaidRoom room)
	{
		if (position < 0 || position >= ROOM_COUNT)
		{
			return;
		}
		roomsDirty |= rooms[position] != room || !observed[position];
		rooms[position] = room;
		observed[position] = true;
	}

	/** @return the solved raid rotation (combat + puzzle rooms in order), or null. */
	public String layout()
	{
		return cachedLayout;
	}

	/**
	 * Reset when the IN_RAID varbit leaves 1. Fed from the plugin's VarbitChanged
	 * subscription: the entrance-stairs "reload" flicks the varbit 1 -> 0 -> 1 within
	 * a single tick, which the per-tick poll in {@link #update()} never sees but the
	 * event stream does - this is how RuneLite's Raids plugin catches the re-roll.
	 * Complements the lobby-moved check in update(): that one fires first (inside the
	 * tick, guarding the scan), this one is the authoritative lifecycle signal and
	 * covers any case where the lobby isn't scannable.
	 */
	public void onInRaidChanged(int value)
	{
		if (value != 1 && solvedLayout != null)
		{
			log.debug("IN_RAID left 1 (event); resetting CoX scan");
		}
		if (value != 1)
		{
			reset();
		}
	}

	/**
	 * Reset when the raid party changes hands under us. Fed from the plugin's VarbitChanged subscription,
	 * as RuneLite's Raids plugin does it: a re-roll is a new party id, and that is a fact about the raid
	 * rather than an inference from where its lobby happens to sit. The lobby-moved check in
	 * {@link #update()} cannot see a re-roll whose lobby lands back on the tile we were already anchored
	 * to; this can.
	 */
	public void onRaidPartyChanged(int partyId)
	{
		int previous = raidPartyId;
		raidPartyId = partyId;
		if (previous != -1 && partyId != -1 && previous != partyId)
		{
			log.debug("CoX raid party changed ({} -> {}); rescouting layout", previous, partyId);
			reset();
		}
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
		roomsDirty = false;
		Arrays.fill(rooms, null);
		Arrays.fill(observed, false);
		// A reset exists to make the next scout happen. Keeping the memo of a sweep that found no lobby
		// would let it answer for a scene we are now deliberately re-reading, and one stale "nothing here"
		// is enough to keep the scanner from ever locating the lobby again.
		emptySweepBaseX = Integer.MIN_VALUE;
		emptySweepBaseY = Integer.MIN_VALUE;
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
		// Capture the lobby anchor once in world coordinates. scanRooms() re-projects it
		// against the current scene base each tick, so it stays correct across scene
		// reloads without ever re-locating (which would risk a different grid index).
		this.lobbyBaseX = client.getBaseX() + base.getX();
		this.lobbyBaseY = client.getBaseY() + base.getY();
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
			RaidRoom seen = determineRoom(tile);
			// Don't let a stray EMPTY clobber a room we already know.
			if (rooms[i] != null && seen == RaidRoom.EMPTY)
			{
				continue;
			}
			roomsDirty |= rooms[i] != seen;
			rooms[i] = seen;
			if (seen != RaidRoom.EMPTY && !observed[i])
			{
				// The first real reading of this slot. Worth re-solving for even when it agrees with what
				// was already guessed there, because the rotation now rests on one more fact than it did.
				observed[i] = true;
				roomsDirty = true;
			}
		}
	}

	private Point findLobbyBase()
	{
		Tile[][] tiles = client.getScene().getTiles()[LOBBY_PLANE];

		// The anchor we already hold projects to exactly one tile, so confirming the lobby hasn't
		// moved costs one lookup instead of sweeping all 104x104 of them. It only misses when the
		// lobby has left the scene or the raid re-rolled, which is what the sweep below is for.
		if (haveBase)
		{
			int x = lobbyBaseX - client.getBaseX();
			int y = lobbyBaseY - client.getBaseY();
			if (x >= 0 && x < SCENE_SIZE && y >= 0 && y < SCENE_SIZE && isLobbyWall(tiles[x][y]))
			{
				return tiles[x][y].getSceneLocation();
			}
		}

		// A scene's static objects don't change within one load, so once a sweep has come up empty
		// there is nothing to find until the scene reloads - which is also what a re-roll does.
		if (client.getBaseX() == emptySweepBaseX && client.getBaseY() == emptySweepBaseY)
		{
			return null;
		}

		for (int x = 0; x < SCENE_SIZE; x++)
		{
			for (int y = 0; y < SCENE_SIZE; y++)
			{
				if (isLobbyWall(tiles[x][y]))
				{
					return tiles[x][y].getSceneLocation();
				}
			}
		}
		emptySweepBaseX = client.getBaseX();
		emptySweepBaseY = client.getBaseY();
		return null;
	}

	private static boolean isLobbyWall(Tile tile)
	{
		return tile != null && tile.getWallObject() != null
			&& tile.getWallObject().getId() == NullObjectID.NULL_12231;
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
			// Treat EMPTY like "not scanned yet": a room the scanner saw but couldn't
			// identify (out of view / unvisited) reads as EMPTY. Leaving it EMPTY drops
			// it from the layout string and, for combat slots, shifts setCombatRooms so
			// the rotation duplicates/misorders. Seed it from the known layout symbol
			// (UNKNOWN_COMBAT / UNKNOWN_PUZZLE / SCAVENGERS / ...) so every room shows;
			// scanRooms refines it to the real room once the player reaches it.
			if (position < ROOM_COUNT && (rooms[position] == null || rooms[position] == RaidRoom.EMPTY))
			{
				rooms[position] = unsolvedRoom((char) entry[1]);
			}
		}
	}

	/**
	 * The layout's combat slots in raid order.
	 *
	 * <p>Taken from the matched layout rather than from what {@code rooms} currently holds, so the two
	 * halves of the solve cannot disagree about which slots they are talking about. A slot dropped from
	 * one half but not the other shifts every combat room after it onto its neighbour's place in the
	 * rotation, which is how a raid ends up advertising the same boss twice.
	 */
	private static List<Integer> combatPositions(CoxLayout layout)
	{
		List<Integer> positions = new ArrayList<>();
		for (int[] entry : layout.ordered)
		{
			if ((char) entry[1] == 'C' && entry[0] >= 0 && entry[0] < ROOM_COUNT)
			{
				positions.add(entry[0]);
			}
		}
		return positions;
	}

	/**
	 * The combat rooms as evidence rather than as belief: a slot the scanner has not read yet reads
	 * UNKNOWN_COMBAT even while it holds a perfectly plausible guess from an earlier rotation solve.
	 * That is what keeps {@link #solveRotation} honest about how much it actually knows.
	 */
	private RaidRoom[] combatRooms(CoxLayout layout)
	{
		List<Integer> positions = combatPositions(layout);
		RaidRoom[] combat = new RaidRoom[positions.size()];
		for (int i = 0; i < combat.length; i++)
		{
			int position = positions.get(i);
			RaidRoom room = observed[position] ? rooms[position] : null;
			combat[i] = room == null ? RaidRoom.UNKNOWN_COMBAT : room;
		}
		return combat;
	}

	private void setCombatRooms(CoxLayout layout, RaidRoom[] combat)
	{
		List<Integer> positions = combatPositions(layout);
		for (int i = 0; i < positions.size() && i < combat.length; i++)
		{
			rooms[positions.get(i)] = combat[i];
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

	/**
	 * Fill unknown combat rooms by matching the known ones against the four rotations.
	 *
	 * @return false when the rooms actually seen fit no rotation at all. They cannot all have come from
	 *     one raid, so the scan is holding rooms from a raid we are no longer in — the caller's cue to
	 *     throw the scout away rather than render a raid that never existed.
	 */
	private static boolean solveRotation(RaidRoom[] combat)
	{
		if (combat == null)
		{
			return true;
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
			return true; // too little to work from, or nothing left to fill in
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
						return true; // ambiguous: consistent with the raid, just not yet decisive
					}
					index = i - start;
					match = rotation;
				}
			}
		}
		if (match == null)
		{
			return false; // two rooms that no single raid puts where we saw them
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
		return true;
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
