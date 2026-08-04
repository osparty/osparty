package net.osparty.ui;

import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import javax.swing.JMenuItem;
import net.runelite.client.hiscore.HiscoreEndpoint;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.util.LinkBrowser;

/**
 * "Look up on hiscores" for a player name, opening RuneLite's own Hiscore side panel. That plugin's
 * lookup entry point is package-private, so we reach it reflectively and fall back to the web
 * hiscores when the Hiscore plugin isn't running.
 */
@lombok.extern.slf4j.Slf4j
public final class HiscoreLookup
{
	private static final String PLUGIN_CLASS = "net.runelite.client.plugins.hiscore.HiscorePlugin";
	private static final String WEB_HISCORES =
		"https://secure.runescape.com/m=hiscore_oldschool/hiscorepersonal?user1=";

	private static PluginManager pluginManager;

	private HiscoreLookup()
	{
	}

	public static void init(PluginManager manager)
	{
		pluginManager = manager;
	}

	/** Menu item looking {@code rsn} up on the hiscores, or null when there's no name to look up. */
	static JMenuItem menuItem(String rsn)
	{
		return menuItem(rsn, "Look up on hiscores");
	}

	static JMenuItem menuItem(String rsn, String label)
	{
		final String name = AdText.normalizeName(rsn);
		if (name.isEmpty())
		{
			return null;
		}
		JMenuItem item = new JMenuItem(label);
		item.addActionListener(e -> lookup(name));
		return item;
	}

	private static void lookup(String name)
	{
		Plugin hiscore = hiscorePlugin();
		if (hiscore != null)
		{
			try
			{
				Method lookup = method(hiscore, "lookupPlayer", String.class, HiscoreEndpoint.class);
				lookup.invoke(hiscore, name, endpoint(hiscore));
				return;
			}
			catch (ReflectiveOperationException | RuntimeException ex)
			{
				log.debug("Hiscore panel lookup failed for {}", name, ex);
			}
		}
		LinkBrowser.browse(WEB_HISCORES + URLEncoder.encode(name, StandardCharsets.UTF_8));
	}

	/** The running Hiscore plugin, or null when it isn't installed or is switched off. */
	private static Plugin hiscorePlugin()
	{
		if (pluginManager == null)
		{
			return null;
		}
		for (Plugin plugin : pluginManager.getPlugins())
		{
			if (PLUGIN_CLASS.equals(plugin.getClass().getName()))
			{
				return pluginManager.isPluginEnabled(plugin) ? plugin : null;
			}
		}
		return null;
	}

	/** The endpoint the Hiscore plugin would use itself (leagues, deadman, ...); normal on failure. */
	private static HiscoreEndpoint endpoint(Plugin hiscore)
	{
		try
		{
			return (HiscoreEndpoint) method(hiscore, "getWorldEndpoint").invoke(hiscore);
		}
		catch (ReflectiveOperationException | RuntimeException ex)
		{
			return HiscoreEndpoint.NORMAL;
		}
	}

	private static Method method(Plugin plugin, String name, Class<?>... params) throws NoSuchMethodException
	{
		for (Class<?> type = plugin.getClass(); type != null; type = type.getSuperclass())
		{
			try
			{
				Method method = type.getDeclaredMethod(name, params);
				method.setAccessible(true);
				return method;
			}
			catch (NoSuchMethodException ignored)
			{
				// keep walking up; the plugin may be a subclass
			}
		}
		throw new NoSuchMethodException(name);
	}
}
