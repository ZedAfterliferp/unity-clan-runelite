package com.unityclan.ui;

import com.unityclan.wom.WiseOldManService.CompetitionData;
import com.unityclan.wom.WiseOldManService.PlayerStanding;
import com.unityclan.wom.UnityClanEventsService.EventData;
import com.unityclan.wom.UnityClanEventsService.Standing;
import java.util.ArrayList;
import java.util.Comparator;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import com.unityclan.members.ClanMemberInfo;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

public class UnityClanDashboard extends JPanel
{
    private static final Color BACKGROUND =
            Color.decode("#191719");

    private static final Color CARD =
            Color.decode("#302526");

    private static final Color INNER =
            Color.decode("#403233");

    private static final Color GOLD =
            Color.decode("#D6A34E");

    private static final Color MUTED =
            Color.decode("#B8A7A1");

    private static final Color GREEN =
            Color.decode("#69C88A");

    private static final Color SILVER =
            Color.decode("#C9CCD2");

    private static final Color BRONZE =
            Color.decode("#BD8561");

    private final JLabel clanNameLabel =
            new JLabel("Not loaded");

    private final JLabel statusLabel =
            new JLabel("Waiting for login");

    private final JLabel competitionTitle =
            new JLabel("Loading competition...");

    private final JLabel competitionStatus =
            new JLabel("WAITING");

    private final JLabel competitionDates =
            new JLabel("");

    private final JLabel countdownLabel =
            new JLabel("");

    private final JLabel[] rankLabels =
            new JLabel[3];

    private final JLabel[] playerLabels =
            new JLabel[3];

    private final JLabel[] scoreLabels =
            new JLabel[3];

    private final JLabel personalRank =
            new JLabel("--", SwingConstants.CENTER);

    private final JLabel personalScore =
            new JLabel("--", SwingConstants.CENTER);

    private CompetitionData currentCompetition;
    private EventData sharedEvent;
    private List<EventData> sharedEvents = java.util.Collections.emptyList();
    private String localUsername;

    private final UnityClanTopPlayersPanel topPlayersPanel = new UnityClanTopPlayersPanel();
    private final UnityClanActivityPanel activityPanel = new UnityClanActivityPanel();
    private final UnityClanWeeklyXpPanel weeklyXpPanel = new UnityClanWeeklyXpPanel();
    private final JLabel trackedMembersLabel = new JLabel("--", SwingConstants.CENTER);
    private final JLabel combinedXpLabel = new JLabel("--", SwingConstants.CENTER);
    private final JLabel averageLevelLabel = new JLabel("--", SwingConstants.CENTER);
    private final JLabel bossKillsLabel = new JLabel("--", SwingConstants.CENTER);
    private final JLabel xpLeaderLabel = new JLabel("Waiting for clan statistics...");

    private final DateTimeFormatter dateFormatter =
            DateTimeFormatter.ofPattern(
                    "MMM d, yyyy", Locale.US
            ).withZone(ZoneId.systemDefault());

    public UnityClanDashboard()
    {
        // These labels are also updated when their optional cards are hidden.
        // Initialize them independently of createLeaderboardCard().
        for (int i = 0; i < 3; i++)
        {
            playerLabels[i] = new JLabel("Waiting...");
            scoreLabels[i] = new JLabel("--");
        }
        setLayout(
                new BoxLayout(this, BoxLayout.Y_AXIS)
        );

        setBackground(BACKGROUND);
        setBorder(
                BorderFactory.createEmptyBorder(
                        10, 8, 15, 8
                )
        );

        add(createHeader());
        add(spacing(10));
        add(createClanCard());
        add(spacing(10));
        add(createClanStatisticsCard());
        add(spacing(10));
        add(weeklyXpPanel);
        add(spacing(10));
        add(createCompetitionCard());
        add(spacing(10));
        add(topPlayersPanel);
        add(spacing(10));
        add(activityPanel);
        add(spacing(12));
        add(createFooter());

        Timer timer = new Timer(
                60000,
                event -> updateCountdown()
        );
        timer.start();
        topPlayersPanel.setStatisticsListener(this::updateClanStatistics);
    }

