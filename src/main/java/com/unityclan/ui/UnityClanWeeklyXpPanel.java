
        package com.unityclan.ui;

import com.unityclan.wom.WiseOldManRequestGate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.unityclan.members.ClanMemberInfo;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import com.unityclan.net.ClanHttpConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Monday 00:00 UTC through the present, for all WOM group 10453 members. */
public final class UnityClanWeeklyXpPanel extends JPanel {
    private static final Color CARD = Color.decode("#302526");
    private static final Color INNER = Color.decode("#403233");
    private static final Color GOLD = Color.decode("#D6A34E");
    private static final Color MUTED = Color.decode("#B8A7A1");
    private static final Color[] MEDALS = {GOLD, Color.decode("#C9CCD2"), Color.decode("#BD8561")};
    private static final String API = "https://api.wiseoldman.net/v2/groups/10453/gained";
    private static final File CACHE = new File(new File(System.getProperty("user.home"), ".runelite/unity-clan"), "weekly-xp-cache.json");
    private static final long REFRESH_MS = TimeUnit.HOURS.toMillis(24);
    private static final long MANUAL_REFRESH_MS = TimeUnit.MINUTES.toMillis(5);
    private static final File PREVIOUS_CACHE = new File(CACHE.getParentFile(), "previous-week-winner.json");
    private static final long PREVIOUS_RETRY_MS = TimeUnit.HOURS.toMillis(1);
    private static final long ERROR_RETRY_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long MAX_RETRY_MS = TimeUnit.MINUTES.toMillis(60);
    private int consecutiveFailures;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "unity-clan-weekly-xp"); t.setDaemon(true); return t;
    });
    private final JPanel rows = new JPanel();
    private final JLabel weekLabel = new JLabel("Week begins Monday 00:00 UTC");
    private final JLabel personalRank = new JLabel("--", SwingConstants.CENTER);
    private final JLabel personalXp = new JLabel("--", SwingConstants.CENTER);
    private final JLabel status = new JLabel("Waiting for clan login...");
    private final JButton refreshButton = new JButton("Refresh Top 3");
    private final JLabel previousWinner = new JLabel("Loading previous week...");
    private final JLabel previousWeekLabel = new JLabel("PREVIOUS WEEK WINNER");
    private String previousWeek = "";
    private String previousName = "";
    private long previousXp;
    private boolean previousLoading;
    private long previousRetryAt;
    private long lastManualRefresh;
    private List<Entry> entries = new ArrayList<>();
    private String username = "";
    private String cachedWeek = "";
    private boolean connected;
    private boolean loading;
    private long nextFetchAt;

    public UnityClanWeeklyXpPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(CARD);
        setBorder(BorderFactory.createEmptyBorder(12, 10, 12, 10));
        setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel heading = new JLabel("MEMBER OF THE WEEK");
        heading.setForeground(GOLD); heading.setFont(new Font("Arial", Font.BOLD, 11));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(heading);
        add(Box.createVerticalStrut(5));
        weekLabel.setForeground(MUTED); weekLabel.setFont(new Font("Arial", Font.PLAIN, 10));
        weekLabel.setAlignmentX(Component.LEFT_ALIGNMENT); add(weekLabel);
        add(Box.createVerticalStrut(5));
        refreshButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        refreshButton.addActionListener(e -> manualRefresh());
        add(refreshButton);
        add(Box.createVerticalStrut(10));
        JLabel top = new JLabel("TOP 3 - WEEKLY XP GAINED");
        top.setForeground(GOLD); top.setFont(new Font("Arial", Font.BOLD, 10));
        top.setAlignmentX(Component.LEFT_ALIGNMENT); add(top);
        add(Box.createVerticalStrut(7));
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setBackground(CARD); rows.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(rows);
        add(Box.createVerticalStrut(10));
        previousWeekLabel.setForeground(GOLD); previousWeekLabel.setFont(new Font("Arial", Font.BOLD, 10));
        previousWeekLabel.setAlignmentX(Component.LEFT_ALIGNMENT); add(previousWeekLabel);
        add(Box.createVerticalStrut(5));
        previousWinner.setForeground(Color.WHITE); previousWinner.setFont(new Font("Arial", Font.PLAIN, 11));
        previousWinner.setAlignmentX(Component.LEFT_ALIGNMENT); add(previousWinner);
        add(Box.createVerticalStrut(10));
        JLabel yourTitle = new JLabel("YOUR WEEKLY PERFORMANCE");
        yourTitle.setForeground(GOLD); yourTitle.setFont(new Font("Arial", Font.BOLD, 10));
        yourTitle.setAlignmentX(Component.LEFT_ALIGNMENT); add(yourTitle);
        add(Box.createVerticalStrut(7));
        JPanel personal = new JPanel(new GridLayout(1, 2, 7, 0));
        personal.setBackground(CARD); personal.setMaximumSize(new Dimension(Integer.MAX_VALUE, 75));
        personal.setAlignmentX(Component.LEFT_ALIGNMENT);
        personal.add(statBox("YOUR RANK", personalRank));
        personal.add(statBox("XP GAINED", personalXp));
        add(personal);
        add(Box.createVerticalStrut(8));
        status.setForeground(MUTED); status.setFont(new Font("Arial", Font.PLAIN, 10));
        status.setAlignmentX(Component.LEFT_ALIGNMENT); add(status);
        loadCache();
        loadPreviousCache();
        render();
        javax.swing.Timer timer = new javax.swing.Timer(60000, e -> tick());
        timer.start();
    }

    private JPanel statBox(String title, JLabel value) {
        JPanel box = new JPanel(); box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBackground(INNER); box.setBorder(BorderFactory.createEmptyBorder(8, 2, 8, 2));
        JLabel caption = new JLabel(title, SwingConstants.CENTER);
        caption.setForeground(MUTED); caption.setFont(new Font("Arial", Font.BOLD, 10));
        caption.setAlignmentX(Component.CENTER_ALIGNMENT);
        value.setForeground(GOLD); value.setFont(new Font("Arial", Font.BOLD, 18));
        value.setAlignmentX(Component.CENTER_ALIGNMENT);
        box.add(caption); box.add(Box.createVerticalStrut(5)); box.add(value);
        return box;
    }

    public void setUsername(String value) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setUsername(value)); return;
        }
        String normalized = normalize(value);
        if (!normalized.equals(username)) { username = normalized; render(); }
    }

    public void updateMembers(List<ClanMemberInfo> members) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> updateMembers(members)); return;
        }
        if (members != null && !members.isEmpty()) connected = true;
        tick();
    }

    private void manualRefresh() {
        if (!connected || loading) return;
        long now = System.currentTimeMillis();
        if (now - lastManualRefresh < MANUAL_REFRESH_MS) {
            status.setText("Manual refresh available in " + ((MANUAL_REFRESH_MS - (now - lastManualRefresh) + 59999) / 60000) + "m");
            return;
        }
        if (now < nextFetchAt && consecutiveFailures > 0) {
            status.setText("Wise Old Man retry cooldown active");
            return;
        }
        lastManualRefresh = now;
        nextFetchAt = 0;
        tick();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('_', ' ').trim().toLowerCase(Locale.ROOT);
    }

    private static String weekKey() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return today.minusDays(today.getDayOfWeek().getValue() - 1).toString();
    }

    private void tick() {
        String week = weekKey();
        if (!week.equals(cachedWeek)) {
            entries = new ArrayList<>(); cachedWeek = week; nextFetchAt = 0; consecutiveFailures = 0;
            status.setText("Loading new week's XP gains..."); render();
        }
        weekLabel.setText("Since " + week + " 00:00 UTC");
        refreshButton.setEnabled(connected && !loading);
        refreshPreviousWeek();
        if (!connected || loading || System.currentTimeMillis() < nextFetchAt) return;
        loading = true;
        refreshButton.setEnabled(false);
        nextFetchAt = System.currentTimeMillis() + ERROR_RETRY_MS;
        final String requestedWeek = week;
        status.setText(entries.isEmpty() ? "Loading weekly clan XP..." : "Refreshing weekly XP...");
        executor.execute(() -> {
            try {
                List<Entry> result = fetch(requestedWeek);
                SwingUtilities.invokeLater(() -> {
                    loading = false;
                    refreshButton.setEnabled(true);
                    if (!requestedWeek.equals(weekKey())) { tick(); return; }
                    entries = result; cachedWeek = requestedWeek;
                    consecutiveFailures = 0;
                    nextFetchAt = System.currentTimeMillis() + REFRESH_MS;
                    saveCache(requestedWeek, result);
                    status.setText("Weekly XP: " + result.size() + " tracked players | refresh daily");
                    status.setToolTipText(null);
                    render();
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> {
                    loading = false;
                    refreshButton.setEnabled(true);
                    if (!requestedWeek.equals(weekKey())) { tick(); return; }
                    consecutiveFailures++;
                    long backoff = ERROR_RETRY_MS * (1L << Math.min(consecutiveFailures - 1, 4));
                    long retryDelay = Math.min(MAX_RETRY_MS, Math.max(backoff,
                            error instanceof RateLimitException ? ((RateLimitException) error).retryAfterMs : 0L));
                    nextFetchAt = System.currentTimeMillis() + retryDelay;
                    long minutes = Math.max(1L, (retryDelay + 59999L) / 60000L);
                    String message = (entries.isEmpty() ? "WOM unavailable" : "Saved weekly XP shown")
                            + "; retry in " + minutes + "m (" + error.getMessage() + ")";
                    status.setText(message);
                    status.setToolTipText(message);
                });
            }
        });
    }

    private static String previousWeekKey() {
        return LocalDate.parse(weekKey()).minusWeeks(1).toString();
    }

    private void refreshPreviousWeek() {
        if (!connected || previousLoading) return;
        String target = previousWeekKey();
        if (target.equals(previousWeek)) return;
        if (System.currentTimeMillis() < previousRetryAt) return;
        previousLoading = true;
        previousWinner.setText("Loading previous week's winner...");
        executor.execute(() -> {
            try {
                List<Entry> result = fetchRange(target, LocalDate.parse(target).plusWeeks(1).toString());
                SwingUtilities.invokeLater(() -> {
                    previousLoading = false;
                    previousWeek = target;
                    previousName = result.isEmpty() ? "" : result.get(0).name;
                    previousXp = result.isEmpty() ? 0 : result.get(0).xp;
                    savePreviousCache();
                    renderPreviousWinner();
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> {
                    previousLoading = false;
                    previousRetryAt = System.currentTimeMillis() + PREVIOUS_RETRY_MS;
                    previousWinner.setText("Previous winner unavailable; retry later");
                    previousWinner.setToolTipText(error.getMessage());
                });
            }
        });
    }

    private void renderPreviousWinner() {
        previousWeekLabel.setText("PREVIOUS WEEK WINNER (" + previousWeek + ")");
        previousWinner.setText(previousName.isEmpty() ? "No previous-week XP data" :
                previousName + "  -  " + String.format(Locale.US, "%,d XP", previousXp));
    }

    private void loadPreviousCache() {
        if (!PREVIOUS_CACHE.isFile()) return;
        try (Reader reader = new InputStreamReader(new FileInputStream(PREVIOUS_CACHE), StandardCharsets.UTF_8)) {
            JsonObject object = new JsonParser().parse(reader).getAsJsonObject();
            previousWeek = string(object, "week", "");
            previousName = string(object, "name", "");
            previousXp = number(object, "xp");
            if (previousWeek.equals(previousWeekKey())) renderPreviousWinner();
            else { previousWeek = ""; previousName = ""; }
        } catch (Exception ignored) { /* Optional saved winner. */ }
    }

    private void savePreviousCache() {
        try {
            if (!PREVIOUS_CACHE.getParentFile().isDirectory() && !PREVIOUS_CACHE.getParentFile().mkdirs()) return;
            JsonObject object = new JsonObject();
            object.addProperty("week", previousWeek);
            object.addProperty("name", previousName);
            object.addProperty("xp", previousXp);
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(PREVIOUS_CACHE), StandardCharsets.UTF_8)) {
                writer.write(object.toString());
            }
        } catch (Exception ignored) { /* Optional saved winner. */ }
    }

    private static final class RateLimitException extends IOException {
        final long retryAfterMs;
        RateLimitException(long retryAfterMs) {
            super("HTTP 429");
            this.retryAfterMs = retryAfterMs;
        }
    }

    private static long retryAfterMillis(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        try { return Math.max(0L, Long.parseLong(value.trim()) * 1000L); }
        catch (NumberFormatException ignored) {
            try {
                Instant until = java.time.ZonedDateTime.parse(value.trim(),
                        DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Math.max(0L, Duration.between(Instant.now(), until).toMillis());
            } catch (RuntimeException invalidDate) { return 0L; }
        }
    }

    private static List<Entry> fetch(String week) throws Exception {
        return WiseOldManRequestGate.execute(() -> fetchDirect(week, Instant.now().toString()));
    }

    private static List<Entry> fetchRange(String startWeek, String endWeek) throws Exception {
        return WiseOldManRequestGate.execute(() -> fetchDirect(startWeek, endWeek + "T00:00:00.000Z"));
    }

    private static List<Entry> fetchDirect(String week, String end) throws Exception {
        String start = week + "T00:00:00.000Z";
        String url = API + "?metric=overall&startDate=" + URLEncoder.encode(start, "UTF-8")
                + "&endDate=" + URLEncoder.encode(end, "UTF-8");
        ClanHttpConnection conn = ClanHttpConnection.open(url);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "UnityClanRuneLitePlugin/1.0");
        conn.setConnectTimeout(10000); conn.setReadTimeout(30000);
        try {
            int code = conn.getResponseCode();
            if (code == 429) {
                WiseOldManRequestGate.onRateLimited(conn.getHeaderField("Retry-After"));
                throw new RateLimitException(retryAfterMillis(conn.getHeaderField("Retry-After")));
            }
            if (code != 200) throw new IOException("HTTP " + code);
            JsonElement root;
            try (Reader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                root = new JsonParser().parse(reader);
            }
            if (!root.isJsonArray()) throw new IOException("Unexpected WOM gains response");
            List<Entry> result = new ArrayList<>();
            for (JsonElement el : root.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject object = el.getAsJsonObject();
                JsonObject player = object.has("player") && object.get("player").isJsonObject()
                        ? object.getAsJsonObject("player") : null;
                JsonObject data = object.has("data") && object.get("data").isJsonObject()
                        ? object.getAsJsonObject("data") : null;
                if (player == null || data == null) continue;
                String name = string(player, "displayName", string(player, "username", ""));
                if (name.isEmpty()) continue;
                long gained = number(data, "gained");
                result.add(new Entry(name, Math.max(0, gained)));
            }
            result.sort(Comparator.comparingLong((Entry e) -> e.xp).reversed()
                    .thenComparing(e -> e.name, String.CASE_INSENSITIVE_ORDER));
            return result;
        } finally { conn.disconnect(); }
    }

    private static String string(JsonObject obj, String key, String fallback) {
        try { return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    private static long number(JsonObject obj, String key) {
        try { return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsLong() : 0; }
        catch (RuntimeException ignored) { return 0; }
    }

    private void loadCache() {
        if (!CACHE.isFile()) return;
        try (Reader reader = new InputStreamReader(new FileInputStream(CACHE), StandardCharsets.UTF_8)) {
            JsonElement root = new JsonParser().parse(reader);
            if (!root.isJsonObject()) return;
            JsonObject obj = root.getAsJsonObject();
            String week = string(obj, "week", "");
            if (!weekKey().equals(week)) return;
            JsonArray data = obj.getAsJsonArray("players");
            if (data == null) return;
            List<Entry> saved = new ArrayList<>();
            for (JsonElement el : data) {
                if (!el.isJsonObject()) continue;
                JsonObject p = el.getAsJsonObject();
                String name = string(p, "name", "");
                if (!name.isEmpty()) saved.add(new Entry(name, number(p, "xp")));
            }
            saved.sort(Comparator.comparingLong((Entry e) -> e.xp).reversed()
                    .thenComparing(e -> e.name, String.CASE_INSENSITIVE_ORDER));
            entries = saved; cachedWeek = week;
            nextFetchAt = Math.max(System.currentTimeMillis(), CACHE.lastModified() + REFRESH_MS);
            status.setText("Saved weekly XP shown; waiting to refresh");
        } catch (Exception ignored) { /* Cache is best-effort. */ }
    }

    private void saveCache(String week, List<Entry> data) {
        try {
            if (!CACHE.getParentFile().isDirectory() && !CACHE.getParentFile().mkdirs()) return;
            JsonObject root = new JsonObject(); root.addProperty("week", week);
            JsonArray array = new JsonArray();
            for (Entry e : data) {
                JsonObject player = new JsonObject(); player.addProperty("name", e.name);
                player.addProperty("xp", e.xp); array.add(player);
            }
            root.add("players", array);
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(CACHE), StandardCharsets.UTF_8)) {
                writer.write(root.toString());
            }
        } catch (Exception ignored) { /* Cache is best-effort. */ }
    }

    private void render() {
        rows.removeAll();
        for (int i = 0; i < Math.min(3, entries.size()); i++) {
            Entry p = entries.get(i);
            JPanel line = new JPanel(new BorderLayout(6, 0));
            line.setBackground(INNER); line.setBorder(BorderFactory.createEmptyBorder(7, 6, 7, 6));
            line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            line.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel rank = new JLabel(String.valueOf(i + 1), SwingConstants.CENTER);
            rank.setOpaque(true); rank.setBackground(MEDALS[i]); rank.setForeground(Color.BLACK);
            rank.setPreferredSize(new Dimension(24, 24)); rank.setFont(new Font("Arial", Font.BOLD, 11));
            JLabel name = new JLabel(p.name);
            name.setForeground(Color.WHITE); name.setFont(new Font("Arial", Font.PLAIN, 11));
            JLabel xp = new JLabel(String.format(Locale.US, "%,d", p.xp));
            xp.setForeground(GOLD); xp.setFont(new Font("Arial", Font.BOLD, 11));
            line.add(rank, BorderLayout.WEST); line.add(name, BorderLayout.CENTER); line.add(xp, BorderLayout.EAST);
            rows.add(line); rows.add(Box.createVerticalStrut(4));
        }
        if (entries.isEmpty()) {
            JLabel empty = new JLabel("No weekly XP data loaded yet");
            empty.setForeground(MUTED); rows.add(empty);
        }
        personalRank.setText("--"); personalXp.setText("--");
        if (!username.isEmpty()) {
            int rank = 0; long previous = Long.MIN_VALUE;
            for (int i = 0; i < entries.size(); i++) {
                Entry p = entries.get(i);
                if (p.xp != previous) rank = i + 1;
                previous = p.xp;
                if (normalize(p.name).equals(username)) {
                    personalRank.setText("#" + rank);
                    personalXp.setText(String.format(Locale.US, "%,d", p.xp));
                    break;
                }
            }
        }
        rows.revalidate(); rows.repaint();
    }

    private static final class Entry {
        final String name; final long xp;
        Entry(String name, long xp) { this.name = name; this.xp = xp; }
    }
}


