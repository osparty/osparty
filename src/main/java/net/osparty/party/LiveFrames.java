package net.osparty.party;

import com.google.gson.annotations.SerializedName;

/** The shapes this plugin puts on the live channel. Gson omits nulls, so a frame carries only what is set. */
final class LiveFrames {
	private LiveFrames() {
	}

	static final class HelloFrame {
		@SerializedName("t")
		final String type = "hello";
		final long accountHash;
		final String name;

		HelloFrame(long accountHash, String name) {
			this.accountHash = accountHash;
			this.name = name;
		}
	}

	static final class HostFrame {
		@SerializedName("t")
		final String type = "host";
		final String room;
		final String hostName;
		final String activityId;
		final Integer capacity;
		final Boolean locked;
		final String role;
		final Boolean learner;
		final Boolean teacher;
		final long accountHash;

		HostFrame(String room, String hostName, String activityId, int capacity, boolean locked, String role,
			boolean learner, boolean teacher, long accountHash) {
			this.room = room;
			this.hostName = hostName;
			this.activityId = activityId;
			this.capacity = capacity;
			this.locked = locked;
			this.role = role;
			this.learner = learner;
			this.teacher = teacher;
			this.accountHash = accountHash;
		}
	}

	static final class JoinFrame {
		@SerializedName("t")
		final String type = "join";
		final String room;
		final String activityId;
		final String role;
		final Boolean learner;
		final Boolean teacher;
		final Boolean invited;
		final String name;
		final long accountHash;

		JoinFrame(String room, String activityId, String role, boolean learner, boolean teacher,
			boolean invited, String name, long accountHash) {
			this.room = room;
			this.activityId = activityId;
			this.role = role;
			this.learner = learner;
			this.teacher = teacher;
			this.invited = invited;
			this.name = name;
			this.accountHash = accountHash;
		}
	}

	/** Proof of life for an idle party; carries nothing else. The server relays it to peers as {@code alive}. */
	static final class HeartbeatFrame {
		@SerializedName("t")
		final String type = "heartbeat";
	}

	/**
	 * One live update, carrying whichever parts changed this tick.
	 *
	 * <p>The parts are chosen by how often they move (vitals every tick or two, items on a swap, profile
	 * almost never) but they travel together, because the cost of a frame is dominated by serialising it and
	 * writing it once per peer, not by its size. Splitting a tick that changed two things into two frames
	 * measurably raised CPU for no benefit; the saving was always in what the payload leaves out.
	 *
	 * <p>Its own {@code type}, not {@code state}: a client that does not know about the split dispatches on
	 * type and ignores what it does not recognise, so it sees a peer go stale rather than parsing a partial
	 * update as a whole one and blanking their gear.
	 */
	static final class UpdateFrame {
		@SerializedName("t")
		final String type = "update";
		@SerializedName("s")
		final Object state;
		/**
		 * Ask the owner node to relay this one without waiting out its idle window. Null when it can wait, so
		 * the field is simply absent from the ordinary frame.
		 *
		 * <p>A field of the frame rather than of {@link #state}: the server must stay blind to what a member
		 * is reporting, and this tells it only how soon the report is wanted.
		 */
		@SerializedName("g")
		final Boolean urgent;

		UpdateFrame(Object state, boolean urgent) {
			this.state = state;
			this.urgent = urgent ? Boolean.TRUE : null;
		}
	}

	static final class PingFrame {
		@SerializedName("t")
		final String type = "ping";
		final int x;
		final int y;
		final int plane;
		final int color;
		final String name;

		PingFrame(int x, int y, int plane, int color, String name) {
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.color = color;
			this.name = name;
		}
	}

	static final class CommandFrame {
		@SerializedName("t")
		final String type = "command";
		final String action;
		final long target;
		final String name;

		CommandFrame(String action, long target, String name) {
			this.action = action;
			this.target = target;
			this.name = name;
		}
	}

	static final class CapacityFrame {
		@SerializedName("t")
		final String type = "setCapacity";
		final int capacity;

		CapacityFrame(int capacity) {
			this.capacity = capacity;
		}
	}

	static final class LockedFrame {
		@SerializedName("t")
		final String type = "setLocked";
		final boolean locked;

		LockedFrame(boolean locked) {
			this.locked = locked;
		}
	}

	static final class MetaFrame {
		@SerializedName("t")
		final String type = "setMeta";
		final Object meta;

		MetaFrame(Object meta) {
			this.meta = meta;
		}
	}

	static final class DiscordFrame {
		@SerializedName("t")
		final String type = "setDiscord";
		final String url;

		DiscordFrame(String url) {
			this.url = url;
		}
	}

	static final class LeaveFrame {
		@SerializedName("t")
		final String type = "leave";
	}

	static final class ReadyStartFrame {
		@SerializedName("t")
		final String type = "readyStart";
		final long checkId;
		final String starter;

		ReadyStartFrame(long checkId, String starter) {
			this.checkId = checkId;
			this.starter = starter;
		}
	}

	static final class ReadyFrame {
		@SerializedName("t")
		final String type = "ready";
		final long checkId;

		ReadyFrame(long checkId) {
			this.checkId = checkId;
		}
	}

	static final class SpecDrainFrame {
		@SerializedName("t")
		final String type = "specDrain";
		final int npcIndex;
		final String weapon;
		final int hit;
		final int world;

		SpecDrainFrame(int npcIndex, String weapon, int hit, int world) {
			this.npcIndex = npcIndex;
			this.weapon = weapon;
			this.hit = hit;
			this.world = world;
		}
	}

	/** Host to member: how to actually get into the raid. Named {@code fcRequest} on the wire. */
	static final class JoinPromptFrame {
		@SerializedName("t")
		final String type = "fcRequest";
		final long target;
		final String kind;
		final String friendsChat;

		JoinPromptFrame(long target, String kind, String friendsChat) {
			this.target = target;
			this.kind = kind;
			this.friendsChat = friendsChat;
		}
	}

	static final class TransferHostFrame {
		@SerializedName("t")
		final String type = "transferHost";
		final String kind;
		final long target;
		final String newHostKey;
		final String newHostName;
		final boolean hostStays;

		TransferHostFrame(String kind, long target, String newHostKey, String newHostName, boolean hostStays) {
			this.kind = kind;
			this.target = target;
			this.newHostKey = newHostKey;
			this.newHostName = newHostName;
			this.hostStays = hostStays;
		}
	}
}
