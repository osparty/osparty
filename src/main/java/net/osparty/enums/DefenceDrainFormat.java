package net.osparty.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** What the defence tracker writes after its down arrow: the amount drained so far, the percent drained, or nothing. */
@Getter
@RequiredArgsConstructor
public enum DefenceDrainFormat
{
	AMOUNT("Amount"),
	PERCENT("Percent"),
	OFF("Off");

	private final String label;

	@Override
	public String toString()
	{
		return label;
	}
}
