
package com.unityclan.wom;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class WiseOldManPlayerService
{
    private static final WiseOldManPlayerService SHARED = new WiseOldManPlayerService();
    public static WiseOldManPlayerService shared() { return SHARED; }

    private static final String API_URL =
            "https://api.wiseoldman.net/v2/players/";

    private static final long CACHE_DURATION =
            24 * 60 * 60 * 1000L;

    private final Map<String, CacheEntry> cache =
            new HashMap<>();

    /**
     * Blocking network method.
     *
     * Call this only from a background thread,
     * never from RuneLite's client thread or
     * Swing's event dispatch thread.
     */
    public synchronized PlayerStats fetchPlayer(
            String username) throws Exception
    {
        if (username == null
                || username.trim().isEmpty())
        {
            throw new IllegalArgumentException(
                    "Player name cannot be empty"
            );
        }

        String cacheKey = username
                .trim()
                .replace('_', ' ')
                .toLowerCase(Locale.ROOT);

        long now = System.currentTimeMillis();

        CacheEntry cached = cache.get(cacheKey);

        if (cached != null
                && now - cached.timestamp < CACHE_DURATION)
        {
            return cached.stats;
        }

        PlayerStats stats = requestPlayer(username);

        cache.put(
                cacheKey,
                new CacheEntry(stats, now)
        );

        return stats;
    }

    private PlayerStats requestPlayer(
            String username) throws Exception
    {
        String encodedName = URLEncoder.encode(
                username.trim(),
                StandardCharsets.UTF_8.name()
        ).replace("+", "%20");

        URL url = new URL(
                API_URL + encodedName
        );

        return WiseOldManRequestGate.execute(() -> requestPlayerDirect(url, username));
    }

    private PlayerStats requestPlayerDirect(URL url, String username) throws Exception
    {
        HttpURLConnection connection =
                (HttpURLConnection) url.openConnection();

        connection.setRequestMethod("GET");
        connection.setRequestProperty(
                "Accept", "application/json"
        );
        connection.setRequestProperty(
                "User-Agent",
                "UnityClanRuneLitePlugin/1.0"
        );
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);

        try
        {
            int status = connection.getResponseCode();

            if (status == 404)
            {
                throw new IllegalStateException(
                        "Player not found on Wise Old Man"
                );
            }

            if (status != 200)
            {
                throw new IllegalStateException(
                        "Wise Old Man returned HTTP "
                                + status
                );
            }

            StringBuilder response =
                    new StringBuilder();

            try (BufferedReader reader =
                         new BufferedReader(
                                 new InputStreamReader(
                                         connection.getInputStream(),
                                         StandardCharsets.UTF_8
                                 )
                         ))
            {
                String line;

                while ((line = reader.readLine()) != null)
                {
                    response.append(line);
                }
            }

            JsonObject player = new JsonParser()
                    .parse(response.toString())
                    .getAsJsonObject();

            String displayName = getString(
                    player,
                    "displayName",
                    username
            );

            double ehp = getDouble(player, "ehp");
            double ehb = getDouble(player, "ehb");

            int totalLevel = 0;
            long totalXp = 0;
            int overallRank = 0;

            List<BossStats> bosses =
                    new ArrayList<>();

            JsonObject snapshot = getObject(
                    player,
                    "latestSnapshot"
            );

            if (snapshot != null)
            {
                JsonObject data = getObject(
                        snapshot,
                        "data"
                );

                if (data != null)
                {
                    JsonObject skills = getObject(
                            data,
                            "skills"
                    );

                    if (skills != null)
                    {
                        JsonObject overall = getObject(
                                skills,
                                "overall"
                        );

                        if (overall != null)
                        {
                            totalLevel = getInt(
                                    overall,
                                    "level"
                            );

                            totalXp = getLong(
                                    overall,
                                    "experience"
                            );

                            overallRank = getInt(
                                    overall,
                                    "rank"
                            );
                        }
                    }

                    JsonObject bossData = getObject(
                            data,
                            "bosses"
                    );

                    if (bossData != null)
                    {
                        for (Map.Entry<String, JsonElement>
                                entry : bossData.entrySet())
                        {
                            if (!entry.getValue()
                                    .isJsonObject())
                            {
                                continue;
                            }

                            JsonObject boss =
                                    entry.getValue()
                                            .getAsJsonObject();

                            long kills = getLong(
                                    boss,
                                    "kills"
                            );

                            if (kills > 0)
                            {
                                bosses.add(
                                        new BossStats(
                                                formatBossName(
                                                        entry.getKey()
                                                ),
                                                kills
                                        )
                                );
                            }
                        }
                    }
                }
            }

            bosses.sort(
                    Comparator.comparingLong(
                            BossStats::getKills
                    ).reversed()
            );

            return new PlayerStats(
                    displayName,
                    totalLevel,
                    totalXp,
                    overallRank,
                    ehp,
                    ehb,
                    bosses,
                    snapshot != null
            );
        }
        finally
        {
            connection.disconnect();
        }
    }

    private String formatBossName(String name)
    {
        String[] words = name.split("_");
        StringBuilder formatted = new StringBuilder();

        for (String word : words)
        {
            if (word.isEmpty())
            {
                continue;
            }

            if (formatted.length() > 0)
            {
                formatted.append(" ");
            }

            formatted.append(
                    word.substring(0, 1)
                            .toUpperCase(Locale.ROOT)
            );

            formatted.append(
                    word.substring(1)
            );
        }

        return formatted.toString();
    }

    private JsonObject getObject(
            JsonObject object,
            String key)
    {
        if (object == null
                || !object.has(key)
                || object.get(key).isJsonNull()
                || !object.get(key).isJsonObject())
        {
            return null;
        }

        return object.getAsJsonObject(key);
    }

    private String getString(
            JsonObject object,
            String key,
            String fallback)
    {
        if (object == null
                || !object.has(key)
                || object.get(key).isJsonNull())
        {
            return fallback;
        }

        return object.get(key).getAsString();
    }

    private double getDouble(
            JsonObject object,
            String key)
    {
        if (object == null
                || !object.has(key)
                || object.get(key).isJsonNull())
        {
            return 0;
        }

        return object.get(key).getAsDouble();
    }

    private int getInt(
            JsonObject object,
            String key)
    {
        if (object == null
                || !object.has(key)
                || object.get(key).isJsonNull())
        {
            return 0;
        }

        return object.get(key).getAsInt();
    }

    private long getLong(
            JsonObject object,
            String key)
    {
        if (object == null
                || !object.has(key)
                || object.get(key).isJsonNull())
        {
            return 0;
        }

        return object.get(key).getAsLong();
    }

    public static class PlayerStats
    {
        private final String displayName;
        private final int totalLevel;
        private final long totalXp;
        private final int overallRank;
        private final double ehp;
        private final double ehb;
        private final List<BossStats> bosses;
        private final boolean hasSnapshot;

        public PlayerStats(
                String displayName,
                int totalLevel,
                long totalXp,
                int overallRank,
                double ehp,
                double ehb,
                List<BossStats> bosses,
                boolean hasSnapshot)
        {
            this.displayName = displayName;
            this.totalLevel = totalLevel;
            this.totalXp = totalXp;
            this.overallRank = overallRank;
            this.ehp = ehp;
            this.ehb = ehb;
            this.bosses = Collections.unmodifiableList(
                    new ArrayList<>(bosses)
            );
            this.hasSnapshot = hasSnapshot;
        }

        public String getDisplayName()
        {
            return displayName;
        }

        public int getTotalLevel()
        {
            return totalLevel;
        }

        public long getTotalXp()
        {
            return totalXp;
        }

        public int getOverallRank()
        {
            return overallRank;
        }

        public double getEhp()
        {
            return ehp;
        }

        public double getEhb()
        {
            return ehb;
        }

        public List<BossStats> getBosses()
        {
            return bosses;
        }

        public boolean hasSnapshot()
        {
            return hasSnapshot;
        }
    }

    public static class BossStats
    {
        private final String name;
        private final long kills;

        public BossStats(
                String name,
                long kills)
        {
            this.name = name;
            this.kills = kills;
        }

        public String getName()
        {
            return name;
        }

        public long getKills()
        {
            return kills;
        }
    }

    private static class CacheEntry
    {
        private final PlayerStats stats;
        private final long timestamp;

        private CacheEntry(
                PlayerStats stats,
                long timestamp)
        {
            this.stats = stats;
            this.timestamp = timestamp;
        }
    }
}
