package com.unityclan.ui;

import com.unityclan.members.ClanMemberInfo;
import com.unityclan.temple.TempleOSRSService;
import com.unityclan.temple.TempleOSRSService.CollectionLogItem;
import com.unityclan.wom.UnityClanEventsService;
import com.unityclan.wom.UnityClanEventsService.EventData;
import com.unityclan.wom.UnityClanEventsService.Standing;

import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UnityClanActivityPanel extends JPanel
{
    private static final Color CARD = Color.decode("#302526");
    private static final Color INNER = Color.decode("#403233");
    private static final Color GOLD = Color.decode("#D6A34E");
    private static final Color MUTED = Color.decode("#B8A7A1");
    private static final long REFRESH_INTERVAL = 6 * 60 * 60 * 1000L;
    private static final int MAX_MEMBERS = 20;
    private static final int MAX_DISPLAY = 12;
    private final TempleOSRSService temple = TempleOSRSService.shared();
    private final UnityClanEventsService eventsService = UnityClanEventsService.shared();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "unity-clan-activity");
        t.setDaemon(true);
        return t;
    });
    private final JComboBox<String> filter = new JComboBox<>(
            new String[]{"All Activity", "Collection Logs", "Competitions"});
    private final JButton refresh = new JButton("Refresh");
    private final JLabel status = new JLabel("Waiting for clan roster...");
    private final JPanel entriesPanel = new JPanel();
    private final List<Activity> entries = new ArrayList<>();
    private List<String> roster = Collections.emptyList();
    private boolean loading;
    private long lastRefresh;
    private final DateTimeFormatter dates = DateTimeFormatter.ofPattern("MMM d, yyyy")
            .withZone(ZoneId.systemDefault());

    public UnityClanActivityPanel()
    {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(CARD);
        setBorder(BorderFactory.createEmptyBorder(12, 10, 12, 10));
        setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel title = new JLabel("CLAN ACTIVITY FEED");
        title.setForeground(GOLD);
        title.setFont(new Font("Arial", Font.BOLD, 11));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(title);
        add(Box.createVerticalStrut(8));
        filter.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        filter.setAlignmentX(Component.LEFT_ALIGNMENT);
        filter.addActionListener(e -> render());
        add(filter);
        add(Box.createVerticalStrut(6));
        refresh.setAlignmentX(Component.LEFT_ALIGNMENT);
        refresh.addActionListener(e -> load(true));
        add(refresh);
        add(Box.createVerticalStrut(8));
        entriesPanel.setLayout(new BoxLayout(entriesPanel, BoxLayout.Y_AXIS));
        entriesPanel.setBackground(CARD);
        entriesPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(entriesPanel);
        add(Box.createVerticalStrut(6));
        status.setForeground(MUTED);
        status.setFont(new Font("Arial", Font.PLAIN, 10));
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(status);
        render();
    }

    public void updateMembers(List<ClanMemberInfo> members)
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            List<ClanMemberInfo> copy = new ArrayList<>(members);
            SwingUtilities.invokeLater(() -> updateMembers(copy));
            return;
        }
        Set<String> unique = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (ClanMemberInfo member : members)
        {
            if (member.getName() != null && !member.getName().trim().isEmpty())
                unique.add(member.getName().trim());
        }
        List<String> names = new ArrayList<>(unique);
        boolean changed = !names.equals(roster);
        roster = names;
        if (changed || System.currentTimeMillis() - lastRefresh >= REFRESH_INTERVAL)
            load(false);
    }

    private void load(boolean force)
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            SwingUtilities.invokeLater(() -> load(force));
            return;
        }
        if (loading) return;
        if (!force && System.currentTimeMillis() - lastRefresh < REFRESH_INTERVAL) return;
        loading = true;
        refresh.setEnabled(false);
        status.setText("Loading activity...");
        List<String> names = new ArrayList<>(roster);
        worker.execute(() -> {
            List<Activity> fetched = new ArrayList<>();
            int successful = 0;
            boolean eventsLoaded = false;
            try
            {
                for (EventData event : eventsService.getCachedEvents())
                {
                    if (!"Completed".equals(event.getStatus()) || event.getStandings().isEmpty()) continue;
                    Standing winner = event.getStandings().get(0);
                    String type = event.getTitle().toUpperCase(Locale.ROOT).contains("SOTW") ? "Skilling" :
                            event.getTitle().toUpperCase(Locale.ROOT).contains("BOTW") ? "Bossing" :
                                    event.getTitle().toUpperCase(Locale.ROOT).contains("PVM") ? "PvM" : event.getType();
                    fetched.add(new Activity(false, event.getEndsAt().getEpochSecond(),
                            event.getTitle(), "Winner: " + winner.getName() + " | " + type));
                }
                eventsLoaded = true;
            }
            catch (Exception ignored) { /* Show partial results when WOM is unavailable. */ }
            for (String name : names.subList(0, Math.min(names.size(), MAX_MEMBERS)))
            {
                try
                {
                    for (CollectionLogItem item : temple.fetchRecentItems(name))
                    {
                        fetched.add(new Activity(true, item.getDateUnix(),
                                name + " - " + item.getName(),
                                item.isNotable() ? "Notable collection log unlock" : "Collection log unlock"));
                    }
                    successful++;
                }
                catch (Exception ignored) { /* Some players may not have TempleOSRS data. */ }
            }
            fetched.sort(Comparator.comparingLong((Activity a) -> a.timestamp).reversed());
            final int loadedMembers = successful;
            final boolean loadedEvents = eventsLoaded;
            SwingUtilities.invokeLater(() -> {
                entries.clear();
                entries.addAll(fetched);
                lastRefresh = System.currentTimeMillis();
                loading = false;
                refresh.setEnabled(true);
                status.setText("TempleOSRS: " + loadedMembers + "/" + Math.min(names.size(), MAX_MEMBERS)
                        + " online members | WOM: " + (loadedEvents ? "loaded" : "unavailable"));
                render();
            });
        });
    }

    public void onEventsUpdated() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::onEventsUpdated); return; }
        if (lastRefresh == 0 && !roster.isEmpty()) load(false);
        else {
            entries.removeIf(activity -> !activity.collection);
            for (EventData event : eventsService.getCachedEvents()) {
                if (!"Completed".equals(event.getStatus()) || event.getStandings().isEmpty()) continue;
                Standing winner = event.getStandings().get(0);
                entries.add(new Activity(false, event.getEndsAt().getEpochSecond(), event.getTitle(), "Winner: " + winner.getName() + " | " + event.getType()));
            }
            entries.sort(Comparator.comparingLong((Activity a) -> a.timestamp).reversed());
            render();
        }
    }

    private void render()
    {
        entriesPanel.removeAll();
        int shown = 0;
        int selection = filter.getSelectedIndex();
        for (Activity entry : entries)
        {
            if (selection == 1 && !entry.collection) continue;
            if (selection == 2 && entry.collection) continue;
            if (shown++ >= MAX_DISPLAY) break;
            JPanel row = new JPanel();
            row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
            row.setBackground(INNER);
            row.setBorder(BorderFactory.createEmptyBorder(7, 7, 7, 7));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 76));
            JLabel heading = new JLabel("<html>" + escape(entry.title) + "</html>");
            heading.setForeground(entry.collection ? Color.WHITE : GOLD);
            heading.setFont(new Font("Arial", Font.BOLD, 11));
            JLabel details = new JLabel("<html>" + escape(entry.details) + "</html>");
            details.setForeground(MUTED);
            details.setFont(new Font("Arial", Font.PLAIN, 10));
            JLabel date = new JLabel(entry.timestamp > 0 ? dates.format(Instant.ofEpochSecond(entry.timestamp)) : "Date unavailable");
            date.setForeground(MUTED);
            date.setFont(new Font("Arial", Font.PLAIN, 10));
            row.add(heading);
            row.add(Box.createVerticalStrut(3));
            row.add(details);
            row.add(Box.createVerticalStrut(3));
            row.add(date);
            entriesPanel.add(row);
            entriesPanel.add(Box.createVerticalStrut(5));
        }
        if (shown == 0)
        {
            JLabel empty = new JLabel("No activity to display");
            empty.setForeground(MUTED);
            entriesPanel.add(empty);
        }
        entriesPanel.revalidate();
        entriesPanel.repaint();
    }

    private static String escape(String text)
    {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final class Activity
    {
        final boolean collection;
        final long timestamp;
        final String title;
        final String details;
        Activity(boolean collection, long timestamp, String title, String details)
        {
            this.collection = collection;
            this.timestamp = timestamp;
            this.title = title;
            this.details = details;
        }
    }
}
