package net.osparty.party;

import lombok.Value;

/** The ready check in progress, as the panel and overlay render it. Null when none is running. */
@Value
public class ReadyCheckStatus
{
	String starter;
	int ready;
	int total;
	int secondsLeft;
	boolean localReady;
}
