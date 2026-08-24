package net.osparty.ui;

import java.util.ArrayList;
import java.util.List;
import net.osparty.api.PartyInvite;
import net.osparty.model.Activity;
import net.osparty.model.Advertisement;
import net.runelite.client.game.chatbox.ChatboxPanelManager;

/** Turns a received {@link PartyInvite} into the in-game card the invited player answers. */
public final class InvitePrompt
{
	private InvitePrompt()
	{
	}

	public static void open(ChatboxPanelManager chatboxPanelManager, PartyInvite invite, String inviter,
		Runnable accept, Runnable decline, Runnable onClose)
	{
		Advertisement ad = invite.getAd();
		Activity activity = Activity.fromId(ad.getActivity());

		PartyPrompt prompt = PartyPrompt.create(chatboxPanelManager)
			.heading("Party invite")
			.title(inviter)
			.meta(meta(ad))
			.subtitle(subtitle(activity, ad))
			.detail(AdText.requirementText(activity, ad))
			.detail(AdText.neededRolesText(activity, PartyCardPanel.neededRolesOf(ad)))
			.detail(PartyCardPanel.tagLine(ad))
			.note(note(ad));

		prompt.option("Accept", PartyPrompt.ACCEPT, accept)
			.option("Decline", PartyPrompt.DECLINE, decline)
			.onClose(onClose)
			.build();
	}

	/** Party size and world, right-aligned next to the inviter's name. */
	private static String meta(Advertisement ad)
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

	private static String subtitle(Activity activity, Advertisement ad)
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
	private static String note(Advertisement ad)
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
