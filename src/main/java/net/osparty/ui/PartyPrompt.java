package net.osparty.ui;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.FontID;
import net.runelite.api.FontTypeFace;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.game.chatbox.ChatboxInput;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.util.Text;

/**
 * A party card in the game's chatbox: a heading, who is asking and what for, a few detail lines,
 * and real buttons that light up under the cursor and answer on a left click. This is what the
 * party Accept/Decline prompts render instead of a plain list of text options.
 *
 * <p>It is built out of widgets in the chatbox message layer, the same place RuneLite's own chatbox
 * inputs live, so it inherits their lifecycle for free: Escape closes it, so does clicking away or
 * anything else that shuts the message layer, and only one can ever be open. Number keys still pick
 * an option the way they do in a normal dialogue.
 */
public class PartyPrompt extends ChatboxInput implements KeyListener
{
	/** Left-click verb colours, picked so Accept and Decline read apart at a glance. */
	public static final int ACCEPT = 0x5FD75F;
	public static final int DECLINE = 0xE0876A;
	public static final int NEUTRAL = 0xD6C9AE;

	private static final int PAD = 12;
	private static final int HEADING_COLOR = 0x800000;
	private static final int INK = 0x000000;
	private static final int DIM = 0x5D4C33;
	private static final int NOTE_COLOR = 0x3B5C2E;
	private static final int WARNING_COLOR = 0x9B2020;
	private static final int DIVIDER = 0x9A8B6C;

	private static final int BUTTON_FILL = 0x453722;
	private static final int BUTTON_FILL_HOVER = 0x6A5636;
	private static final int BUTTON_EDGE = 0x241C11;
	private static final int BUTTON_EDGE_HOVER = 0xC9B27A;
	private static final int BUTTON_HEIGHT = 22;
	private static final int BUTTON_MAX_WIDTH = 118;
	private static final int BUTTON_GAP = 10;

	private static final int HEADING_HEIGHT = 13;
	private static final int TITLE_HEIGHT = 16;
	private static final int LINE_HEIGHT = 13;

	private static final String JOIN = " | ";

	private final ChatboxPanelManager chatboxPanelManager;
	private final List<String> details = new ArrayList<>();
	private final List<Option> options = new ArrayList<>();
	private String heading;
	private String title;
	private String target;
	private String meta;
	private String subtitle;
	private String warning;
	private String note;
	private Runnable onClose;

	private PartyPrompt(ChatboxPanelManager chatboxPanelManager)
	{
		this.chatboxPanelManager = chatboxPanelManager;
	}

	public static PartyPrompt create(ChatboxPanelManager chatboxPanelManager)
	{
		return new PartyPrompt(chatboxPanelManager);
	}

	/** Small caption above the rule, e.g. "Party invite". */
	public PartyPrompt heading(String heading)
	{
		this.heading = heading;
		return this;
	}

	/** The player this is about, and the right-click target for the buttons. */
	public PartyPrompt title(String title)
	{
		this.title = title;
		this.target = title;
		return this;
	}

	/** Right-aligned on the title row: party size, world, whatever is short. */
	public PartyPrompt meta(String meta)
	{
		this.meta = meta;
		return this;
	}

	/** What they're offering or asking for, under the name. */
	public PartyPrompt subtitle(String subtitle)
	{
		this.subtitle = subtitle;
		return this;
	}

	/** Short facts, packed onto as many lines as fit. */
	public PartyPrompt detail(String detail)
	{
		if (detail != null && !detail.isEmpty())
		{
			details.add(detail);
		}
		return this;
	}

	/** A red line that gets the first of the space details would have used. */
	public PartyPrompt warning(String warning)
	{
		this.warning = warning;
		return this;
	}

	/** The other player's own words, quoted. */
	public PartyPrompt note(String note)
	{
		this.note = note;
		return this;
	}

	public PartyPrompt option(String text, int color, Runnable action)
	{
		options.add(new Option(text, color, action));
		return this;
	}

	public PartyPrompt onClose(Runnable onClose)
	{
		this.onClose = onClose;
		return this;
	}

	public PartyPrompt build()
	{
		chatboxPanelManager.openInput(this);
		return this;
	}

