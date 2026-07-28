package net.osparty.party;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;

/**
 * Reads a fixed-length slot array written either as a plain array or as a sparse object keyed by slot index.
 *
 * <p>An items frame used to carry all 28 inventory slots, all 28 quantities and all 14 equipment slots every
 * time any one of them moved — around 570 bytes to say that a shark was eaten. The sparse form carries only
 * the slots that changed ({@code {"3":1234,"7":-1}}), and peers accumulate it because
 * {@code LivePartyV2.merge} merges these three fields key by key rather than replacing them.
 *
 * <p>The array form is still written, and still read, because that is what a full send is: the first frame
 * of a party, and every frame after a {@code resync}, carry the whole picture. Only the frames in between
 * are sparse.
 *
 * <p>The result is always {@link #length} entries regardless of how few arrived, so a caller can index by
 * slot without checking. Absent slots take {@link #absent} — the id of an empty slot, or a quantity of one,
 * which is what a slot holding an ordinary non-stackable item has and is therefore never worth sending.
 */
abstract class SlotMap extends TypeAdapter<int[]>
{
	private final int length;
	private final int absent;

	SlotMap(int length, int absent)
	{
		this.length = length;
		this.absent = absent;
	}

	/** 28 inventory slots; an absent one is empty. */
	static final class Inventory extends SlotMap
	{
		Inventory()
		{
			super(28, -1);
		}
	}

	/** 28 stack sizes; an absent one is a single item, which is the overwhelming majority of them. */
	static final class Quantities extends SlotMap
	{
		Quantities()
		{
			super(28, 1);
		}
	}

	/** 14 worn slots, in {@link net.osparty.model.Applicant.EquipmentSlot} order; absent is empty. */
	static final class Equipment extends SlotMap
	{
		Equipment()
		{
			super(14, -1);
		}
	}

	@Override
	public void write(JsonWriter out, int[] value) throws IOException
	{
		if (value == null)
		{
			out.nullValue();
			return;
		}
		// Whole-picture sends stay arrays; LivePartyV2 rewrites them to the sparse form when it has
		// something to compare against, which it does not the first time.
		out.beginArray();
		for (int slot : value)
		{
			out.value(slot);
		}
		out.endArray();
	}

	@Override
	public int[] read(JsonReader in) throws IOException
	{
		if (in.peek() == JsonToken.NULL)
		{
			in.nextNull();
			return null;
		}
		if (in.peek() == JsonToken.BEGIN_ARRAY)
		{
			// Exactly what was sent: the array form is the whole picture by definition, so it needs no
			// padding and inventing slots the sender did not claim would be worse than leaving it short.
			java.util.List<Integer> read = new java.util.ArrayList<>();
			in.beginArray();
			while (in.hasNext())
			{
				read.add(in.nextInt());
			}
			in.endArray();
			int[] array = new int[read.size()];
			for (int i = 0; i < array.length; i++)
			{
				array[i] = read.get(i);
			}
			return array;
		}
		// The sparse form names only some slots, so it is padded to the full length -- a caller indexes by
		// slot, and a short array would turn a quiet omission into an out-of-bounds.
		int[] out = new int[length];
		java.util.Arrays.fill(out, absent);
		in.beginObject();
		while (in.hasNext())
		{
			int slot;
			try
			{
				slot = Integer.parseInt(in.nextName());
			}
			catch (NumberFormatException e)
			{
				in.skipValue();
				continue;
			}
			int value = in.nextInt();
			if (slot >= 0 && slot < length)
			{
				out[slot] = value;
			}
		}
		in.endObject();
		return out;
	}
}
