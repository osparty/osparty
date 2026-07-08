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
 * One party member as carried in the API party ad: the display {@code name} plus the
 * stable {@code accountHash} used to recognise the player across name changes (block /
 * favourite matching). {@code accountHash} is {@code 0} when the reporting client is an
 * older version that didn't send one.
 *
 * <p>{@code badges} are server-asserted Discord-role badges (e.g. {@code "developer"}) the
 * API stamps onto broadcast ads for linked members; {@code null} when the member has none.
 * Unknown badge strings from newer servers are kept as-is here and simply not rendered.
 *
 * <p>The {@link MemberAdapter} lets us tolerate the legacy wire form where a member was a
 * bare JSON string ({@code "Alice"}) rather than an object — it deserialises that to a
 * hash-less member. The annotation binds the adapter to the type, so it works even through
 * RuneLite's shared Gson instance.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonAdapter(Member.MemberAdapter.class)
public class Member
{
	private String name;
	private long accountHash;
	private List<String> badges;

	public Member(String name, long accountHash)
	{
		this(name, accountHash, null);
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
			out.name("accountHash").value(value.accountHash);
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
			// Legacy shape: a bare name string with no hash.
			if (token == JsonToken.STRING)
			{
				return new Member(in.nextString(), 0L);
			}
			String name = null;
			long accountHash = 0L;
			List<String> badges = null;
			in.beginObject();
			while (in.hasNext())
			{
				switch (in.nextName())
				{
					case "name":
						name = in.nextString();
						break;
					case "accountHash":
						accountHash = in.nextLong();
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
					default:
						in.skipValue();
				}
			}
			in.endObject();
			return new Member(name, accountHash, badges);
		}
	}
}