    private JPanel createHeader()
    {
        JPanel header = verticalPanel(BACKGROUND);

        JLabel title = new JLabel("UNITY 07");
        title.setFont(
                new Font("Arial", Font.BOLD, 22)
        );
        title.setForeground(GOLD);

        JLabel subtitle =
                new JLabel("CLAN DASHBOARD");
        subtitle.setFont(
                new Font("Arial", Font.BOLD, 10)
        );
        subtitle.setForeground(MUTED);

        header.add(title);
        header.add(spacing(3));
        header.add(subtitle);

        return header;
    }

    private JPanel createClanCard()
    {
        JPanel card = card();

        JLabel heading =
                sectionLabel("CLAN STATUS");

        clanNameLabel.setFont(
                new Font("Arial", Font.BOLD, 15)
        );
        clanNameLabel.setForeground(Color.WHITE);

        statusLabel.setFont(
                new Font("Arial", Font.PLAIN, 12)
        );
        statusLabel.setForeground(MUTED);

        card.add(heading);
        card.add(spacing(8));
        card.add(clanNameLabel);
        card.add(spacing(5));
        card.add(statusLabel);

        return card;
    }

    private JPanel createCompetitionCard()
    {
        JPanel card = card();

        card.add(
                sectionLabel("CURRENT / UPCOMING EVENT")
        );
        card.add(spacing(10));

        competitionTitle.setFont(
                new Font("Arial", Font.BOLD, 14)
        );
        competitionTitle.setForeground(
                Color.WHITE
        );

        competitionStatus.setFont(
                new Font("Arial", Font.BOLD, 11)
        );
        competitionStatus.setForeground(GOLD);

        competitionDates.setFont(
                new Font("Arial", Font.PLAIN, 11)
        );
        competitionDates.setForeground(MUTED);

        countdownLabel.setFont(
                new Font("Arial", Font.PLAIN, 11)
        );
        countdownLabel.setForeground(GOLD);

        card.add(competitionTitle);
        card.add(spacing(8));
        card.add(competitionStatus);
        card.add(spacing(7));
        card.add(competitionDates);
        card.add(spacing(7));
        card.add(countdownLabel);

        return card;
    }

    private JPanel createLeaderboardCard()
    {
        JPanel card = card();

        card.add(
                sectionLabel("TOP 3 LEADERBOARD")
        );
        card.add(spacing(10));

        Color[] medalColors = {
                GOLD, SILVER, BRONZE
        };

        for (int i = 0; i < 3; i++)
        {
            JPanel row = new JPanel(
                    new BorderLayout(8, 0)
            );
            row.setBackground(INNER);
            row.setMaximumSize(
                    new Dimension(
                            Integer.MAX_VALUE, 42
                    )
            );
            row.setBorder(
                    BorderFactory.createEmptyBorder(
                            7, 7, 7, 7
                    )
            );

            rankLabels[i] = new JLabel(
                    String.valueOf(i + 1),
                    SwingConstants.CENTER
            );
            rankLabels[i].setOpaque(true);
            rankLabels[i].setBackground(
                    medalColors[i]
            );
            rankLabels[i].setForeground(
                    BACKGROUND
            );
            rankLabels[i].setFont(
                    new Font("Arial", Font.BOLD, 12)
            );
            rankLabels[i].setPreferredSize(
                    new Dimension(26, 26)
            );

            playerLabels[i] =
                    new JLabel("Waiting...");
            playerLabels[i].setForeground(
                    Color.WHITE
            );
            playerLabels[i].setFont(
                    new Font("Arial", Font.PLAIN, 12)
            );

            scoreLabels[i] =
                    new JLabel("--");
            scoreLabels[i].setForeground(
                    GOLD
            );
            scoreLabels[i].setFont(
                    new Font("Arial", Font.BOLD, 12)
            );

            row.add(
                    rankLabels[i],
                    BorderLayout.WEST
            );
            row.add(
                    playerLabels[i],
                    BorderLayout.CENTER
            );
            row.add(
                    scoreLabels[i],
                    BorderLayout.EAST
            );

            card.add(row);

            if (i < 2)
            {
                card.add(spacing(5));
            }
        }

        return card;
    }

