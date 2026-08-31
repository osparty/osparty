package net.osparty.api;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * Maps low-level network/socket exceptions to short, user-facing messages so the
 * UI never surfaces a raw {@code getMessage()} string. Keep the technical text for
 * logs; show the friendly text to users.
 */
public final class PartyErrors
{
	private PartyErrors()
	{
	}

	public static String friendly(Throwable t)
	{
		if (t == null)
		{
			return "Something went wrong. Please try again.";
		}

		String msg = t.getMessage();
		if (msg != null)
		{
			String m = msg.toLowerCase();
			if (m.contains("not connected"))
			{
				return "You're offline and can't reach OSParty. Check your connection and retry.";
			}
			if (m.startsWith("host rejected:"))
			{
				String detail = msg.substring("host rejected:".length()).trim();
				return detail.isEmpty()
					? "The server rejected the request."
					: "The server rejected the request: " + detail;
			}
		}

		if (t instanceof UnknownHostException
			|| t instanceof SocketTimeoutException
			|| t instanceof EOFException
			|| t instanceof IOException)
		{
			return "Couldn't reach OSParty. Please try again in a moment.";
		}

		return "Something went wrong. Please try again.";
	}
}
