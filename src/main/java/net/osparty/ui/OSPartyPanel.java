package net.osparty.ui;

import net.osparty.OSPartyConfig;
import net.osparty.HostApplicationHandler;
import net.osparty.api.PartyService;
import net.osparty.party.LiveParty;
import net.osparty.runewatch.RuneWatchService;
import java.awt.BorderLayout;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.runelite.api.vars.AccountType;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;

/**
 * Root side-panel. Hosts Search / Create tabs, plus a "Current" tab that only
 * appears while the player is in a party (hosting or joined). All three tabs
 * share a single {@link PartyState} so the membership rule (one party at a
 * time) and the Current tab's visibility stay in sync.
 */
public class OSPartyPanel extends PluginPanel
{
	private final PartyState partyState = new PartyState();
	private final MaterialTabGroup tabGroup;
	private final MaterialTab searchTab;
	private final MaterialTab currentTab;
	private boolean wasInParty;

	public OSPartyPanel(PartyService partyService, OSPartyConfig config, Supplier<String> playerNameSupplier,
		HostApplicationHandler hostApplicationHandler, Supplier<String> friendsChatOwnerSupplier,
		IntSupplier worldSupplier, ItemManager itemManager, LiveParty liveParty,
		RuneWatchService runeWatchService, Supplier<AccountType> accountTypeSupplier)
	{
		super(false);

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		// super(false) skips PluginPanel's default border, so add our own breathing
		// room — otherwise the tabs and content sit flush against the sidebar edges.
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		SearchPanel searchPanel = new SearchPanel(partyService, playerNameSupplier,
			friendsChatOwnerSupplier, worldSupplier, partyState, liveParty, accountTypeSupplier);
		CreatePanel createPanel = new CreatePanel(partyService, config, playerNameSupplier, partyState, liveParty,
			accountTypeSupplier);
		CurrentPanel currentPanel = new CurrentPanel(partyService, playerNameSupplier,
			hostApplicationHandler, partyState, itemManager, liveParty, runeWatchService);

		JPanel display = new JPanel(new BorderLayout());
		display.setBackground(ColorScheme.DARK_GRAY_COLOR);

		tabGroup = new MaterialTabGroup(display);
		searchTab = new MaterialTab("Search", tabGroup, searchPanel);
		MaterialTab createTab = new MaterialTab("Create", tabGroup, createPanel);
		currentTab = new MaterialTab("Current", tabGroup, currentPanel);

		tabGroup.addTab(searchTab);
		tabGroup.addTab(createTab);
		tabGroup.addTab(currentTab);

		// The Current tab only shows while in a party.
		currentTab.setVisible(false);
		tabGroup.select(searchTab);

		add(tabGroup, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);

		partyState.addListener(this::onPartyStateChanged);
	}

	private void onPartyStateChanged()
	{
		boolean inParty = partyState.isInParty();
		currentTab.setVisible(inParty);

		if (inParty && !wasInParty)
		{
			// Just entered a party — jump to the Current tab.
			tabGroup.select(currentTab);
		}
		else if (!inParty && wasInParty && currentTab.isSelected())
		{
			// Left the party while viewing it — fall back to Search.
			tabGroup.select(searchTab);
		}

		wasInParty = inParty;
		tabGroup.revalidate();
		tabGroup.repaint();
	}
}
