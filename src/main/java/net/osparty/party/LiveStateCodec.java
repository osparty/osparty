package net.osparty.party;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.osparty.model.Applicant.EquipmentSlot;

/**
 * The live-update wire format: short field names, and the item fields carried slot by slot rather than whole.
 *
 * <p>An items frame goes out because one thing changed, a shark eaten or a brew sipped, and carrying all 28
 * inventory slots, all 28 quantities and every worn slot to say so costs some 570 bytes. Those three fields
 * travel as an object of the slots that moved, which peers accumulate (see {@link #merge}); the rune pouch
 * keeps its array, being three entries at most and rarely touched.
 */
final class LiveStateCodec {
	/**
	 * Long field name to short wire name, for everything a live frame can carry. The short names are what a
	 * peer reads off the wire, so they are what cannot move; the field names on {@link PlayerUpdate} can.
	 */
	static final Map<String, String> TO_WIRE = Map.ofEntries(
		Map.entry("name", "n"), Map.entry("combatLevel", "cl"),
		Map.entry("equipment", "eq"), Map.entry("inventory", "iv"), Map.entry("inventoryQuantities", "iq"),
		Map.entry("runePouch", "rp"), Map.entry("runePouchAmounts", "ra"), Map.entry("runePouchNames", "rn"),
		Map.entry("stats", "sk"), Map.entry("currentHp", "hp"), Map.entry("maxHp", "mh"),
		Map.entry("currentPrayer", "pr"), Map.entry("maxPrayer", "mp"), Map.entry("specialPercent", "sp"),
		Map.entry("runEnergy", "re"), Map.entry("vengeance", "vg"), Map.entry("spellbook", "sb"),
		Map.entry("accountType", "at"), Map.entry("role", "ro"), Map.entry("learner", "ln"),
		Map.entry("teacher", "te"), Map.entry("invited", "in"), Map.entry("pbSeconds", "pb"),
		Map.entry("world", "wd"), Map.entry("friendsChatOwner", "fc"), Map.entry("hideInventory", "hi"),
		Map.entry("hideGear", "hg"));

	/** The same the other way, for reading a peer's frame back into a {@link PlayerUpdate}. */
	static final Map<String, String> FROM_WIRE = TO_WIRE.entrySet().stream()
		.collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

	/** How many slots each sparse item field expands back to. */
	static final Map<String, Integer> SLOT_LENGTHS =
		Map.of("iv", 28, "iq", 28, "eq", EquipmentSlot.COUNT);

	static final Set<String> SLOT_FIELDS = Set.of("iv", "iq", "eq");

	/**
	 * What a slot holds when the frame does not mention it: an empty inventory or equipment slot, and a stack
	 * of one, which is what an ordinary non-stackable item has and so the most common value on the wire.
	 */
	static final Map<String, Integer> SLOT_ABSENT = Map.of("iv", -1, "iq", 1, "eq", -1);

	/** Fields carried by an {@code items} frame: everything gated by the two privacy toggles. */
	static final String[] ITEM_FIELDS = {
		"eq", "iv", "iq", "rp", "ra", "rn", "hi", "hg",
	};

	/**
	 * Fields carried by a {@code profile} frame: identity, caps and self-reported flags. All of it changes
	 * on login, a world hop, a level-up or a deliberate act, never on a tick.
	 */
	static final String[] PROFILE_FIELDS = {
		"n", "ah", "cl", "mh", "mp", "sb", "sk", "at", "wd", "fc", "pb", "ro", "ln", "te", "in",
		// Unshortened, deliberately: peers running an older build read it by this name.
		"memberId",
	};

	/**
	 * Everything we have to compare against to send only what moved. Cleared whenever the next frame has to
	 * carry the whole picture: a reconnect, or a resync for somebody who has just been seated and holds
	 * nothing of ours.
	 */
	private final Map<String, int[]> sentSlots = new ConcurrentHashMap<>();

	/** Forget the slot baseline, so the next items frame is written whole. */
	void resetSlots() {
		sentSlots.clear();
	}

