package com.unityclan.ui;

import com.unityclan.wom.UnityClanEventsService;
import com.unityclan.wom.WiseOldManRequestGate;
import com.google.gson.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import com.unityclan.wom.UnityClanEventsService.EventData;
import com.unityclan.wom.UnityClanEventsService.Standing;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class UnityClanEventsPanel extends JPanel
{
    private static final Color BACKGROUND = Color.decode("#242424");
    private static final Color CARD = Color.decode("#333333");
    private static final Color EXPANDED = Color.decode("#302526");
    private static final Color GOLD = Color.decode("#D6A34E");
    private static final Color SECONDARY = Color.decode("#BBBBBB");
    private static final Color SILVER = Color.decode("#C0C0C0");
    private static final Color BRONZE = Color.decode("#CD7F32");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy")
            .withZone(ZoneId.systemDefault());

    private final JPanel content = new JPanel();
    private final JLabel status = new JLabel("Loading competitions...");
    private final UnityClanEventsService service = UnityClanEventsService.shared();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "unity-clan-events");
        thread.setDaemon(true);
        return thread;
    });

    private static final long REFRESH_MS = TimeUnit.HOURS.toMillis(6);
    private java.util.function.Consumer<List<EventData>> onEventsChanged = ignored -> {};
    public void setOnEventsChanged(java.util.function.Consumer<List<EventData>> listener) {
        onEventsChanged = listener == null ? ignored -> {} : listener;
        onEventsChanged.accept(events);
    }

    private List<EventData> events = Collections.emptyList();
    // Null means the group roster is unavailable: never hide results on a failed lookup.
    private Set<String> currentGroupNames;
    private long lastGroupRefresh;
    private boolean groupLoading;
    private static final long GROUP_REFRESH_MS = TimeUnit.MINUTES.toMillis(30);

    private static String playerKey(String name) {
        return name.replace('_', ' ').replace('\u00a0', ' ').trim().toLowerCase(Locale.ROOT);
    }

    private void refreshGroupMembership() {
        if (groupLoading || System.currentTimeMillis() - lastGroupRefresh < GROUP_REFRESH_MS) return;
        groupLoading = true;
        executor.execute(() -> {
            Set<String> names = null;
            try {
                names = WiseOldManRequestGate.execute(() -> {
                    HttpURLConnection connection = (HttpURLConnection) new URL(
                            "https://api.wiseoldman.net/v2/groups/10453").openConnection();
                    try {
                        connection.setConnectTimeout(7000);
                        connection.setReadTimeout(10000);
                        connection.setRequestProperty("Accept", "application/json");
                        if (connection.getResponseCode() != 200) throw new IOException("Group HTTP " + connection.getResponseCode());
                        try (Reader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                            JsonObject group = new JsonParser().parse(reader).getAsJsonObject();
                            JsonArray memberships = group.getAsJsonArray("memberships");
                            if (memberships == null) throw new IOException("Missing group memberships");
                            Set<String> result = new HashSet<>();
                            for (JsonElement item : memberships) {
                                JsonObject membership = item.getAsJsonObject();
                                JsonObject player = membership.getAsJsonObject("player");
                                if (player != null && player.has("username") && !player.get("username").isJsonNull()) {
                                    result.add(playerKey(player.get("username").getAsString()));
                                }
                            }
                            return result;
                        }
                    } finally { connection.disconnect(); }
                });
            } catch (Exception ignored) {
                // Preserve existing standings if the group service is unavailable.
            }
            final Set<String> loaded = names;
            SwingUtilities.invokeLater(() -> {
                groupLoading = false;
                lastGroupRefresh = System.currentTimeMillis();
                if (loaded != null) currentGroupNames = loaded;
                render();
            });
        });
    }

    private Integer expandedId;
    private boolean winnersExpanded;
    private boolean loading;
    private String error;
    private long lastLoaded;
    private final JScrollPane scroll;

    public UnityClanEventsPanel()
    {
        setLayout(new BorderLayout());
        setBackground(BACKGROUND);

        JPanel header = new JPanel(new BorderLayout(0, 8));
        header.setBackground(BACKGROUND);
        header.setBorder(BorderFactory.createEmptyBorder(12, 10, 10, 10));

        JLabel title = new JLabel("CLAN EVENTS");
        title.setFont(new Font("Arial", Font.BOLD, 17));
        title.setForeground(GOLD);
        header.add(title, BorderLayout.NORTH);

        JPanel tools = new JPanel(new BorderLayout(5, 0));
        tools.setOpaque(false);
        status.setForeground(SECONDARY);
        status.setFont(new Font("Arial", Font.PLAIN, 10));
        JButton refresh = new JButton("Refresh");
        refresh.setFont(new Font("Arial", Font.PLAIN, 11));
        refresh.addActionListener(e -> refresh(true));
        tools.add(status, BorderLayout.CENTER);
        tools.add(refresh, BorderLayout.EAST);
        header.add(tools, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(4, 8, 12, 8));
        scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        events = service.getCachedEvents();
        lastLoaded = service.getLastRefreshTime();
        status.setText(events.isEmpty() ? "No cached events" : "Cached · " + events.size() + " events");
        render();
        refresh(false);
        refreshGroupMembership();
    }

    public void refresh(boolean force)
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            SwingUtilities.invokeLater(() -> refresh(force));
            return;
        }
        refreshGroupMembership();
        if (loading || (!force && lastLoaded > 0 &&
                System.currentTimeMillis() - lastLoaded < REFRESH_MS))
        {
            return;
        }
        loading = true;
        error = null;
        status.setText("Loading competitions...");
        executor.submit(() -> {
            try
            {
                List<EventData> result = service.fetchEvents(force);
                SwingUtilities.invokeLater(() -> {
                    events = result;
                    onEventsChanged.accept(events);
                    lastLoaded = service.getLastRefreshTime();
                    loading = false;
                    status.setText("Loaded · " + events.size() + " events");
                    render();
                });
            }
            catch (Exception ex)
            {
                SwingUtilities.invokeLater(() -> {
                    loading = false;
                    error = ex.getMessage() == null ? "Unable to load events" : ex.getMessage();
                    status.setText(events.isEmpty() ? "Unable to refresh" : "Cached events · refresh failed");
                    render();
                });
            }
        });
    }

    private void render()
    {
        int previousScroll = scroll.getVerticalScrollBar().getValue();
        content.removeAll();
        if (error != null)
        {
            addText("Unable to refresh: " + error, SECONDARY, 11);
            gap(10);
        }

        List<EventData> completed = new ArrayList<>();
        List<EventData> current = new ArrayList<>();
        for (EventData event : events)
        {
            if ("Completed".equals(event.getStatus())) completed.add(event);
            else current.add(event);
        }
        completed.sort(Comparator.comparing(EventData::getEndsAt).reversed());
        current.sort(Comparator.comparing(EventData::getStartsAt));

        renderHighlights(completed);
        gap(16);
        heading("CURRENT & UPCOMING EVENTS");
        gap(7);
        if (current.isEmpty()) addText("No active or upcoming events.", SECONDARY, 11);
        for (EventData event : current) addEvent(event);

        gap(14);
        heading("PAST EVENTS");
        gap(7);
        if (completed.isEmpty()) addText("No completed competitions found.", SECONDARY, 11);
        for (EventData event : completed) addEvent(event);

        content.revalidate();
        content.repaint();
        SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(previousScroll));
    }

    private void renderHighlights(List<EventData> completed)
    {
        Map<String, Integer> wins = new HashMap<>();
        Map<String, String> displayNames = new HashMap<>();
        for (EventData event : completed)
        {
            for (Standing standing : event.getStandings())
            {
                if (standing.getRank() != 1) continue;
                String key = playerKey(standing.getName());
                if (currentGroupNames != null && !currentGroupNames.contains(key)) continue;
                wins.merge(key, 1, Integer::sum);
                displayNames.putIfAbsent(key, standing.getName());
            }
        }

        List<Map.Entry<String, Integer>> leaders = new ArrayList<>(wins.entrySet());
        leaders.sort((a, b) -> {
            int comparison = Integer.compare(b.getValue(), a.getValue());
            return comparison != 0 ? comparison :
                    displayNames.get(a.getKey()).compareToIgnoreCase(displayNames.get(b.getKey()));
        });

        JPanel winners = cardPanel(EXPANDED);
        String bestName = leaders.isEmpty() ? "No results yet" : displayNames.get(leaders.get(0).getKey());
        int bestWins = leaders.isEmpty() ? 0 : leaders.get(0).getValue();
        JLabel winnerHeading = addLabel(winners, "MOST EVENT WINS  " + (winnersExpanded ? "▲" : "▼"), GOLD, 11, Font.BOLD);
        JLabel winnerName = addLabel(winners, bestName, Color.WHITE, 14, Font.BOLD);
        JLabel winnerCount = addLabel(winners, bestWins == 0 ? "No recorded wins" : bestWins + " win(s)", SECONDARY, 11, Font.PLAIN);
        MouseAdapter toggle = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                winnersExpanded = !winnersExpanded;
                render();
            }
        };
        makeClickable(toggle, winners, winnerHeading, winnerName, winnerCount);

        if (winnersExpanded)
        {
            gapInside(winners, 6);
            addLabel(winners, "ALL-TIME TOP 5", GOLD, 10, Font.BOLD);
            if (leaders.isEmpty()) addLabel(winners, "No completed event winners yet.", SECONDARY, 11, Font.PLAIN);
            for (int i = 0; i < Math.min(5, leaders.size()); i++)
            {
                Map.Entry<String, Integer> entry = leaders.get(i);
                String medal = i == 0 ? "🥇" : i == 1 ? "🥈" : i == 2 ? "🥉" : "#" + (i + 1);
                addLabel(winners, medal + "  " + displayNames.get(entry.getKey()) +
                        " — " + entry.getValue() + " win(s)", SECONDARY, 11, Font.PLAIN);
            }
            addLabel(winners, "Tied 1st places count as wins.", SECONDARY, 10, Font.PLAIN);
        }
        content.add(winners);
        gap(7);

        String lastWinner = "No results yet";
        String lastTitle = "No completed events";
        for (EventData event : completed)
        {
            List<String> names = new ArrayList<>();
            for (Standing standing : event.getStandings())
            {
                if (standing.getRank() == 1) names.add(standing.getName());
            }
            if (!names.isEmpty())
            {
                lastWinner = String.join(", ", names);
                lastTitle = event.getTitle();
                break;
            }
        }
        JPanel last = cardPanel(EXPANDED);
        addLabel(last, "LAST EVENT WINNER", GOLD, 11, Font.BOLD);
        addLabel(last, "🏆 " + lastWinner, Color.WHITE, 13, Font.BOLD);
        addLabel(last, lastTitle, SECONDARY, 11, Font.PLAIN);
        content.add(last);
    }

    private void addEvent(EventData event)
    {
        boolean open = Objects.equals(expandedId, event.getId());
        JPanel panel = cardPanel(open ? EXPANDED : CARD);
        JLabel title = addLabel(panel, (open ? "▲ " : "▼ ") + event.getTitle(), Color.WHITE, 12, Font.BOLD);
        JLabel subtitle = addLabel(panel, event.getStatus() + " · " + displayEventType(event), GOLD, 10, Font.PLAIN);
        JLabel dates = addLabel(panel, DATE.format(event.getStartsAt()) + " – " + DATE.format(event.getEndsAt()), SECONDARY, 10, Font.PLAIN);

        MouseAdapter toggle = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                expandedId = open ? null : event.getId();
                render();
            }
        };
        makeClickable(toggle, panel, title, subtitle, dates);

        if (open)
        {
            gapInside(panel, 7);
            JSeparator line = new JSeparator();
            line.setForeground(Color.decode("#554343"));
            line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
            line.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(line);
            gapInside(panel, 8);
            addLabel(panel, "EVENT DETAILS", GOLD, 10, Font.BOLD);
            addLabel(panel, "Status: " + event.getStatus(), SECONDARY, 11, Font.PLAIN);
            addLabel(panel, "Type: " + displayEventType(event), SECONDARY, 11, Font.PLAIN);
            addLabel(panel, "Starts: " + DATE.format(event.getStartsAt()), SECONDARY, 11, Font.PLAIN);
            addLabel(panel, "Ends: " + DATE.format(event.getEndsAt()), SECONDARY, 11, Font.PLAIN);
            gapInside(panel, 8);
            addLabel(panel, "TOP 3 PLAYERS", GOLD, 11, Font.BOLD);
            int shown = 0;
            for (Standing standing : event.getStandings())
            {
                if (standing.getRank() > 3) break;
                String medal = standing.getRank() == 1 ? "🥇" : standing.getRank() == 2 ? "🥈" : "🥉";
                Color color = standing.getRank() == 1 ? GOLD : standing.getRank() == 2 ? SILVER : BRONZE;
                addLabel(panel, medal + "  " + standing.getName(), color, 11, Font.BOLD);
                addLabel(panel, "     Score: " + score(standing.getGained()), SECONDARY, 10, Font.PLAIN);
                shown++;
            }
            if (shown == 0) addLabel(panel, "No standings available.", SECONDARY, 11, Font.PLAIN);
        }
        content.add(panel);
        gap(7);
    }

    private String displayEventType(EventData event)
    {
        String title = event.getTitle();
        String normalized = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (normalized.matches(".*\\bsotw\\b.*"))
        {
            return "Skilling";
        }
        if (normalized.matches(".*\\bbotw\\b.*"))
        {
            return "Bossing";
        }
        if (normalized.matches(".*\\bpvm\\b.*"))
        {
            return "PvM";
        }
        return event.getType();
    }

    private void makeClickable(MouseAdapter listener, Component... components)
    {
        for (Component component : components)
        {
            component.addMouseListener(listener);
            component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
    }

    private String score(double value)
    {
        return Math.abs(value - Math.rint(value)) < 0.000001
                ? String.format(Locale.US, "%,.0f", value)
                : String.format(Locale.US, "%,.2f", value);
    }

    private JPanel cardPanel(Color background)
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(background);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 9, 9, 9));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private JLabel addLabel(JPanel panel, String value, Color color, int size, int style)
    {
        JLabel label = new JLabel("<html><div style='width:170px'>" + escape(value) + "</div></html>");
        label.setFont(new Font("Arial", style, size));
        label.setForeground(color);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(label);
        panel.add(Box.createVerticalStrut(3));
        return label;
    }

    private String escape(String value)
    {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private void heading(String value) { addText(value, GOLD, 12); }

    private void addText(String value, Color color, int size)
    {
        JLabel label = new JLabel(value);
        label.setFont(new Font("Arial", Font.BOLD, size));
        label.setForeground(color);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(label);
    }

    private void gap(int height) { content.add(Box.createVerticalStrut(height)); }
    private void gapInside(JPanel panel, int height) { panel.add(Box.createVerticalStrut(height)); }
}
