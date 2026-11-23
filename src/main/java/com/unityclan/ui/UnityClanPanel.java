package com.unityclan.ui;

import com.unityclan.members.ClanMemberInfo;
import com.unityclan.wom.WiseOldManService.CompetitionData;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Desktop;
import java.net.URI;
import javax.swing.JOptionPane;
import java.awt.Font;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import net.runelite.client.ui.PluginPanel;

public class UnityClanPanel extends PluginPanel
{
    private static final String DISCORD_INVITE = "https://discord.gg/unity-07";
    private static final Color BACKGROUND = Color.decode("#242424");
    private static final Color BUTTON_COLOR = Color.decode("#333333");
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentPanel = new JPanel(cardLayout);
    private final UnityClanDashboard dashboard = new UnityClanDashboard();
    private final UnityClanMembersPanel membersPanel = new UnityClanMembersPanel();
    private final UnityClanEventsPanel eventsPanel = new UnityClanEventsPanel();
    private final UnityClanRandomEventsPanel randomEventsPanel = new UnityClanRandomEventsPanel();
    private List<ClanMemberInfo> latestRoster;

    public UnityClanPanel()
    {
        setLayout(new BorderLayout());
        setBackground(BACKGROUND);
        JLabel title = new JLabel("UNITY CLAN");
        title.setFont(new Font("Arial", Font.BOLD, 20));
        title.setForeground(Color.WHITE);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        title.setBorder(BorderFactory.createEmptyBorder(15, 0, 15, 0));
        add(title, BorderLayout.NORTH);

        JPanel navigation = new JPanel();
        navigation.setLayout(new BoxLayout(navigation, BoxLayout.Y_AXIS));
        navigation.setBackground(BACKGROUND);
        addNavigationButton(navigation, "Dashboard", "dashboard");
        addNavigationButton(navigation, "Clan Members", "members");
        addNavigationButton(navigation, "Clan Events", "events");
        addNavigationButton(navigation, "Random Trivia", "random-events");
        addNavigationButton(navigation, "Discord", "discord");

        contentPanel.setBackground(BACKGROUND);
        contentPanel.add(dashboard, "dashboard");
        contentPanel.add(membersPanel, "members");
        contentPanel.add(eventsPanel, "events");
        contentPanel.add(randomEventsPanel, "random-events");

        JPanel main = new JPanel(new BorderLayout());
        main.setBackground(BACKGROUND);
        main.add(navigation, BorderLayout.NORTH);
        main.add(contentPanel, BorderLayout.CENTER);
        add(main, BorderLayout.CENTER);
        eventsPanel.setOnEventsChanged(events -> {
            dashboard.updateSharedEvents(events);
            dashboard.onEventsUpdated();
        });
        cardLayout.show(contentPanel, "dashboard");
    }

    private void addNavigationButton(JPanel parent, String text, String page)
    {
        JButton button = new JButton(text);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 35));
        button.setAlignmentX(LEFT_ALIGNMENT);
        button.setBackground(BUTTON_COLOR);
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.addActionListener(event -> {
            if ("discord".equals(page)) {
                try {
                    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        throw new UnsupportedOperationException("Browser opening is unavailable");
                    }
                    Desktop.getDesktop().browse(new URI(DISCORD_INVITE));
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this,
                            "Could not open Discord automatically. Visit: " + DISCORD_INVITE,
                            "Unity Clan Discord", JOptionPane.INFORMATION_MESSAGE);
                }
                return;
            }
            cardLayout.show(contentPanel, page);
            if ("events".equals(page)) eventsPanel.refresh(false);
            randomEventsPanel.setPolling("random-events".equals(page));
        });
        parent.add(button);
        parent.add(Box.createVerticalStrut(5));
    }
    public void stopRandomEvents() { randomEventsPanel.setPolling(false); }

    public void updateClanInfo(String clanName, boolean inClan)
    {
        dashboard.updateClanInfo(clanName, inClan);
    }

    public void updateMemberCount(int online)
    {
        membersPanel.updateMemberCount(online);
    }

    public void updateMembers(List<ClanMemberInfo> members)
    {
        membersPanel.updateMembers(members);
        dashboard.updateMembers(members);
        latestRoster = members;
        randomEventsPanel.updateRoster(members);
    }

    public void setUsername(String username) {
        dashboard.setUsername(username);
        randomEventsPanel.setUsername(username);
        randomEventsPanel.updateRoster(latestRoster);
    }

    public void updateCompetition(CompetitionData competition, String username)
    {
        dashboard.updateCompetition(competition, username);
    }
}
