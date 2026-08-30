package net.osparty.ui;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.osparty.model.Role;
import net.osparty.tools.RaidPartyDetected;
import net.runelite.client.game.chatbox.ChatboxPanelManager;

/**
 * The in-game card asking whether to put the raid party the player just made onto the OSParty board.
 * Where the raid has roles, Advertise turns the page to the role question, so the whole answer is given
 * on the one card.
 */
public final class RaidPartyPrompt
{
	private RaidPartyPrompt()
	{
	}

	/**
	 * @param roles the roles the host could fill, asked for when the page turns so a mode chosen after the
	 *     card opened still gets the right set; unused for a raid without roles
	 * @param advertise takes the chosen role id, or null when there was nothing to choose
	 */
	public static PartyPrompt open(ChatboxPanelManager chatboxPanelManager, RaidPartyDetected detected,
		Supplier<List<Role>> roles, Consumer<String> advertise, Runnable notNow, Runnable dontAsk, Runnable onClose)
	{
		PartyPrompt prompt = PartyPrompt.create(chatboxPanelManager);
		offerPage(prompt, detected);
		if (detected.getActivity().hasRoles())
		{
			prompt.page("Advertise", PartyPrompt.ACCEPT,
				() -> rolePage(prompt.reset(), detected, roles.get(), advertise, notNow).refresh());
		}
		else
		{
			prompt.option("Advertise", PartyPrompt.ACCEPT, () -> advertise.accept(null));
		}
		return prompt
			.option("Not now", PartyPrompt.NEUTRAL, notNow)
			.option("Don't ask again", PartyPrompt.DECLINE, dontAsk)
			.onClose(onClose)
			.build();
	}

	private static PartyPrompt offerPage(PartyPrompt prompt, RaidPartyDetected detected)
	{
		return prompt
			.heading("Advertise on OSParty?")
			.title(detected.label())
			.subtitle("You just made a raid party. List it for others to apply to?")
			.detail(detected.getPreferredSize() > 0 ? "Party size " + detected.getPreferredSize() : null)
			.detail("You still accept or decline every applicant");
	}

	private static PartyPrompt rolePage(PartyPrompt prompt, RaidPartyDetected detected, List<Role> roles,
		Consumer<String> advertise, Runnable cancel)
	{
		prompt.heading("Which role will you fill?").title(detected.label());
		for (Role role : roles)
		{
			prompt.option(role.getDisplayName(), PartyPrompt.NEUTRAL, () -> advertise.accept(role.getId()));
		}
		return prompt.option("Cancel", PartyPrompt.DECLINE, cancel);
	}
}
