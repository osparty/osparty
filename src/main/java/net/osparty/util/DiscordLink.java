package net.osparty.util;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.LinkBrowser;

/**
 * Opens Discord invite/channel links in the Discord desktop app via its {@code discord://} protocol
 * handler, falling back to the browser when the app isn't installed or the launch fails.
 */
@Slf4j
public final class DiscordLink
{
	private static final Pattern INVITE = Pattern.compile(
		"^https?://(?:www\\.)?(?:discord\\.gg|discord(?:app)?\\.com/invite)/([A-Za-z0-9-]+)");
	private static final Pattern CHANNEL = Pattern.compile(
		"^https?://(?:www\\.)?discord(?:app)?\\.com/channels/(\\d+)/(\\d+)");

	private DiscordLink()
	{
	}

	/** The {@code discord://} deep link for an invite or channel URL, or null when it isn't one. */
	public static String deepLink(String url)
	{
		if (url == null)
		{
			return null;
		}
		Matcher invite = INVITE.matcher(url.trim());
		if (invite.find())
		{
			return "discord://-/invite/" + invite.group(1);
		}
		Matcher channel = CHANNEL.matcher(url.trim());
		if (channel.find())
		{
			return "discord://-/channels/" + channel.group(1) + "/" + channel.group(2);
		}
		return null;
	}

	/** Open in the Discord app when possible, else the browser. Returns immediately; never blocks the EDT. */
	public static void open(String url)
	{
		String deepLink = deepLink(url);
		if (deepLink == null)
		{
			LinkBrowser.browse(url);
			return;
		}
		new Thread(() ->
		{
			if (launchApp(deepLink))
			{
				log.debug("Opened {} in the Discord app", deepLink);
				return;
			}
			log.debug("Discord app launch failed for {}, falling back to the browser", deepLink);
			LinkBrowser.browse(url);
		}, "osparty-discord-link").start();
	}

	private static boolean launchApp(String deepLink)
	{
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("win"))
		{
			// Without a registered handler "start" pops an Explorer error box instead of failing cleanly.
			if (!run(new String[]{"reg", "query", "HKCR\\discord", "/v", "URL Protocol"}))
			{
				return false;
			}
			return run(new String[]{"cmd", "/c", "start", "", deepLink});
		}
		if (os.contains("mac"))
		{
			return run(new String[]{"open", deepLink});
		}
		return run(new String[]{"xdg-open", deepLink});
	}

	private static boolean run(String[] command)
	{
		try
		{
			Process process = Runtime.getRuntime().exec(command);
			if (!process.waitFor(5, TimeUnit.SECONDS))
			{
				process.destroy();
				return false;
			}
			return process.exitValue() == 0;
		}
		catch (IOException e)
		{
			return false;
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return false;
		}
	}
}
