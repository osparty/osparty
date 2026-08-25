package net.osparty.ui;

import java.util.function.Consumer;
import net.osparty.model.Activity;
import net.osparty.model.Advertisement;
import net.runelite.client.game.chatbox.ChatboxPanelManager;

/**
 * The in-game card offering a party to someone who is looking for one. It reads as an invite does,
 * same header and same buttons, because from the player's side it is the same question, with one
 * difference the wording has to carry: nobody invited them, so the host still has to say yes.
 */
public final class MatchPrompt
{
	private MatchPrompt()
	{
	}

	public static PartyPrompt open(ChatboxPanelManager chatboxPanelManager, MatchOffer offer,
		Runnable join, Runnable dismiss, Runnable stopLooking, Runnable onClose)
	{
		Advertisement ad = offer.ad();
		Activity activity = Activity.fromId(ad.getActivity());

		PartyPrompt prompt = PartyPrompt.create(chatboxPanelManager);
		InvitePrompt.partyPage(prompt, ad, ad.getHost())
			.heading("Party found")
			.detail(AdText.requirementText(activity, ad))
			.detail(AdText.neededRolesText(activity, PartyCardPanel.neededRolesOf(ad)))
			.detail(PartyCardPanel.tagLine(ad))
			.note(InvitePrompt.note(ad))
			// Not an invite, so Accept would overpromise: the host has still to let them in.
			.page("Request join", PartyPrompt.ACCEPT, join)
			.option("Not now", PartyPrompt.NEUTRAL, dismiss)
			.option("Stop looking", PartyPrompt.DECLINE, stopLooking)
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

	/** Turn the card to the role question, so a match accepted in-game is finished in-game. */
	public static void showRolePicker(PartyPrompt prompt, Advertisement ad, Activity activity,
		java.util.List<net.osparty.model.Role> options, Consumer<String> onPicked)
	{
		InvitePrompt.showRolePicker(prompt, ad, activity, options, onPicked);
	}
}
