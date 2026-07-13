package net.osparty.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Where the scene defence display sits relative to the monster. {@code heightFactor}
 * and {@code heightOffset} produce a z-offset of {@code logicalHeight * factor +
 * offset} for the text; {@code xNudge} shifts it sideways (used to sit beside the
 * health bar without covering it).
 */
@Getter
@RequiredArgsConstructor
public enum DefenceOverlayPosition
{
	ABOVE_HP_BAR("Above HP bar", 1.0, 55, 0),
	CENTRE_OF_NPC("Centre of NPC", 0.5, 0, 0),
	AT_NPC_FEET("At NPC feet", 0.0, 0, 0);

	private final String label;
	private final double heightFactor;
	private final int heightOffset;
	private final int xNudge;

	@Override
	public String toString()
	{
		return label;
	}
}
