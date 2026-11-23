package com.unityclan.ui;

import com.unityclan.members.ClanMemberInfo;
import com.unityclan.wom.WiseOldManPlayerService;
import com.unityclan.wom.WiseOldManPlayerService.BossStats;
import com.unityclan.wom.WiseOldManPlayerService.PlayerStats;
import com.unityclan.temple.TempleOSRSService;
import com.unityclan.temple.TempleOSRSService.CollectionLogItem;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

public class UnityClanMembersPanel extends JPanel
{
    private static final Color BACKGROUND =
            Color.decode("#242424");

    private static final Color CARD_BACKGROUND =
            Color.decode("#333333");

    private static final Color EXPANDED_BACKGROUND =
            Color.decode("#302526");

    private static final Color SECTION_BACKGROUND =
            Color.decode("#403233");

    private static final Color TEXT_SECONDARY =
            Color.decode("#BBBBBB");

    private static final Color GOLD =
            Color.decode("#D6A34E");

    private static final long UI_CACHE_DURATION =
            24 * 60 * 60 * 1000L;

    private final JLabel memberCountLabel =
            new JLabel("Members online: Not loaded");

    private final JTextField searchField =
            new JTextField();

    private final JComboBox<String> sortBox =
            new JComboBox<>(new String[]{
                    "Name A-Z",
                    "Name Z-A",
                    "Rank",
                    "World"
            });

    private final JPanel memberListPanel =
            new JPanel();

    private final List<ClanMemberInfo> allMembers =
            new ArrayList<>();

    private String expandedMember = null;

    private final WiseOldManPlayerService playerService =
            WiseOldManPlayerService.shared();

    private final TempleOSRSService templeService =
            TempleOSRSService.shared();

    private final ExecutorService playerExecutor =
            Executors.newFixedThreadPool(2, runnable ->
            {
                Thread thread = new Thread(
                        runnable,
                        "unity-clan-member-data"
                );

                thread.setDaemon(true);
                return thread;
            });

    private final Map<String, PlayerStats> playerStats =
            new HashMap<>();

    private final Map<String, String> playerErrors =
            new HashMap<>();

    private final Set<String> loadingPlayers =
            new HashSet<>();

    private final Map<String, Long> playerLoadedAt =
            new HashMap<>();

    private final Map<String, List<CollectionLogItem>>
            collectionLogs = new HashMap<>();

    private final Map<String, String> collectionErrors =
            new HashMap<>();

    private final Set<String> loadingCollections =
            new HashSet<>();

    private final Map<String, Long> collectionLoadedAt =
            new HashMap<>();

