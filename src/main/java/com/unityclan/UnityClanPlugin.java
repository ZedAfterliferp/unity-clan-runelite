
package com.unityclan;

import com.unityclan.members.ClanMemberInfo;
import com.unityclan.ui.UnityClanPanel;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.swing.SwingUtilities;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.clan.ClanChannelMember;
import net.runelite.api.clan.ClanRank;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.clan.ClanTitle;
import net.runelite.api.events.ClanChannelChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;

import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
		name = "Unity Clan",
		description = "Clan management tools, events, and Discord integration",
		tags = {"clan", "unity", "discord", "events"}
)
public class UnityClanPlugin extends Plugin
{
	private static final Logger log =
			LoggerFactory.getLogger(UnityClanPlugin.class);

	private String lastRosterSignature = null;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;
	private NavigationButton navigationButton;
	private UnityClanPanel panel;

	private volatile boolean running;

	private int refreshTicks = 0;
	@Override
	protected void startUp()
	{
		running = true;
		refreshTicks = 0;
		lastRosterSignature = null;


		SwingUtilities.invokeLater(() ->
		{
			if (!running)
			{
				return;
			}

			panel = new UnityClanPanel();

			BufferedImage icon;
			try
			{
				java.net.URL iconResource = UnityClanPlugin.class.getResource("/unity_sidebar_16.png");
				if (iconResource == null)
				{
					log.error("Missing Unity Clan sidebar icon resource: /unity_sidebar_16.png");
					return;
				}
				icon = ImageIO.read(iconResource);
				if (icon == null)
				{
					log.error("Unable to decode Unity Clan sidebar icon");
					return;
				}
			}
			catch (IOException exception)
			{
				log.error("Failed to load Unity Clan sidebar icon", exception);
				return;
			}

			navigationButton = NavigationButton.builder()
					.tooltip("Unity Clan")
					.icon(icon)
					.priority(5)
					.panel(panel)
					.build();

			clientToolbar.addNavigation(navigationButton);

			requestClanRefresh();
		});

	}

	@Override
	protected void shutDown()
	{
		running = false;


		if (panel != null) panel.stopRandomEvents();

		SwingUtilities.invokeLater(() ->
		{
			if (navigationButton != null)
			{
				clientToolbar.removeNavigation(
						navigationButton
				);
				navigationButton = null;
			}

			panel = null;
		});

		lastRosterSignature = null;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN
				|| event.getGameState() == GameState.LOGIN_SCREEN)
		{
			requestClanRefresh();
		}
	}

	@Subscribe
	public void onClanChannelChanged(
			ClanChannelChanged event)
	{
		if (!event.isGuest())
		{
			requestClanRefresh();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		refreshTicks++;

		if (refreshTicks >= 10)
		{
			refreshTicks = 0;
			refreshClanData();
		}
	}

	private void requestClanRefresh()
	{
		if (running)
		{
			clientThread.invokeLater(
					this::refreshClanData
			);
		}
	}

	private void refreshClanData()
	{
		if (!running)
		{
			return;
		}

		String clanName = "Not loaded";
		boolean inClan = false;
		String username = null;

		List<ClanMemberInfo> members =
				new ArrayList<>();

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			Player localPlayer = client.getLocalPlayer();

			if (localPlayer != null)
			{
				username = localPlayer.getName();
			}

			ClanSettings clanSettings =
					client.getClanSettings();

			if (clanSettings != null)
			{
				clanName = clanSettings.getName();
				inClan = true;
			}
			else
			{
				clanName = "No clan";
			}

			ClanChannel clanChannel =
					client.getClanChannel();

			if (clanChannel != null)
			{
				for (ClanChannelMember member
						: clanChannel.getMembers())
				{
					String name = member.getName();
					ClanRank rank = member.getRank();

					String rankName = "Unknown";

					if (rank != null
							&& clanSettings != null)
					{
						ClanTitle title =
								clanSettings.titleForRank(rank);

						if (title != null)
						{
							rankName = title.getName();
						}
					}

					int world = member.getWorld();

					members.add(
							new ClanMemberInfo(
									name,
									rankName,
									world
							)
					);
				}
			}
		}

		final String finalClanName = clanName;
		final boolean finalInClan = inClan;
		final String finalUsername = username;
		final List<ClanMemberInfo> finalMembers = members;
		final String rosterSignature = finalClanName + "|" + finalInClan + "|" + finalUsername + "|" +
				members.stream().map(m -> m.getName() + ":" + m.getRank() + ":" + m.getWorld()).sorted().collect(java.util.stream.Collectors.joining("|"));
		if (rosterSignature.equals(lastRosterSignature)) return;
		lastRosterSignature = rosterSignature;

		SwingUtilities.invokeLater(() ->
		{
			if (!running || panel == null)
			{
				return;
			}

			panel.updateClanInfo(
					finalClanName,
					finalInClan
			);

			panel.updateMembers(finalMembers);

			panel.updateMemberCount(
					finalMembers.size()
			);

			panel.setUsername(finalUsername);
		});
	}
}
