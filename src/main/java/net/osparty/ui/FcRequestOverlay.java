package net.osparty.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * A brief, self-dismissing in-game popup telling a member how to join the raid:
 * join the host's friends chat (CoX), apply on the notice board (ToB), or apply on
 * the Grouping Obelisk (ToA).
 */
public class FcRequestOverlay extends OverlayPanel
{
	private volatile String hostName;
	private volatile String title;
	private volatile String detail;
	private volatile long expiresAt;

	public FcRequestOverlay()
	{
		// Distinct anchor from the ready-check overlay so the two never stack; user can reposition.
		setPosition(OverlayPosition.TOP_RIGHT);
		setMovable(true);
		setSnappable(true);
	}

	public void show(String hostName, String title, String detail, long durationMs)
	{
		this.hostName = hostName;
		this.title = title;
		this.detail = detail;
		this.expiresAt = System.currentTimeMillis() + durationMs;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		String d = detail;
		if (d == null || System.currentTimeMillis() > expiresAt)
		{
			return null;
		}

		panelComponent.getChildren().clear();
		panelComponent.setPreferredSize(new Dimension(230, 0));

		panelComponent.getChildren().add(TitleComponent.builder()
			.text(title != null ? title : "Party")
			.color(Color.ORANGE)
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left((hostName != null ? hostName : "The host") + ":")
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left(d)
			.leftColor(Color.YELLOW)
			.build());

		// Visible countdown so a glance tells you how long it stays.
		int secs = (int) Math.ceil(Math.max(0, expiresAt - System.currentTimeMillis()) / 1000.0);
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Closing in")
			.right(secs + "s")
			.rightColor(Color.ORANGE)
			.build());

		return super.render(graphics);
	}
}