    private JPanel createPersonalCard()
    {
        JPanel card = card();

        card.add(
                sectionLabel("YOUR PERFORMANCE")
        );
        card.add(spacing(10));

        JPanel stats = new JPanel(
                new GridLayout(1, 2, 8, 0)
        );
        stats.setBackground(CARD);
        stats.setMaximumSize(
                new Dimension(
                        Integer.MAX_VALUE, 85
                )
        );

        stats.add(
                createStatBox(
                        "PLACEMENT",
                        personalRank,
                        GOLD
                )
        );

        stats.add(
                createStatBox(
                        "SCORE",
                        personalScore,
                        Color.WHITE
                )
        );

        card.add(stats);

        return card;
    }

    private JPanel createStatBox(
            String heading,
            JLabel value,
            Color valueColor)
    {
        JPanel box = new JPanel();
        box.setLayout(
                new BoxLayout(
                        box,
                        BoxLayout.Y_AXIS
                )
        );
        box.setBackground(INNER);
        box.setBorder(
                BorderFactory.createEmptyBorder(
                        10, 4, 10, 4
                )
        );

        JLabel label = new JLabel(
                heading,
                SwingConstants.CENTER
        );
        label.setAlignmentX(
                Component.CENTER_ALIGNMENT
        );
        label.setForeground(MUTED);
        label.setFont(
                new Font("Arial", Font.BOLD, 10)
        );

        value.setAlignmentX(
                Component.CENTER_ALIGNMENT
        );
        value.setForeground(valueColor);
        value.setFont(
                new Font("Arial", Font.BOLD, 22)
        );

        box.add(label);
        box.add(spacing(5));
        box.add(value);

        return box;
    }

    private JPanel createClanStatisticsCard()
    {
        JPanel card = card();
        card.add(sectionLabel("CLAN STATISTICS"));
        card.add(spacing(9));
        JPanel stats = new JPanel(new GridLayout(2, 2, 7, 7));
        stats.setBackground(CARD);
        stats.setMaximumSize(new Dimension(Integer.MAX_VALUE, 154));
        stats.add(createStatBox("TRACKED MEMBERS", trackedMembersLabel, GOLD));
        stats.add(createStatBox("COMBINED XP", combinedXpLabel, Color.WHITE));
        stats.add(createStatBox("AVG TOTAL LEVEL", averageLevelLabel, GOLD));
        stats.add(createStatBox("BOSS KILLS", bossKillsLabel, Color.WHITE));
        card.add(stats);
        card.add(spacing(9));
        xpLeaderLabel.setForeground(MUTED);
        xpLeaderLabel.setFont(new Font("Arial", Font.PLAIN, 11));
        xpLeaderLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(xpLeaderLabel);
        return card;
    }