	@Override
	protected void open()
	{
		Widget container = chatboxPanelManager.getContainerWidget();
		if (container == null || options.isEmpty())
		{
			return;
		}

		int width = container.getWidth();
		int contentWidth = width - PAD * 2;
		int buttonTop = container.getHeight() - BUTTON_HEIGHT - 8;
		int y = 5;

		if (heading != null)
		{
			text(container, heading, PAD, y, contentWidth, HEADING_HEIGHT, FontID.QUILL_8, HEADING_COLOR,
				WidgetTextAlignment.CENTER);
			y += HEADING_HEIGHT + 2;
		}
		divider(container, PAD, y, contentWidth);
		y += 5;

		if (title != null)
		{
			int titleWidth = contentWidth;
			if (meta != null)
			{
				Widget right = text(container, meta, PAD, y + 2, contentWidth, TITLE_HEIGHT - 2,
					FontID.PLAIN_12, DIM, WidgetTextAlignment.RIGHT);
				titleWidth -= measure(right, right.getText()) + 8;
			}
			text(container, title, PAD, y, titleWidth, TITLE_HEIGHT, FontID.BOLD_12, INK,
				WidgetTextAlignment.LEFT);
			y += TITLE_HEIGHT + 1;
		}

		if (subtitle != null)
		{
			text(container, subtitle, PAD, y, contentWidth, LINE_HEIGHT + 2, FontID.PLAIN_12, INK,
				WidgetTextAlignment.LEFT);
			y += LINE_HEIGHT + 2;
		}

		if (warning != null && y + LINE_HEIGHT <= buttonTop - 4)
		{
			text(container, "(!) " + warning, PAD, y, contentWidth, LINE_HEIGHT, FontID.PLAIN_11,
				WARNING_COLOR, WidgetTextAlignment.LEFT);
			y += LINE_HEIGHT;
		}

		for (String line : pack(container, details, contentWidth))
		{
			if (y + LINE_HEIGHT > buttonTop - 4)
			{
				break;
			}
			text(container, line, PAD, y, contentWidth, LINE_HEIGHT, FontID.PLAIN_11, DIM,
				WidgetTextAlignment.LEFT);
			y += LINE_HEIGHT;
		}

		if (note != null && y + LINE_HEIGHT <= buttonTop - 4)
		{
			text(container, '"' + note + '"', PAD, y, contentWidth, LINE_HEIGHT, FontID.PLAIN_11,
				NOTE_COLOR, WidgetTextAlignment.LEFT);
		}

		int buttonWidth = Math.min(BUTTON_MAX_WIDTH,
			(contentWidth - BUTTON_GAP * (options.size() - 1)) / options.size());
		int row = buttonWidth * options.size() + BUTTON_GAP * (options.size() - 1);
		int x = (width - row) / 2;
		for (Option option : options)
		{
			button(container, option, x, buttonTop, buttonWidth);
			x += buttonWidth + BUTTON_GAP;
		}
	}

	/** A filled plate under a bordered frame under the label, which owns the click and hover. */
	private void button(Widget container, Option option, int x, int y, int width)
	{
		Widget fill = container.createChild(-1, WidgetType.RECTANGLE);
		fill.setFilled(true);
		fill.setTextColor(BUTTON_FILL);
		fill.setOpacity(0);
		place(fill, x, y, width, BUTTON_HEIGHT);

		Widget edge = container.createChild(-1, WidgetType.RECTANGLE);
		edge.setFilled(false);
		edge.setTextColor(BUTTON_EDGE);
		edge.setOpacity(0);
		place(edge, x, y, width, BUTTON_HEIGHT);

		Widget label = container.createChild(-1, WidgetType.TEXT);
		label.setFontId(FontID.PLAIN_12);
		label.setText(option.text);
		label.setTextColor(option.color);
		label.setTextShadowed(true);
		label.setXTextAlignment(WidgetTextAlignment.CENTER);
		label.setYTextAlignment(WidgetTextAlignment.CENTER);
		if (target != null)
		{
			label.setName(JagexColors.MENU_TARGET_TAG + target);
		}
		label.setAction(0, option.text);
		label.setNoClickThrough(true);
		label.setOnOpListener((JavaScriptCallback) ev -> select(option));
		label.setOnMouseOverListener((JavaScriptCallback) ev ->
		{
			fill.setTextColor(BUTTON_FILL_HOVER);
			edge.setTextColor(BUTTON_EDGE_HOVER);
			label.setTextColor(0xFFFFFF);
		});
		label.setOnMouseLeaveListener((JavaScriptCallback) ev ->
		{
			fill.setTextColor(BUTTON_FILL);
			edge.setTextColor(BUTTON_EDGE);
			label.setTextColor(option.color);
		});
		label.setHasListener(true);
		place(label, x, y, width, BUTTON_HEIGHT);
	}

