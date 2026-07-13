package net.osparty.model;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * One entry in the RuneWatch / We Do Raids watchlist — a player reported for
 * scamming. Field names match the published {@code mixedlist.json} feed.
 */
@Data
public class RuneWatchCase
{
	@SerializedName("accused_rsn")
	private String rsn;

	@SerializedName("published_date")
	private String date;

	@SerializedName("short_code")
	private String code;

	private String reason;

	@SerializedName("evidence_rating")
	private String rating;

	/** "RW" (RuneWatch) or "WDR" (We Do Raids). */
	private String source;

	public String sourceName()
	{
		return source != null && source.equalsIgnoreCase("wdr") ? "We Do Raids" : "RuneWatch";
	}
}
