package com.unityclan.ui;

import com.unityclan.wom.WiseOldManRequestGate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.unityclan.members.ClanMemberInfo;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Date;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import java.text.SimpleDateFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** All-time leaderboard for every member tracked in Wise Old Man group 10453. */
public class UnityClanTopPlayersPanel extends JPanel
{
    private static final String BULK_URL = "https://api.wiseoldman.net/v2/groups/10453/bulk-hiscores";
    private static final Color BACKGROUND = Color.decode("#302526");
    private static final Color INNER = Color.decode("#403233");
    private static final Color GOLD = Color.decode("#D6A34E");
    private static final Color MUTED = Color.decode("#B8A7A1");
    private static final Color[] MEDALS = {
            GOLD, Color.decode("#C9CCD2"), Color.decode("#BD8561")
    };

    private static final File CACHE_FILE = new File(
            new File(System.getProperty("user.home"), ".runelite/unity-clan"), "top-players-cache.json");
    private static final long CACHE_MAX_AGE_MS = TimeUnit.DAYS.toMillis(7);
    private static final long REFRESH_MS = TimeUnit.HOURS.toMillis(24);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "unity-clan-bulk-hiscores");
        t.setDaemon(true);
        return t;
    });
    private final JPanel rows = new JPanel();
    private final JLabel status = new JLabel("Waiting for clan login...");
    private final JComboBox<String> metric = new JComboBox<>(
            new String[]{"Total XP", "Total Level", "Boss Kills"});
    private List<PlayerScore> players = new ArrayList<>();
    private boolean requested;
    private int generation;
    private Consumer<ClanStatistics> statisticsListener;

    public UnityClanTopPlayersPanel()
    {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(12, 10, 12, 10));
        setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel title = new JLabel("CLAN TOP PLAYERS - ALL MEMBERS");
        title.setForeground(GOLD);
        title.setFont(new Font("Arial", Font.BOLD, 11));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(title);
        add(Box.createVerticalStrut(8));

        metric.setMaximumSize(new Dimension(Integer.MAX_VALUE, 27));
        metric.setAlignmentX(Component.LEFT_ALIGNMENT);
        metric.addActionListener(e -> render());
        add(metric);
        add(Box.createVerticalStrut(8));

        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setBackground(BACKGROUND);
        rows.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(rows);
        add(Box.createVerticalStrut(6));

        status.setFont(new Font("Arial", Font.PLAIN, 10));
        status.setForeground(MUTED);
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(status);
        loadCache();
        render();
    }

    /** Subscribe to the same cache and API snapshot used by the leaderboard. No extra requests. */
    public void setStatisticsListener(Consumer<ClanStatistics> listener)
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            SwingUtilities.invokeLater(() -> setStatisticsListener(listener));
            return;
        }
        statisticsListener = listener;
        notifyStatistics();
    }

    private void notifyStatistics()
    {
        if (statisticsListener != null)
            statisticsListener.accept(ClanStatistics.from(players));
    }

    /** Called by the existing dashboard. The online roster only signals login/logout. */
    public void updateMembers(List<ClanMemberInfo> members)
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            List<ClanMemberInfo> copy = members == null ? new ArrayList<>() : new ArrayList<>(members);
            SwingUtilities.invokeLater(() -> updateMembers(copy));
            return;
        }
        if (members == null || members.isEmpty())
        {
            // An empty roster can also be a temporary clan-channel state.
            // Keep the current leaderboard; it will be replaced on plugin restart.
            return;
        }
        if (requested) return;
        if (CACHE_FILE.isFile() && System.currentTimeMillis() - CACHE_FILE.lastModified() < REFRESH_MS) {
            requested = true;
            status.setText("Saved all-time rankings (daily refresh)");
            return;
        }
        requested = true;
        final int request = ++generation;
        status.setText(players.isEmpty() ? "Loading all Wise Old Man clan members..." : "Showing saved rankings; checking WOM...");
        executor.execute(() -> {
            try
            {
                List<PlayerScore> loaded = fetchBulkHiscores();
                SwingUtilities.invokeLater(() -> {
                    if (request != generation) return;
                    players = loaded;
                    saveCache(loaded);
                    status.setText("All-time stats: " + loaded.size() + " tracked members | refresh daily");
                    render();
                    notifyStatistics();
                });
            }
            catch (Exception ex)
            {
                SwingUtilities.invokeLater(() -> {
                    if (request != generation) return;
                    status.setText(players.isEmpty()
                            ? "WOM unavailable: " + ex.getMessage()
                            : "Saved rankings shown; WOM: " + ex.getMessage());
                });
            }
        });
    }

    private static final class RateLimitedException extends Exception
    {
        private final long retryAfterMs;
        private RateLimitedException(long retryAfterMs) { this.retryAfterMs = retryAfterMs; }
    }

    private static long retryAfterMillis(String value)
    {
        if (value == null || value.trim().isEmpty()) return 0;
        try { return Math.max(0, Long.parseLong(value.trim()) * 1000L); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private void loadCache()
    {
        if (!CACHE_FILE.isFile() || System.currentTimeMillis() - CACHE_FILE.lastModified() > CACHE_MAX_AGE_MS)
            return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(CACHE_FILE), StandardCharsets.UTF_8)))
        {
            JsonElement parsed = new JsonParser().parse(reader);
            if (!parsed.isJsonArray()) return;
            List<PlayerScore> cached = new ArrayList<>();
            for (JsonElement element : parsed.getAsJsonArray())
            {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String name = string(item, "name", "");
                if (!name.isEmpty()) cached.add(new PlayerScore(name,
                        number(item, "xp"), number(item, "level"), number(item, "kills")));
            }
            players = cached;
            notifyStatistics();
            if (!cached.isEmpty()) status.setText("Saved rankings (up to 7 days old); waiting to refresh");
        }
        catch (Exception ignored) { /* Corrupt cache is not fatal. */ }
    }

    private void saveCache(List<PlayerScore> loaded)
    {
        try
        {
            File folder = CACHE_FILE.getParentFile();
            if (!folder.isDirectory() && !folder.mkdirs()) return;
            JsonArray array = new JsonArray();
            for (PlayerScore p : loaded)
            {
                JsonObject object = new JsonObject();
                object.addProperty("name", p.name);
                object.addProperty("xp", p.xp);
                object.addProperty("level", p.level);
                object.addProperty("kills", p.kills);
                array.add(object);
            }
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(CACHE_FILE), StandardCharsets.UTF_8))
            {
                writer.write(array.toString());
            }
        }
        catch (Exception ignored) { /* Cache persistence is best-effort. */ }
    }

    private List<PlayerScore> fetchBulkHiscores() throws Exception
    {
        return WiseOldManRequestGate.execute(this::fetchBulkHiscoresDirect);
    }

    private List<PlayerScore> fetchBulkHiscoresDirect() throws Exception
    {
        HttpURLConnection connection = (HttpURLConnection) new URL(BULK_URL).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "UnityClanRuneLitePlugin/1.0");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        try
        {
            int responseCode = connection.getResponseCode();
            if (responseCode == 429)
            {
                WiseOldManRequestGate.onRateLimited(connection.getHeaderField("Retry-After"));
                throw new RateLimitedException(retryAfterMillis(connection.getHeaderField("Retry-After")));
            }
            if (responseCode != 200)
                throw new IllegalStateException("HTTP " + responseCode);
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            JsonElement root = new JsonParser().parse(body.toString());
            if (!root.isJsonArray()) throw new IllegalStateException("Unexpected WOM response");
            List<PlayerScore> result = new ArrayList<>();
            JsonArray entries = root.getAsJsonArray();
            for (JsonElement entry : entries)
            {
                if (!entry.isJsonObject()) continue;
                JsonObject object = entry.getAsJsonObject();
                JsonObject player = object.getAsJsonObject("player");
                JsonObject snapshot = object.getAsJsonObject("data");
                if (player == null || snapshot == null) continue;
                JsonObject data = snapshot.getAsJsonObject("data");
                if (data == null) continue;
                JsonObject skills = data.getAsJsonObject("skills");
                JsonObject overall = skills == null ? null : skills.getAsJsonObject("overall");
                if (overall == null) continue;
                String name = string(player, "displayName", string(player, "username", "Unknown"));
                long xp = number(overall, "experience");
                long level = number(overall, "level");
                long kills = 0;
                JsonObject bosses = data.getAsJsonObject("bosses");
                if (bosses != null)
                {
                    for (java.util.Map.Entry<String, JsonElement> boss : bosses.entrySet())
                    {
                        if (boss.getValue().isJsonObject())
                        {
                            long count = number(boss.getValue().getAsJsonObject(), "kills");
                            if (count > 0) kills += count;
                        }
                    }
                }
                result.add(new PlayerScore(name, xp, level, kills));
            }
            return result;
        }
        finally
        {
            connection.disconnect();
        }
    }

    private static long number(JsonObject object, String key)
    {
        if (!object.has(key) || object.get(key).isJsonNull()) return 0;
        try { return object.get(key).getAsLong(); }
        catch (RuntimeException ignored) { return 0; }
    }

    private static String string(JsonObject object, String key, String fallback)
    {
        if (!object.has(key) || object.get(key).isJsonNull()) return fallback;
        return object.get(key).getAsString();
    }

    private long value(PlayerScore player)
    {
        int selected = metric.getSelectedIndex();
        if (selected == 1) return player.level;
        if (selected == 2) return player.kills;
        return player.xp;
    }

    private void render()
    {
        rows.removeAll();
        List<PlayerScore> sorted = new ArrayList<>(players);
        sorted.sort(Comparator.comparingLong(this::value).reversed()
                .thenComparing(p -> p.name, String.CASE_INSENSITIVE_ORDER));
        if (sorted.isEmpty())
        {
            JLabel empty = new JLabel("No player statistics loaded yet");
            empty.setForeground(MUTED);
            rows.add(empty);
        }
        for (int i = 0; i < Math.min(5, sorted.size()); i++)
        {
            PlayerScore player = sorted.get(i);
            JPanel row = new JPanel(new BorderLayout(6, 0));
            row.setBackground(INNER);
            row.setBorder(BorderFactory.createEmptyBorder(7, 6, 7, 6));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel rank = new JLabel(String.valueOf(i + 1), SwingConstants.CENTER);
            rank.setOpaque(true);
            rank.setBackground(i < 3 ? MEDALS[i] : MUTED);
            rank.setForeground(Color.BLACK);
            rank.setPreferredSize(new Dimension(24, 24));
            rank.setFont(new Font("Arial", Font.BOLD, 11));

            JLabel name = new JLabel(player.name);
            name.setForeground(Color.WHITE);
            name.setFont(new Font("Arial", Font.PLAIN, 11));

            JLabel score = new JLabel(String.format(Locale.US, "%,d", value(player)));
            score.setForeground(GOLD);
            score.setFont(new Font("Arial", Font.BOLD, 11));

            row.add(rank, BorderLayout.WEST);
            row.add(name, BorderLayout.CENTER);
            row.add(score, BorderLayout.EAST);
            rows.add(row);
            rows.add(Box.createVerticalStrut(4));
        }
        rows.revalidate();
        rows.repaint();
    }

    /** Immutable aggregate of the available WOM bulk-hiscores snapshot. */
    public static final class ClanStatistics
    {
        public final int trackedMembers;
        public final long combinedXp;
        public final long combinedBossKills;
        public final double averageTotalLevel;
        public final String highestXpPlayer;
        public final long highestXp;

        private ClanStatistics(int members, long xp, long kills, double avg, String leader, long leaderXp)
        {
            trackedMembers = members;
            combinedXp = xp;
            combinedBossKills = kills;
            averageTotalLevel = avg;
            highestXpPlayer = leader;
            highestXp = leaderXp;
        }

        private static ClanStatistics from(List<PlayerScore> players)
        {
            long xp = 0, kills = 0, levels = 0, highest = -1;
            String leader = "--";
            for (PlayerScore p : players)
            {
                xp += p.xp;
                kills += p.kills;
                levels += p.level;
                if (p.xp > highest)
                {
                    highest = p.xp;
                    leader = p.name;
                }
            }
            return new ClanStatistics(players.size(), xp, kills,
                    players.isEmpty() ? 0 : (double) levels / players.size(),
                    leader, Math.max(0, highest));
        }
    }

    private static final class PlayerScore
    {
        private final String name;
        private final long xp;
        private final long level;
        private final long kills;
        private PlayerScore(String name, long xp, long level, long kills)
        {
            this.name = name;
            this.xp = xp;
            this.level = level;
            this.kills = kills;
        }
    }
}