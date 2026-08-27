package net.osparty.tools;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.osparty.OSPartyConfig;
import net.osparty.enums.EventSound;
import net.runelite.api.Client;
import net.runelite.api.SoundEffectID;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.callback.ClientThread;

/**
 * Plays the plugin's event sounds, each behind its config switch, at the volume the config sets.
 *
 * <p>Bundled sounds go through RuneLite's {@link AudioPlayer}, which takes a gain in decibels; the volume is
 * turned into one on the usual amplitude scale (half volume is about -6 dB), floored where the mixer's
 * range ends. The ping is the game's own effect and cannot be scaled: the client API's volume argument
 * only lets a sound through when the game is muted, so it would make a muted game start making noise.
 * It plays at the game's sound-effect volume.
 */
@Slf4j
@Singleton
public class PartySounds
{
	/** Below this the mixer refuses the gain; it is also inaudible, so 1% lands here rather than failing. */
	static final float MIN_GAIN_DB = -80f;

	private final Client client;
	private final ClientThread clientThread;
	private final OSPartyConfig config;
	private final AudioPlayer audioPlayer;

	@Inject
	PartySounds(Client client, ClientThread clientThread, OSPartyConfig config, AudioPlayer audioPlayer)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.audioPlayer = audioPlayer;
	}

	/** Play the sound for an event, if its switch is on and the volume is above zero. Safe from any thread. */
	public void play(EventSound sound)
	{
		if (!enabled(sound))
		{
			return;
		}
		if (sound.isInGame())
		{
			clientThread.invoke(() -> client.playSoundEffect(SoundEffectID.SMITH_ANVIL_TINK));
			return;
		}
		int volume = config.soundVolume();
		if (volume <= 0)
		{
			return;
		}
		try
		{
			audioPlayer.play(getClass(), sound.getResource(), gainDb(volume));
		}
		catch (Exception e)
		{
			log.warn("OSParty: failed to play sound '{}'", sound.getResource(), e);
		}
	}

	private boolean enabled(EventSound sound)
	{
		switch (sound)
		{
			case READY_CHECK_STARTED:
			case ALL_READY:
				return config.readyCheckSound();
			case FRIENDS_CHAT_REQUEST:
				return config.friendsChatRequestSound();
			case KICKED:
				return config.kickSound();
			case PING:
				return config.pingSound();
			default:
				return false;
		}
	}

	/**
	 * A percentage as a mixer gain: 100% is unity, and each halving takes about 6 dB off, which is how
	 * loudness controls are expected to feel. Anything at or below the floor is the floor.
	 */
	static float gainDb(int percent)
	{
		if (percent >= 100)
		{
			return 0f;
		}
		if (percent <= 0)
		{
			return MIN_GAIN_DB;
		}
		return (float) Math.max(MIN_GAIN_DB, 20 * Math.log10(percent / 100.0));
	}
}
