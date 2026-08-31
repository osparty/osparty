package net.osparty.enums;

import lombok.Getter;

/**
 * Every sound the plugin can play. A sound with no {@code resource} is one of the game's own effects
 * rather than a bundled file, and plays at the game's sound-effect volume instead of the plugin's.
 */
@Getter
public enum EventSound
{
	READY_CHECK_STARTED("/net/osparty/sounds/readycheck.wav"),
	ALL_READY("/net/osparty/sounds/ready.wav"),
	FRIENDS_CHAT_REQUEST("/net/osparty/sounds/friendschatsound.wav"),
	KICKED("/net/osparty/sounds/kicked.wav"),
	PING(null);

	private final String resource;

	EventSound(String resource)
	{
		this.resource = resource;
	}

	public boolean isInGame()
	{
		return resource == null;
	}
}