    private void updateClanStatistics(UnityClanTopPlayersPanel.ClanStatistics stats)
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            SwingUtilities.invokeLater(() -> updateClanStatistics(stats));
            return;
        }
        if (stats.trackedMembers == 0)
        {
            trackedMembersLabel.setText("--");
            combinedXpLabel.setText("--");
            averageLevelLabel.setText("--");
            bossKillsLabel.setText("--");
            xpLeaderLabel.setText("Waiting for clan statistics...");
            return;
        }
        trackedMembersLabel.setText(String.format(Locale.US, "%,d", stats.trackedMembers));
        combinedXpLabel.setText(compactNumber(stats.combinedXp));
        averageLevelLabel.setText(String.format(Locale.US, "%,.0f", stats.averageTotalLevel));
        bossKillsLabel.setText(compactNumber(stats.combinedBossKills));
        xpLeaderLabel.setText("Highest XP: " + stats.highestXpPlayer + " (" + compactNumber(stats.highestXp) + ")");
        xpLeaderLabel.setToolTipText(String.format(Locale.US, "%s: %,d XP", stats.highestXpPlayer, stats.highestXp));
        combinedXpLabel.setToolTipText(String.format(Locale.US, "%,d XP", stats.combinedXp));
        bossKillsLabel.setToolTipText(String.format(Locale.US, "%,d recorded kills", stats.combinedBossKills));
    }

    private static String compactNumber(long value)
    {
        if (value >= 1_000_000_000L) return String.format(Locale.US, "%.2fB", value / 1_000_000_000.0);
        if (value >= 1_000_000L) return String.format(Locale.US, "%.2fM", value / 1_000_000.0);
        if (value >= 10_000L) return String.format(Locale.US, "%.1fK", value / 1_000.0);
        return String.format(Locale.US, "%,d", value);
    }

    private JPanel createFooter()
    {
        JPanel footer =
                verticalPanel(BACKGROUND);

        JLabel label = new JLabel(
                "Wise Old Man | Updates every 5 min"
        );
        label.setFont(
                new Font("Arial", Font.PLAIN, 10)
        );
        label.setForeground(MUTED);

        footer.add(label);

        return footer;
    }

    private JPanel card()
    {
        JPanel panel = verticalPanel(CARD);
        panel.setBorder(
                BorderFactory.createEmptyBorder(
                        12, 10, 12, 10
                )
        );
        return panel;
    }

    private JPanel verticalPanel(Color color)
    {
        JPanel panel = new JPanel();
        panel.setLayout(
                new BoxLayout(
                        panel,
                        BoxLayout.Y_AXIS
                )
        );
        panel.setBackground(color);
        panel.setAlignmentX(
                Component.LEFT_ALIGNMENT
        );
        return panel;
    }

    private JLabel sectionLabel(String text)
    {
        JLabel label = new JLabel(text);
        label.setForeground(GOLD);
        label.setFont(
                new Font("Arial", Font.BOLD, 11)
        );
        label.setAlignmentX(
                Component.LEFT_ALIGNMENT
        );
        return label;
    }

    private Component spacing(int height)
    {
        return Box.createVerticalStrut(height);
    }

    public void updateMembers(List<ClanMemberInfo> members)
    {
        weeklyXpPanel.updateMembers(members);
        topPlayersPanel.updateMembers(members);
        activityPanel.updateMembers(members);
    }

    public void updateClanInfo(
            String clanName,
            boolean inClan)
    {
        clanNameLabel.setText(clanName);

        statusLabel.setText(
                inClan
                        ? "In Clan"
                        : "Not in Clan"
        );

        statusLabel.setForeground(
                inClan ? GREEN : MUTED
        );
    }

    public void updateCompetition(
            CompetitionData competition,
            String username)
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            SwingUtilities.invokeLater(
                    () -> updateCompetition(
                            competition, username
                    )
            );
            return;
        }

        weeklyXpPanel.setUsername(username);
        currentCompetition = competition;
        sharedEvent = null;

        if (competition == null)
        {
            competitionTitle.setText(
                    "No competitions found"
            );
            competitionStatus.setText(
                    "UNAVAILABLE"
            );
            competitionDates.setText("");
            countdownLabel.setText("");

            for (int i = 0; i < 3; i++)
            {
                playerLabels[i].setText(
                        "No results"
                );
                scoreLabels[i].setText("--");
            }

            personalRank.setText("--");
            personalScore.setText("--");
            return;
        }

        competitionTitle.setText(
                competition.getTitle()
        );

        competitionDates.setText(
                dateFormatter.format(
                        competition.getStartsAt()
                )
                        + " - "
                        + dateFormatter.format(
                        competition.getEndsAt()
                )
        );

        List<PlayerStanding> standings =
                competition.getStandings();

        for (int i = 0; i < 3; i++)
        {
            if (i < standings.size())
            {
                PlayerStanding player =
                        standings.get(i);

                playerLabels[i].setText(
                        player.getName()
                );

                scoreLabels[i].setText(
                        formatScore(
                                player.getGained()
                        )
                );
            }
            else
            {
                playerLabels[i].setText(
                        "No participant"
                );
                scoreLabels[i].setText("--");
            }
        }

        PlayerStanding personal =
                competition.getPersonalStanding(
                        username
                );

        if (personal != null)
        {
            personalRank.setText(
                    "#" + personal.getRank()
            );

            personalScore.setText(
                    formatScore(
                            personal.getGained()
                    )
            );
        }
        else
        {
            personalRank.setText("--");
            personalScore.setText("--");
        }

        updateCountdown();
    }

    private String formatScore(double score)
    {
        if (score == Math.rint(score))
        {
            return String.format(
                    Locale.US, "%,.0f", score
            );
        }

        return String.format(
                Locale.US, "%,.2f", score
        );
    }

    private void updateCountdown()
    {
        if (sharedEvent == null && currentCompetition == null)
        {
            return;
        }

        Instant now = Instant.now();

        String status =
                sharedEvent != null ? sharedEvent.getStatus() : currentCompetition.getStatus();

        competitionStatus.setText(
                status.toUpperCase(Locale.US)
        );

        if ("Active".equals(status))
        {
            competitionStatus.setForeground(
                    GREEN
            );
        }
        else
        {
            competitionStatus.setForeground(
                    GOLD
            );
        }

        Instant target;
        String prefix;

        if (now.isBefore(
                (sharedEvent != null ? sharedEvent.getStartsAt() : currentCompetition.getStartsAt())
        ))
        {
            target =
                    (sharedEvent != null ? sharedEvent.getStartsAt() : currentCompetition.getStartsAt());
            prefix = "Starts in: ";
        }
        else if (now.isBefore(
                (sharedEvent != null ? sharedEvent.getEndsAt() : currentCompetition.getEndsAt())
        ))
        {
            target =
                    (sharedEvent != null ? sharedEvent.getEndsAt() : currentCompetition.getEndsAt());
            prefix = "Ends in: ";
        }
        else
        {
            countdownLabel.setText(
                    "Competition completed"
            );
            return;
        }

        Duration remaining =
                Duration.between(now, target);

        long days = remaining.toDays();
        long hours =
                remaining.toHours() % 24;
        long minutes =
                remaining.toMinutes() % 60;

        countdownLabel.setText(
                prefix
                        + days + "d "
                        + hours + "h "
                        + minutes + "m"
        );
    }

    public void onEventsUpdated() { activityPanel.onEventsUpdated(); }

    public void setUsername(String username) {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> setUsername(username)); return; }
        if (java.util.Objects.equals(localUsername, username)) return;
        localUsername = username;
        weeklyXpPanel.setUsername(username);
        updateSharedEvents(sharedEvents);
    }

    public void updateSharedEvents(List<EventData> events) {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> updateSharedEvents(events)); return; }
        sharedEvents = events == null ? java.util.Collections.emptyList() : new ArrayList<>(events);
        Instant now = Instant.now();
        EventData selected = sharedEvents.stream().filter(e -> !now.isBefore(e.getStartsAt()) && now.isBefore(e.getEndsAt()))
                .min(Comparator.comparing(EventData::getEndsAt)).orElse(null);
        if (selected == null) selected = sharedEvents.stream().filter(e -> now.isBefore(e.getStartsAt()))
                .min(Comparator.comparing(EventData::getStartsAt)).orElse(null);
        if (selected == null) selected = sharedEvents.stream().filter(e -> !now.isBefore(e.getEndsAt()))
                .max(Comparator.comparing(EventData::getEndsAt)).orElse(null);
        sharedEvent = selected;
        currentCompetition = null;
        if (selected == null) {
            competitionTitle.setText("No competitions found");
            competitionStatus.setText("UNAVAILABLE");
            competitionDates.setText("");
            countdownLabel.setText("");
            for (int i = 0; i < 3; i++) { playerLabels[i].setText("No results"); scoreLabels[i].setText("--"); }
            personalRank.setText("--"); personalScore.setText("--");
            return;
        }
        competitionTitle.setText(selected.getTitle());
        competitionDates.setText(dateFormatter.format(selected.getStartsAt()) + " - " + dateFormatter.format(selected.getEndsAt()));
        List<Standing> standings = selected.getStandings();
        for (int i = 0; i < 3; i++) {
            playerLabels[i].setText(i < standings.size() ? standings.get(i).getName() : "No participant");
            scoreLabels[i].setText(i < standings.size() ? formatScore(standings.get(i).getGained()) : "--");
        }
        Standing personal = null;
        if (localUsername != null) for (Standing standing : standings) {
            if (standing.getName().replace('_',' ').equalsIgnoreCase(localUsername.replace('_',' '))) { personal = standing; break; }
        }
        personalRank.setText(personal == null ? "--" : "#" + personal.getRank());
        personalScore.setText(personal == null ? "--" : formatScore(personal.getGained()));
        updateCountdown();
    }
}