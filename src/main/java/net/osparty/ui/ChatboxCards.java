package net.osparty.ui;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.game.chatbox.ChatboxPanelManager;

/**
 * One queue for every Accept/Decline card the plugin puts in the chatbox, ordered by how much the
 * player is waiting on it. The chatbox holds a single panel at a time, so without somewhere to line
 * them up the sources compete: whoever checks first each tick wins, and a host with a steady stream
 * of applicants never sees anything else.
 *
 * <p>A card is offered from wherever its event arrives, on any thread, and shown from
 * {@link #tick()} on the client thread once the chatbox is free. It is dropped rather than shown if
 * it has gone {@link Card#isStale() stale} while it waited, which is the normal end for a card whose
 * question was answered in the side panel instead.
 */
@Singleton
public class ChatboxCards
{
	/**
	 * Precedence, most important first. Cards of one priority keep the order they were offered in.
	 * The ordering is about who is waiting on an answer: a player who just clicked something outranks
	 * someone whose party is waiting on us, who outranks an offer we went looking for ourselves.
	 */
	public enum Priority
	{
		/** The player acted and is held up until this is answered. */
		ACTION,
		/** Someone is asking to join the party we host. */
		JOIN_REQUEST,
		/** A friend invited us to theirs. */
		INVITE,
		/** We are looking for a party and one turned up. */
		MATCH
	}

	public interface Card
	{
		Priority priority();

		/** Build and show it. Client thread. Returns the card shown, or null if it could not be. */
		PartyPrompt open();

		/** True once this is no longer worth showing: answered elsewhere, or its party is gone. */
		default boolean isStale()
		{
			return false;
		}

		/** Identity for {@link ChatboxCards#dismiss}, or null when nothing else can resolve it. */
		default Object key()
		{
			return null;
		}
	}

	/**
	 * Ticks to wait for a card we opened to become the chatbox's current input before giving up on it.
	 * {@link ChatboxPanelManager#openInput} defers to the client thread's queue, so a card is never
	 * current the moment it is opened, and a card that never arrives must not wedge the queue shut.
	 */
	private static final int OPEN_GRACE_TICKS = 5;

	private final ChatboxPanelManager chatboxPanelManager;
	private final ConcurrentLinkedDeque<Card> pending = new ConcurrentLinkedDeque<>();

	private Card openCard;
	private PartyPrompt openPrompt;
	private boolean openConfirmed;
	private int waitedTicks;

	@Inject
	ChatboxCards(ChatboxPanelManager chatboxPanelManager)
	{
		this.chatboxPanelManager = chatboxPanelManager;
	}

	/** Queue a card to be shown when the chatbox is free. Any thread. */
	public void offer(Card card)
	{
		if (card != null)
		{
			pending.add(card);
		}
	}

	/** Show the next card if the chatbox is free. Client thread, once per game tick. */
	public void tick()
	{
		if (openPrompt != null && stillOpen())
		{
			return;
		}
		if (pending.isEmpty() || chatboxPanelManager.getCurrentInput() != null)
		{
			return;
		}
		Card next = takeNext();
		if (next == null)
		{
			return;
		}
		PartyPrompt prompt = next.open();
		if (prompt == null)
		{
			return;
		}
		openCard = next;
		openPrompt = prompt;
		openConfirmed = false;
		waitedTicks = 0;
	}

	/**
	 * @return whether the card we opened still holds the chatbox. A card that turns its own pages keeps
	 * the same input, so paging never looks like a close.
	 */
	private boolean stillOpen()
	{
		if (chatboxPanelManager.getCurrentInput() == openPrompt)
		{
			openConfirmed = true;
			return true;
		}
		if (!openConfirmed && ++waitedTicks <= OPEN_GRACE_TICKS)
		{
			return true; // opened this tick; it reaches the chatbox on the client thread's next drain
		}
		release();
		return false;
	}

	/** Close the open card if it is the one for {@code key}, and drop any queued card matching it. */
	public void dismiss(Object key)
	{
		if (key == null)
		{
			return;
		}
		pending.removeIf(card -> Objects.equals(key, card.key()));
		if (openCard != null && Objects.equals(key, openCard.key()))
		{
			chatboxPanelManager.close();
		}
	}

	/** The open card's prompt while it holds the chatbox, so its owner can turn it to another page. */
	public PartyPrompt current()
	{
		return openPrompt != null && chatboxPanelManager.getCurrentInput() == openPrompt ? openPrompt : null;
	}

	/** Drop everything queued at one priority, leaving the rest and anything already open alone. */
	public void clear(Priority priority)
	{
		pending.removeIf(card -> card.priority() == priority);
	}

	/** Drop every queued card and forget whatever is open. Does not close the chatbox. */
	public void clear()
	{
		pending.clear();
		release();
	}

	private void release()
	{
		openCard = null;
		openPrompt = null;
		openConfirmed = false;
		waitedTicks = 0;
	}

	/** The most important card still worth showing, removed from the queue along with any stale ones. */
	private Card takeNext()
	{
		Card best = null;
		for (Card card : pending)
		{
			if (card.isStale())
			{
				continue;
			}
			if (best == null || card.priority().ordinal() < best.priority().ordinal())
			{
				best = card;
			}
		}
		pending.removeIf(Card::isStale);
		if (best != null)
		{
			pending.remove(best);
		}
		return best;
	}
}
