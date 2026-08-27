package net.osparty.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Which single number the status-bar defence info box shows; the full breakdown is in its tooltip. */
@Getter
@RequiredArgsConstructor
public enum DefenceInfoBoxValue
{
	CURRENT("Current defence"),
	PERCENT("Percent remaining"),
	DRAINED("Amount drained");

	private final String label;

	@Override
	public String toString()
	{
		return label;
	}
}
