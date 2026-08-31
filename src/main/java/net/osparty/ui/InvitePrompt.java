package net.osparty.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.osparty.api.PartyInvite;
import net.osparty.model.Activity;
import net.osparty.model.Advertisement;
import net.osparty.model.Role;
import net.runelite.client.game.chatbox.ChatboxPanelManager;

/**
 * The pages of the in-game invite card: the invite itself, the wait while its party is looked up,
 * and the role question that follows.
 *
 * <p>Accepting turns the page rather than closing the card, and every page after it is drawn onto
 * that same open panel, so the chatbox never blanks between them.
 */
public final class InvitePrompt
{
	private InvitePrompt()
	{
	}

	/** @return the card, so the pages that follow an Accept can be drawn onto it. */
	public static PartyPrompt open(ChatboxPanelManager chatboxPanelManager, PartyInvite invite, String inviter,
		Runnable accept, Runnable decline, Runnable onClose)
	{
		Advertisement ad = invite.getAd();
		PartyPrompt prompt = PartyPrompt.create(chatboxPanelManager);

		invitePage(prompt, ad, inviter)
			.page("Accept", PartyPrompt.ACCEPT, () ->
			{
				showJoining(prompt, ad);
				accept.run();
			})
			.option("Decline", PartyPrompt.DECLINE, decline)
			.onClose(onClose)
			.build();
		return prompt;
	}

	/** Holds the card while the party is looked up, so Accept answers instantly and nothing blanks. */
	public static void showJoining(PartyPrompt prompt, Advertisement ad)
	{
		partyPage(prompt.reset(), ad, ad.getHost())
			.heading("Joining party")
			.detail("Getting the party's details...")
			.refresh();
	}

	/** Turn the open card to the role question. */
	public static void showRolePicker(PartyPrompt prompt, Advertisement ad, Activity activity,
		List<Role> options, Consumer<String> onPicked)
	{
		rolePage(prompt.reset(), ad, options, onPicked).refresh();
	}

	/** Ask for a role on a card of its own, for when the invite card is already gone. */
	public static PartyPrompt openRolePicker(ChatboxPanelManager chatboxPanelManager, Advertisement ad,
		Activity activity, List<Role> options, Consumer<String> onPicked, Runnable onClose)
	{
		PartyPrompt prompt = PartyPrompt.create(chatboxPanelManager);
		rolePage(prompt, ad, options, onPicked)
			.onClose(onClose)
			.build();
		return prompt;
	}

	private static PartyPrompt invitePage(PartyPrompt prompt, Advertisement ad, String inviter)
	{
		Activity activity = Activity.fromId(ad.getActivity());
		return partyPage(prompt, ad, inviter)
			.heading("Party invite")
			.detail(AdText.requirementText(activity, ad))
			.detail(AdText.neededRolesText(activity, PartyCardPanel.neededRolesOf(ad)))
			.detail(PartyCardPanel.tagLine(ad))
			.note(note(ad));
	}

	private static PartyPrompt rolePage(PartyPrompt prompt, Advertisement ad, List<Role> options,
		Consumer<String> onPicked)
	{
		partyPage(prompt, ad, ad.getHost()).heading("Choose your role");
		for (Role role : options)
		{
			prompt.option(role.getDisplayName(), PartyPrompt.NEUTRAL, () -> onPicked.accept(role.getId()));
		}
		return prompt.option("Cancel", PartyPrompt.DECLINE, () -> onPicked.accept(null));
	}

	/** The header every party card shares, so only the heading and the body change between them. */
	static PartyPrompt partyPage(PartyPrompt prompt, Advertisement ad, String name)
	{
		return prompt
			.title(name)
			.meta(meta(ad))
			.subtitle(subtitle(Activity.fromId(ad.getActivity()), ad));
	}

	/** Party size and world, right-aligned next to the host's name. */
	static String meta(Advertisement ad)
	{
		List<String> parts = new ArrayList<>();
		if (ad.getCapacity() > 0)
		{
			parts.add(ad.getSize() + "/" + ad.getCapacity());
		}
		Integer world = PartyCardPanel.parseWorldNum(ad);
		if (world != null && world > 0)
		{
			parts.add("W" + world);
		}
		return parts.isEmpty() ? null : String.join(" | ", parts);
	}

	static String subtitle(Activity activity, Advertisement ad)
	{
		if (activity == null)
		{
			return "Party";
		}
		String name = activity.displayName(ad.isHardMode(), ad.getInvocation());
		String scale = PartyCardPanel.coxScaleOf(ad);
		return scale.isEmpty() ? name : name + " " + scale;
	}

	/** The host's own blurb, flattened to one line. */
	static String note(Advertisement ad)
	{
		String description = ad.getDescription();
		if (description == null)
		{
			return null;
		}
		String flat = description.replaceAll("\\s+", " ").trim();
		return flat.isEmpty() ? null : flat;
	}
}