	private void select(Option option)
	{
		Widget container = chatboxPanelManager.getContainerWidget();
		if (container != null)
		{
			container.setOnKeyListener((Object[]) null);
		}
		chatboxPanelManager.close();
		option.action.run();
	}

	@Override
	protected void close()
	{
		if (onClose != null)
		{
			onClose.run();
		}
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
		if (!chatboxPanelManager.shouldTakeInput())
		{
			return;
		}
		char c = e.getKeyChar();
		if (c == '\033')
		{
			chatboxPanelManager.close();
			e.consume();
			return;
		}
		int n = c - '1';
		if (n >= 0 && n < options.size())
		{
			select(options.get(n));
			e.consume();
		}
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (chatboxPanelManager.shouldTakeInput() && e.getKeyCode() == KeyEvent.VK_ESCAPE)
		{
			e.consume();
		}
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
	}

	private Widget text(Widget container, String value, int x, int y, int width, int height, int font,
		int color, int alignment)
	{
		Widget widget = container.createChild(-1, WidgetType.TEXT);
		widget.setFontId(font);
		widget.setText(fit(widget, Text.escapeJagex(value), width));
		widget.setTextColor(color);
		widget.setXTextAlignment(alignment);
		widget.setYTextAlignment(WidgetTextAlignment.CENTER);
		place(widget, x, y, width, height);
		return widget;
	}

	private void divider(Widget container, int x, int y, int width)
	{
		Widget line = container.createChild(-1, WidgetType.LINE);
		line.setTextColor(DIVIDER);
		place(line, x, y, width, 0);
	}

	private static void place(Widget widget, int x, int y, int width, int height)
	{
		widget.setOriginalX(x);
		widget.setOriginalY(y);
		widget.setOriginalWidth(width);
		widget.setOriginalHeight(height);
		widget.revalidate();
	}

	/** Greedily fill lines with as many details as measure out under {@code width}. */
	private List<String> pack(Widget container, List<String> parts, int width)
	{
		List<String> lines = new ArrayList<>();
		if (parts.isEmpty())
		{
			return lines;
		}
		Widget ruler = container.createChild(-1, WidgetType.TEXT);
		ruler.setFontId(FontID.PLAIN_11);
		ruler.setHidden(true);

		StringBuilder line = new StringBuilder();
		for (String part : parts)
		{
			String escaped = Text.escapeJagex(part);
			if (line.length() > 0 && measure(ruler, line + JOIN + escaped) > width)
			{
				lines.add(line.toString());
				line = new StringBuilder(escaped);
			}
			else
			{
				line.append(line.length() > 0 ? JOIN : "").append(escaped);
			}
		}
		lines.add(line.toString());
		return lines;
	}

	private static int measure(Widget widget, String value)
	{
		FontTypeFace font = widget.getFont();
		// No font yet means the cache is still loading; a rough guess beats dropping the text.
		return font == null ? value.length() * 6 : font.getTextWidth(value);
	}

	private static String fit(Widget widget, String value, int width)
	{
		if (measure(widget, value) <= width)
		{
			return value;
		}
		String cut = value;
		while (cut.length() > 1 && measure(widget, cut + "...") > width)
		{
			cut = cut.substring(0, cut.length() - 1);
		}
		return cut + "...";
	}

	private static final class Option
	{
		private final String text;
		private final int color;
		private final Runnable action;

		private Option(String text, int color, Runnable action)
		{
			this.text = text;
			this.color = color;
			this.action = action;
		}
	}
}
