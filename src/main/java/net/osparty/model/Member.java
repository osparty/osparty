package net.osparty.model;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One party member: the display {@code name} and {@code playerId} — the account's public,
 * non-reversible id, safe to persist or show and stable across a rename. {@code playerId} is
 * {@code null} when the source didn't send one: a server or client old enough to predate it.
 *
 * <p>No account hash, deliberately. It used to ride here, and it is the thing a client asserts to
 * claim an identity — so every ad and roster handed everyone the one input needed to impersonate
 * anyone in it. Block and favourite matching, history and voice kicks all key on the id now; the
 * server keeps the hash to itself and derives the id for us.
 *
 * <p>{@code badges} are server-asserted Discord-role badges (e.g. {@code "developer"}) the
 * API stamps onto broadcast ads for linked members; {@code null} when the member has none.
 * Unknown badge strings from newer servers are kept as-is here and simply not rendered.
 *
 * <p>The {@link MemberAdapter} lets us tolerate the legacy wire form where a member was a
 * bare JSON string ({@code "Alice"}) rather than an object — it deserialises that to an
 * id-less member — and skips the {@code accountHash} an older server still sends. The
 * annotation binds the adapter to the type, so it works even through RuneLite's shared Gson
 * instance.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonAdapter(Member.MemberAdapter.class)
public class Member
{
	private String name;
	private List<String> badges;
	private String playerId;

	public Member(String name)
	{
		this(name, null, null);
	}

	public Member(String name, String playerId)
	{
		this(name, null, playerId);
	}

	public static final class MemberAdapter extends TypeAdapter<Member>
	{
		@Override
		public void write(JsonWriter out, Member value) throws IOException
		{
			if (value == null)
			{
				out.nullValue();
				return;
			}
			out.beginObject();
			out.name("name").value(value.name);
			if (value.playerId != null && !value.playerId.isEmpty())
			{
				out.name("playerId").value(value.playerId);
			}
			if (value.badges != null && !value.badges.isEmpty())
			{
				out.name("badges");
				out.beginArray();
				for (String badge : value.badges)
				{
					out.value(badge);
				}
				out.endArray();
			}
			out.endObject();
		}

		@Override
		public Member read(JsonReader in) throws IOException
		{
			JsonToken token = in.peek();
			if (token == JsonToken.NULL)
			{
				in.nextNull();
				return null;
			}
			// Legacy shape: a bare name string.
			if (token == JsonToken.STRING)
			{
				return new Member(in.nextString());
			}
			String name = null;
			List<String> badges = null;
			String playerId = null;
			in.beginObject();
			while (in.hasNext())
			{
				switch (in.nextName())
				{
					case "name":
						name = in.nextString();
						break;
					case "badges":
						if (in.peek() == JsonToken.NULL)
						{
							in.nextNull();
							break;
						}
						badges = new ArrayList<>();
						in.beginArray();
						while (in.hasNext())
						{
							badges.add(in.nextString());
						}
						in.endArray();
						break;
					case "playerId":
						if (in.peek() == JsonToken.NULL)
						{
							in.nextNull();
							break;
						}
						playerId = in.nextString();
						break;
					default:
						// Includes accountHash from a server that still sends it: read past, never kept.
						in.skipValue();
				}
			}
			in.endObject();
			return new Member(name, badges, playerId);
		}
	}
}
