package net.osparty.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * A brief, self-dismissing in-game popup shown to a member when the party host
 * asks them to join the host's friends chat.
 */
public class FcRequestOverlay extends OverlayPanel
{
	private volatile String hostName;
	private volatile String friendsChat;
	private volatile long expiresAt;

	public FcRequestOverlay()
	{
		setPosition(OverlayPosition.TOP_CENTER);
	}

	public void show(String hostName, String friendsChat, long durationMs)
	{
		this.hostName = hostName;
		this.friendsChat = friendsChat;
		this.expiresAt = System.currentTimeMillis() + durationMs;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		String fc = friendsChat;
		if (fc == null || System.currentTimeMillis() > expiresAt)
		{
			return null;
		}

		panelComponent.getChildren().clear();
		panelComponent.setPreferredSize(new Dimension(230, 0));

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Friends chat request")
			.color(Color.ORANGE)
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left((hostName != null ? hostName : "The host") + " asks you to join:")
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left(fc)
			.leftColor(Color.YELLOW)
			.build());

		return super.render(graphics);
	}
}
