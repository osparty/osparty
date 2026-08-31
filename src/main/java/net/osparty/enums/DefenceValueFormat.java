package net.osparty.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** How the defence tracker writes out a level: {@code 142}, {@code 142/200}, {@code 71%} or {@code 142 (71%)}. */
@Getter
@RequiredArgsConstructor
public enum DefenceValueFormat
{
	CURRENT("Current"),
	CURRENT_OF_BASE("Current / base"),
	PERCENT("Percent"),
	CURRENT_AND_PERCENT("Current (percent)");

	private final String label;

	@Override
	public String toString()
	{
		return label;
	}
}