	/**
	 * Rewrite the whole-array item fields as the slots that actually moved.
	 *
	 * <p>A slot that changed <em>to</em> its absent value is still named: absent means the default only until
	 * a value has been seen, after which the peer holds the old one and has to be told it is gone. Without a
	 * baseline, the first send of a party and the first after a resync, everything is named except the slots
	 * already at their default, which is the same picture written the short way.
	 */
	void sparsify(JsonObject items) {
		for (String field : SLOT_FIELDS) {
			JsonElement value = items.get(field);
			if (value == null || !value.isJsonArray()) {
				// Withheld by a privacy toggle, or already sparse. Either way there is nothing to compare.
				sentSlots.remove(field);
				continue;
			}
			JsonArray array = value.getAsJsonArray();
			int[] current = new int[array.size()];
			for (int i = 0; i < current.length; i++) {
				current[i] = array.get(i).getAsInt();
			}
			int[] previous = sentSlots.put(field, current);
			int absent = SLOT_ABSENT.get(field);
			JsonObject changed = new JsonObject();
			for (int i = 0; i < current.length; i++) {
				boolean send = previous == null || previous.length != current.length
					? current[i] != absent
					: current[i] != previous[i];
				if (send) {
					changed.addProperty(Integer.toString(i), current[i]);
				}
			}
			items.add(field, changed);
		}
	}

	/** {@code patch} over {@code base}, without mutating either; null base means the patch is the whole. */
	static JsonObject merge(JsonObject base, JsonObject patch) {
		if (base == null) {
			return patch.deepCopy();
		}
		JsonObject merged = base.deepCopy();
		for (Map.Entry<String, JsonElement> field : patch.entrySet()) {
			JsonElement held = merged.get(field.getKey());
			// The slot maps are the one place a patch describes part of a field rather than all of it: an
			// items frame carries the inventory slots that moved, not the inventory. Everything else is
			// whole, so replacing is right for it and merging would leave stale keys behind forever.
			if (SLOT_FIELDS.contains(field.getKey())
				&& held != null && held.isJsonObject() && field.getValue().isJsonObject()) {
				JsonObject slots = held.getAsJsonObject().deepCopy();
				for (Map.Entry<String, JsonElement> slot : field.getValue().getAsJsonObject().entrySet()) {
					slots.add(slot.getKey(), slot.getValue());
				}
				merged.add(field.getKey(), slots);
				continue;
			}
			merged.add(field.getKey(), field.getValue());
		}
		return merged;
	}

	/** Rename a whole snapshot's fields to their wire names. Fields with no short name are left alone. */
	static JsonObject toWire(JsonObject src) {
		JsonObject out = new JsonObject();
		for (Map.Entry<String, JsonElement> field : src.entrySet()) {
			out.add(TO_WIRE.getOrDefault(field.getKey(), field.getKey()), field.getValue());
		}
		return out;
	}

	/**
	 * Turn an accumulated wire snapshot back into something {@link PlayerUpdate} can be read from: long
	 * names again, and the sparse item fields expanded to the fixed-length arrays a caller indexes by slot.
	 */
	static JsonObject fromWire(JsonObject src) {
		JsonObject out = new JsonObject();
		for (Map.Entry<String, JsonElement> field : src.entrySet()) {
			String key = field.getKey();
			JsonElement value = field.getValue();
			if (SLOT_FIELDS.contains(key) && value.isJsonObject()) {
				value = expandSlots(key, value.getAsJsonObject());
			}
			out.add(FROM_WIRE.getOrDefault(key, key), value);
		}
		return out;
	}

	/** A slot map back to its array; slots nobody mentioned take their {@link #SLOT_ABSENT} value. */
	private static JsonArray expandSlots(String field, JsonObject slots) {
		int length = SLOT_LENGTHS.get(field);
		int absent = SLOT_ABSENT.get(field);
		int[] values = new int[length];
		Arrays.fill(values, absent);
		for (Map.Entry<String, JsonElement> slot : slots.entrySet()) {
			try {
				int index = Integer.parseInt(slot.getKey());
				if (index >= 0 && index < length) {
					values[index] = slot.getValue().getAsInt();
				}
			}
			catch (RuntimeException ignored) {
				// A key that is not a slot number, or a value that is not one: skip it rather than lose
				// the rest of the inventory to one bad entry.
			}
		}
		JsonArray out = new JsonArray(length);
		for (int value : values) {
			out.add(value);
		}
		return out;
	}

	/** Copy every field of {@code src} onto {@code target}, overwriting. */
	static void addAll(JsonObject target, JsonObject src) {
		for (Map.Entry<String, JsonElement> field : src.entrySet()) {
			target.add(field.getKey(), field.getValue());
		}
	}

	/** The named fields of {@code src}, skipping any it does not carry. */
	static JsonObject project(JsonObject src, String[] fields) {
		JsonObject out = new JsonObject();
		if (src == null) {
			return out;
		}
		for (String field : fields) {
			JsonElement value = src.get(field);
			if (value != null) {
				out.add(field, value);
			}
		}
		return out;
	}
}
