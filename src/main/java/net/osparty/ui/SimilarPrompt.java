package net.osparty.ui;

import java.util.List;
import net.osparty.model.Activity;
import net.osparty.model.Advertisement;
import net.runelite.client.game.chatbox.ChatboxPanelManager;

/**
 * The in-game card asking whether to join a party already running this, rather than advertise beside
 * it. Shown on the way through a create, so every button finishes the click the player made.
 *
 * <p>Only the best match gets a button. The rest are named in the body, because a list of buttons is
 * a decision, and the decision worth making here is join-or-create, not which one.
 */
public final class SimilarPrompt
{
	private SimilarPrompt()
	{
	}

	public static PartyPrompt open(ChatboxPanelManager chatboxPanelManager, SimilarParties similar,
		Runnable join, Runnable createAnyway, Runnable createAndStopAsking, Runnable onClose)
	{
		List<Advertisement> matches = similar.matches();
		Advertisement best = matches.get(0);
		Activity activity = Activity.fromId(best.getActivity());

		PartyPrompt prompt = PartyPrompt.create(chatboxPanelManager);
		InvitePrompt.partyPage(prompt, best, best.getHost())
			.heading(matches.size() == 1
				? "This is already running"
				: matches.size() + " parties are already running this")
			.detail(AdText.requirementText(activity, best))
			.detail(AdText.neededRolesText(activity, PartyCardPanel.neededRolesOf(best)))
			.detail(PartyCardPanel.tagLine(best))
			.note(InvitePrompt.note(best))
			.page("Request join", PartyPrompt.ACCEPT, join)
			.option("Create anyway", PartyPrompt.NEUTRAL, createAnyway)
			.option("Create, don't ask again", PartyPrompt.DECLINE, createAndStopAsking)
			.onClose(onClose)
			.build();
		return prompt;
	}

	/** Hold the card while the application goes out, so it never blanks between pages. */
	public static void showRequesting(PartyPrompt prompt, Advertisement ad)
	{
		InvitePrompt.partyPage(prompt.reset(), ad, ad.getHost())
			.heading("Requesting a place")
			.detail("Asking " + ad.getHost() + " to let you in...")
			.refresh();
	}
}
