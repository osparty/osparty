package net.osparty.ui;

import net.osparty.model.Activity;
import net.osparty.model.Applicant;
import net.osparty.model.Role;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * In-game overlay listing every applicant pending for the party the host is
 * running — name, combat level and activity killcount each.
 */
public class ApplicantOverlay extends OverlayPanel
{
	private volatile List<Applicant> applicants = new ArrayList<>();
	private volatile Activity activity;

	public ApplicantOverlay()
	{
		setPosition(OverlayPosition.TOP_LEFT);
	}

	public void setApplicants(List<Applicant> applicants, Activity activity)
	{
		this.applicants = applicants == null ? new ArrayList<>() : new ArrayList<>(applicants);
		this.activity = activity;
	}

	public void clear()
	{
		this.applicants = new ArrayList<>();
		this.activity = null;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		List<Applicant> list = applicants;
		Activity act = activity;
		if (list.isEmpty() || act == null)
		{
			return null;
		}

		panelComponent.getChildren().clear();
		panelComponent.setPreferredSize(new Dimension(160, 0));

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Applicants (" + list.size() + ")")
			.color(Color.ORANGE)
			.build());

		for (Applicant a : list)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(a.getName())
				.right("cb " + a.getCombatLevel())
				.build());

			if (a.getRole() != null)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left("Role")
					.right(Role.displayNameOf(a.getRole()))
					.rightColor(Color.ORANGE)
					.build());
			}

			if (a.isLearner())
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left("Learner")
					.right("yes")
					.rightColor(Color.ORANGE)
					.build());
			}

			if (a.getKillCount() >= 0)
			{
				String kc = act.getDisplayName() + " KC";
				String value = String.valueOf(a.getKillCount());
				if (act.hasHardMode() && a.getHardModeKillCount() >= 0)
				{
					value += " (" + act.getHardModeLabel() + " " + a.getHardModeKillCount() + ")";
				}
				panelComponent.getChildren().add(LineComponent.builder()
					.left(kc)
					.right(value)
					.rightColor(Color.GREEN)
					.build());
			}
		}

		return super.render(graphics);
	}
}