    public UnityClanMembersPanel()
    {
        setLayout(new BorderLayout());
        setBackground(BACKGROUND);

        JPanel header = new JPanel();

        header.setLayout(
                new BoxLayout(header, BoxLayout.Y_AXIS)
        );

        header.setBackground(BACKGROUND);

        header.setBorder(
                BorderFactory.createEmptyBorder(
                        15, 10, 10, 10
                )
        );

        JLabel heading = new JLabel("Clan Members");

        heading.setFont(
                new Font("Arial", Font.BOLD, 18)
        );

        heading.setForeground(Color.WHITE);

        heading.setAlignmentX(
                Component.CENTER_ALIGNMENT
        );

        memberCountLabel.setForeground(TEXT_SECONDARY);

        memberCountLabel.setAlignmentX(
                Component.CENTER_ALIGNMENT
        );

        header.add(heading);
        header.add(Box.createVerticalStrut(8));
        header.add(memberCountLabel);
        header.add(Box.createVerticalStrut(15));

        searchField.setToolTipText(
                "Search clan members"
        );

        searchField.setBackground(CARD_BACKGROUND);
        searchField.setForeground(Color.WHITE);
        searchField.setCaretColor(Color.WHITE);

        searchField.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, 30)
        );

        header.add(searchField);
        header.add(Box.createVerticalStrut(10));

        sortBox.setBackground(CARD_BACKGROUND);
        sortBox.setForeground(Color.WHITE);

        sortBox.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, 30)
        );

        header.add(sortBox);

        add(header, BorderLayout.NORTH);

        memberListPanel.setLayout(
                new BoxLayout(
                        memberListPanel,
                        BoxLayout.Y_AXIS
                )
        );

        memberListPanel.setBackground(BACKGROUND);

        JScrollPane scrollPane =
                new JScrollPane(memberListPanel);

        scrollPane.setBorder(null);

        scrollPane.getVerticalScrollBar()
                .setUnitIncrement(16);

        add(scrollPane, BorderLayout.CENTER);

        searchField.getDocument().addDocumentListener(
                new DocumentListener()
                {
                    @Override
                    public void insertUpdate(DocumentEvent e)
                    {
                        refreshMemberList();
                    }

                    @Override
                    public void removeUpdate(DocumentEvent e)
                    {
                        refreshMemberList();
                    }

                    @Override
                    public void changedUpdate(DocumentEvent e)
                    {
                        refreshMemberList();
                    }
                }
        );

        sortBox.addActionListener(
                event -> refreshMemberList()
        );
    }

    public void updateMemberCount(int online)
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            SwingUtilities.invokeLater(
                    () -> updateMemberCount(online)
            );
            return;
        }

        memberCountLabel.setText(
                "Members online: " + online
        );
    }

    public void updateMembers(
            List<ClanMemberInfo> members)
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            List<ClanMemberInfo> copy =
                    new ArrayList<>(members);

            SwingUtilities.invokeLater(
                    () -> updateMembers(copy)
            );

            return;
        }

        allMembers.clear();
        allMembers.addAll(members);

        refreshMemberList();
    }

    private void refreshMemberList()
    {
        memberListPanel.removeAll();

        String search = searchField.getText()
                .trim()
                .toLowerCase(Locale.ROOT);

        List<ClanMemberInfo> filtered =
                new ArrayList<>();

        for (ClanMemberInfo member : allMembers)
        {
            if (member.getName()
                    .toLowerCase(Locale.ROOT)
                    .contains(search))
            {
                filtered.add(member);
            }
        }

        String sorting =
                (String) sortBox.getSelectedItem();

        if ("Name A-Z".equals(sorting))
        {
            filtered.sort(
                    Comparator.comparing(
                            ClanMemberInfo::getName,
                            String.CASE_INSENSITIVE_ORDER
                    )
            );
        }
        else if ("Name Z-A".equals(sorting))
        {
            filtered.sort(
                    Comparator.comparing(
                            ClanMemberInfo::getName,
                            String.CASE_INSENSITIVE_ORDER
                    ).reversed()
            );
        }
        else if ("Rank".equals(sorting))
        {
            filtered.sort(
                    Comparator.comparing(
                            ClanMemberInfo::getRank,
                            String.CASE_INSENSITIVE_ORDER
                    )
            );
        }
        else if ("World".equals(sorting))
        {
            filtered.sort(
                    Comparator.comparingInt(
                            ClanMemberInfo::getWorld
                    )
            );
        }

        if (filtered.isEmpty())
        {
            JLabel emptyLabel = new JLabel(
                    "No members found",
                    SwingConstants.CENTER
            );

            emptyLabel.setForeground(TEXT_SECONDARY);

            emptyLabel.setAlignmentX(
                    Component.CENTER_ALIGNMENT
            );

            memberListPanel.add(
                    Box.createVerticalStrut(20)
            );

            memberListPanel.add(emptyLabel);
        }

        for (ClanMemberInfo member : filtered)
        {
            memberListPanel.add(
                    createMemberCard(member)
            );

            memberListPanel.add(
                    Box.createVerticalStrut(6)
            );
        }

        // Consume unused viewport height without stretching member cards.
        memberListPanel.add(Box.createVerticalGlue());

        memberListPanel.revalidate();
        memberListPanel.repaint();
    }

    private JPanel createMemberCard(
            ClanMemberInfo member)
    {
        String memberKey =
                normalizeName(member.getName());

        boolean expanded =
                memberKey.equals(expandedMember);

        JPanel card = new JPanel();

        card.setLayout(
                new BoxLayout(card, BoxLayout.Y_AXIS)
        );

        card.setBackground(
                expanded
                        ? EXPANDED_BACKGROUND
                        : CARD_BACKGROUND
        );

        card.setAlignmentX(
                Component.LEFT_ALIGNMENT
        );

        card.setBorder(
                BorderFactory.createEmptyBorder(
                        10, 10, 10, 10
                )
        );

        JPanel header = new JPanel(
                new BorderLayout(6, 0)
        );

        header.setOpaque(false);

        header.setAlignmentX(
                Component.LEFT_ALIGNMENT
        );

        JLabel nameLabel =
                new JLabel(member.getName());

        nameLabel.setForeground(Color.WHITE);

        nameLabel.setFont(
                new Font("Arial", Font.BOLD, 13)
        );

        JLabel worldLabel =
                new JLabel("W" + member.getWorld());

        worldLabel.setForeground(TEXT_SECONDARY);

        worldLabel.setFont(
                new Font("Arial", Font.PLAIN, 11)
        );

        JLabel arrowLabel = new JLabel(
                expanded ? "\u25B2" : "\u25BC"
        );

        arrowLabel.setForeground(GOLD);

        JPanel right = new JPanel(
                new BorderLayout(6, 0)
        );

        right.setOpaque(false);

        right.add(
                worldLabel,
                BorderLayout.CENTER
        );

        right.add(
                arrowLabel,
                BorderLayout.EAST
        );

        header.add(
                nameLabel,
                BorderLayout.CENTER
        );

        header.add(
                right,
                BorderLayout.EAST
        );

        JLabel rankLabel = new JLabel(
                "Rank: " + member.getRank()
        );

        rankLabel.setForeground(TEXT_SECONDARY);

        rankLabel.setFont(
                new Font("Arial", Font.PLAIN, 12)
        );

        rankLabel.setAlignmentX(
                Component.LEFT_ALIGNMENT
        );

        card.add(header);
        card.add(Box.createVerticalStrut(4));
        card.add(rankLabel);

        MouseAdapter clickListener =
                new MouseAdapter()
                {
                    @Override
                    public void mouseClicked(MouseEvent event)
                    {
                        if (memberKey.equals(expandedMember))
                        {
                            expandedMember = null;
                        }
                        else
                        {
                            expandedMember = memberKey;

                            loadPlayerStats(
                                    member.getName(),
                                    memberKey
                            );

                            loadCollectionLogs(
                                    member.getName(),
                                    memberKey
                            );
                        }

                        refreshMemberList();
                    }
                };

        header.addMouseListener(clickListener);
        nameLabel.addMouseListener(clickListener);
        worldLabel.addMouseListener(clickListener);
        arrowLabel.addMouseListener(clickListener);
        right.addMouseListener(clickListener);
        rankLabel.addMouseListener(clickListener);

        Cursor handCursor =
                Cursor.getPredefinedCursor(
                        Cursor.HAND_CURSOR
                );

        header.setCursor(handCursor);
        nameLabel.setCursor(handCursor);
        worldLabel.setCursor(handCursor);
        arrowLabel.setCursor(handCursor);
        right.setCursor(handCursor);
        rankLabel.setCursor(handCursor);

        if (expanded)
        {
            card.add(Box.createVerticalStrut(12));

            card.add(
                    createExpandedDetails(member)
            );
        }

        // BoxLayout otherwise distributes unused vertical space to cards,
        // especially when a search leaves only one matching member.
        card.setMaximumSize(new Dimension(
                Integer.MAX_VALUE,
                card.getPreferredSize().height
        ));

        return card;
    }

    private void loadPlayerStats(
            String username,
            String memberKey)
    {
        Long loadedAt =
                playerLoadedAt.get(memberKey);

        if (loadedAt != null
                && System.currentTimeMillis() - loadedAt
                < UI_CACHE_DURATION)
        {
            return;
        }

        if (loadingPlayers.contains(memberKey))
        {
            return;
        }

        loadingPlayers.add(memberKey);
        playerErrors.remove(memberKey);

        playerExecutor.submit(() ->
        {
            try
            {
                PlayerStats stats =
                        playerService.fetchPlayer(username);

                SwingUtilities.invokeLater(() ->
                {
                    playerStats.put(memberKey, stats);

                    playerLoadedAt.put(
                            memberKey,
                            System.currentTimeMillis()
                    );

                    playerErrors.remove(memberKey);
                    loadingPlayers.remove(memberKey);

                    if (memberKey.equals(expandedMember))
                    {
                        refreshMemberList();
                    }
                });
            }
            catch (Exception exception)
            {
                SwingUtilities.invokeLater(() ->
                {
                    playerErrors.put(
                            memberKey,
                            errorMessage(exception)
                    );

                    playerStats.remove(memberKey);
                    playerLoadedAt.remove(memberKey);
                    loadingPlayers.remove(memberKey);

                    if (memberKey.equals(expandedMember))
                    {
                        refreshMemberList();
                    }
                });
            }
        });
    }

    private void loadCollectionLogs(
            String username,
            String memberKey)
    {
        Long loadedAt =
                collectionLoadedAt.get(memberKey);

        if (loadedAt != null
                && System.currentTimeMillis() - loadedAt
                < UI_CACHE_DURATION)
        {
            return;
        }

        if (loadingCollections.contains(memberKey))
        {
            return;
        }

        loadingCollections.add(memberKey);
        collectionErrors.remove(memberKey);

        playerExecutor.submit(() ->
        {
            try
            {
                List<CollectionLogItem> items =
                        templeService.fetchRecentItems(username);

                SwingUtilities.invokeLater(() ->
                {
                    collectionLogs.put(
                            memberKey,
                            items
                    );

                    collectionLoadedAt.put(
                            memberKey,
                            System.currentTimeMillis()
                    );

                    collectionErrors.remove(memberKey);
                    loadingCollections.remove(memberKey);

                    if (memberKey.equals(expandedMember))
                    {
                        refreshMemberList();
                    }
                });
            }
            catch (Exception exception)
            {
                SwingUtilities.invokeLater(() ->
                {
                    collectionErrors.put(
                            memberKey,
                            errorMessage(exception)
                    );

                    collectionLogs.remove(memberKey);
                    collectionLoadedAt.remove(memberKey);
                    loadingCollections.remove(memberKey);

                    if (memberKey.equals(expandedMember))
                    {
                        refreshMemberList();
                    }
                });
            }
        });
    }

    private JPanel createExpandedDetails(
            ClanMemberInfo member)
    {
        String memberKey =
                normalizeName(member.getName());

        JPanel details = new JPanel();

        details.setLayout(
                new BoxLayout(
                        details,
                        BoxLayout.Y_AXIS
                )
        );

        details.setOpaque(false);

        details.setAlignmentX(
                Component.LEFT_ALIGNMENT
        );

        details.add(
                sectionTitle("PLAYER STATISTICS")
        );

        details.add(Box.createVerticalStrut(6));

        PlayerStats stats =
                playerStats.get(memberKey);

        if (loadingPlayers.contains(memberKey)
                && stats == null)
        {
            details.add(
                    infoSection(
                            "Wise Old Man",
                            "Loading player statistics..."
                    )
            );
        }
        else if (stats != null)
        {
            details.add(
                    createStatisticsSection(stats)
            );

            details.add(Box.createVerticalStrut(10));

            details.add(
                    sectionTitle("TOP 5 BOSSES")
            );

            details.add(Box.createVerticalStrut(6));

            details.add(
                    createBossSection(stats)
            );
        }
        else if (playerErrors.containsKey(memberKey))
        {
            details.add(
                    infoSection(
                            "Wise Old Man",
                            playerErrors.get(memberKey)
                    )
            );
        }
        else
        {
            details.add(
                    infoSection(
                            "Wise Old Man",
                            "Player statistics are not loaded."
                    )
            );
        }

        details.add(Box.createVerticalStrut(12));

        details.add(
                sectionTitle("RECENT COLLECTION LOGS")
        );

        details.add(Box.createVerticalStrut(6));

        List<CollectionLogItem> items =
                collectionLogs.get(memberKey);

        if (loadingCollections.contains(memberKey)
                && items == null)
        {
            details.add(
                    infoSection(
                            "TempleOSRS",
                            "Loading collection log unlocks..."
                    )
            );
        }
        else if (items != null)
        {
            details.add(
                    createCollectionSection(items)
            );
        }
        else if (collectionErrors.containsKey(memberKey))
        {
            details.add(
                    infoSection(
                            "TempleOSRS",
                            collectionErrors.get(memberKey)
                    )
            );
        }
        else
        {
            details.add(
                    infoSection(
                            "TempleOSRS",
                            "Collection logs are not loaded."
                    )
            );
        }

        details.add(Box.createVerticalStrut(6));

        details.add(
                wrappedText(
                        "Important: Players must sync "
                                + "their collection log to "
                                + "TempleOSRS. Unsynced data "
                                + "may be unavailable.",
                        10,
                        TEXT_SECONDARY
                )
        );


        return details;
    }

    private JPanel createStatisticsSection(
            PlayerStats stats)
    {
        if (!stats.hasSnapshot())
        {
            return infoSection(
                    "Wise Old Man",
                    "No recent player snapshot available."
            );
        }

        StringBuilder description =
                new StringBuilder();

        description.append("Total Level: ")
                .append(formatNumber(stats.getTotalLevel()));

        description.append("\nTotal XP: ")
                .append(formatNumber(stats.getTotalXp()));

        description.append("\nOverall Rank: ")
                .append(formatNumber(stats.getOverallRank()));

        description.append("\nEHP: ")
                .append(
                        String.format(
                                Locale.US,
                                "%,.2f",
                                stats.getEhp()
                        )
                );

        description.append("\nEHB: ")
                .append(
                        String.format(
                                Locale.US,
                                "%,.2f",
                                stats.getEhb()
                        )
                );

        return infoSection(
                "Wise Old Man",
                description.toString()
        );
    }

    private JPanel createBossSection(
            PlayerStats stats)
    {
        List<BossStats> bosses =
                stats.getBosses();

        if (bosses.isEmpty())
        {
            return infoSection(
                    "Boss Kill Counts",
                    "No tracked boss kills available."
            );
        }

        StringBuilder description =
                new StringBuilder();

        int limit = Math.min(5, bosses.size());

        for (int i = 0; i < limit; i++)
        {
            BossStats boss = bosses.get(i);

            if (i > 0)
            {
                description.append("\n");
            }

            description.append(boss.getName());
            description.append(": ");
            description.append(
                    formatNumber(boss.getKills())
            );
        }

        return infoSection(
                "Boss Kill Counts",
                description.toString()
        );
    }

    private JPanel createCollectionSection(
            List<CollectionLogItem> items)
    {
        if (items.isEmpty())
        {
            return infoSection(
                    "TempleOSRS",
                    "No recent collection log unlocks "
                            + "were returned for this player."
            );
        }

        StringBuilder description =
                new StringBuilder();

        for (int i = 0; i < items.size(); i++)
        {
            CollectionLogItem item =
                    items.get(i);

            if (i > 0)
            {
                description.append("\n\n");
            }

            description.append(
                    item.getName()
            );

            if (item.isNotable())
            {
                description.append(" [Notable]");
            }

            if (item.getDate() != null
                    && !item.getDate().isEmpty())
            {
                description.append("\n");
                description.append(
                        item.getDate()
                );
            }
        }

        return infoSection(
                "TempleOSRS",
                description.toString()
        );
    }

    private JLabel sectionTitle(String text)
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

    private JPanel infoSection(
            String heading,
            String description)
    {
        JPanel panel = new JPanel();

        panel.setLayout(
                new BoxLayout(
                        panel,
                        BoxLayout.Y_AXIS
                )
        );

        panel.setBackground(SECTION_BACKGROUND);

        panel.setBorder(
                BorderFactory.createEmptyBorder(
                        8, 8, 8, 8
                )
        );

        panel.setAlignmentX(
                Component.LEFT_ALIGNMENT
        );

        JLabel title = new JLabel(heading);

        title.setForeground(Color.WHITE);

        title.setFont(
                new Font("Arial", Font.BOLD, 12)
        );

        title.setAlignmentX(
                Component.LEFT_ALIGNMENT
        );

        JTextArea text = wrappedText(
                description,
                11,
                TEXT_SECONDARY
        );

        panel.add(title);
        panel.add(Box.createVerticalStrut(4));
        panel.add(text);

        return panel;
    }

    private JTextArea wrappedText(
            String content,
            int fontSize,
            Color color)
    {
        JTextArea text = new JTextArea(content);

        text.setEditable(false);
        text.setFocusable(false);
        text.setOpaque(false);

        text.setForeground(color);

        text.setFont(
                new Font(
                        "Arial",
                        Font.PLAIN,
                        fontSize
                )
        );

        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setBorder(null);

        text.setAlignmentX(
                Component.LEFT_ALIGNMENT
        );

        return text;
    }

    private String normalizeName(String name)
    {
        return name.trim()
                .replace('_', ' ')
                .toLowerCase(Locale.ROOT);
    }

    private String formatNumber(long number)
    {
        return String.format(
                Locale.US,
                "%,d",
                number
        );
    }

    private String errorMessage(Exception exception)
    {
        String message = exception.getMessage();

        if (message == null || message.trim().isEmpty())
        {
            return "Unable to retrieve player data.";
        }

        return message;
    }
}

