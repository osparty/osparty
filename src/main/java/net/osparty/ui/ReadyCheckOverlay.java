package net.osparty.ui;

import net.osparty.party.LiveParty;
import net.osparty.party.LiveParty.ReadyCheckStatus;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * A small on-screen notification shown to every party member while a ready check
 * is running: who started it, how many have readied, and the countdown.
 */
public class ReadyCheckOverlay extends OverlayPanel
{
	private final LiveParty liveParty;

	public ReadyCheckOverlay(LiveParty liveParty)
	{
		this.liveParty = liveParty;
		setPosition(OverlayPosition.TOP_CENTER);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		ReadyCheckStatus status = liveParty.readyCheck();
		if (status == null)
		{
			return null;
		}

		boolean everyone = status.getReady() >= status.getTotal();
		panelComponent.getChildren().clear();
		panelComponent.setPreferredSize(new Dimension(180, 0));

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Ready check")
			.color(Color.ORANGE)
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Started by")
			.right(status.getStarter() == null ? "?" : status.getStarter())
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Ready")
			.right(status.getReady() + "/" + status.getTotal())
			.rightColor(everyone ? Color.GREEN : Color.YELLOW)
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left(status.isLocalReady() ? "You are ready" : "Ready up in the panel")
			.right(status.getSecondsLeft() + "s")
			.leftColor(status.isLocalReady() ? Color.GREEN : Color.WHITE)
			.build());

		return super.render(graphics);
	}
}
