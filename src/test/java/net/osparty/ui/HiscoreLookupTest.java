package net.osparty.ui;

import java.lang.reflect.Method;
import javax.swing.JMenuItem;
import net.runelite.client.hiscore.HiscoreEndpoint;
import net.runelite.client.plugins.hiscore.HiscorePlugin;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class HiscoreLookupTest
{
	/** The lookup is reflective, so a rename upstream would only surface at runtime without this. */
	@Test
	public void hiscorePluginStillExposesLookup() throws Exception
	{
		Method lookup = HiscorePlugin.class.getDeclaredMethod("lookupPlayer", String.class, HiscoreEndpoint.class);
		assertNotNull(lookup);
		Method endpoint = HiscorePlugin.class.getDeclaredMethod("getWorldEndpoint");
		assertEquals(HiscoreEndpoint.class, endpoint.getReturnType());
	}

	@Test
	public void noItemWithoutAName()
	{
		assertNull(HiscoreLookup.menuItem(null));
		assertNull(HiscoreLookup.menuItem("  "));
	}

	@Test
	public void itemIsLabelledForTheNamedPlayer()
	{
		JMenuItem item = HiscoreLookup.menuItem("Zezima ", "Look up host on hiscores");
		assertNotNull(item);
		assertEquals("Look up host on hiscores", item.getText());
		assertNull(item.getToolTipText());
	}
}
