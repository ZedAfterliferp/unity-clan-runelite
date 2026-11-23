
package com.unityclan.temple;

import com.google.gson.JsonArray;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TempleOSRSService
{
    private static final TempleOSRSService SHARED = new TempleOSRSService();

    public static TempleOSRSService shared() { return SHARED; }

    private static final String API_URL =
            "https://templeosrs.com/api/collection-log/"
                    + "player_recent_items.php";

    private static final long CACHE_DURATION =
            6 * 60 * 60 * 1000L;

    private static final int MAX_ITEMS = 5;

    private final Map<String, CacheEntry> cache =
            new HashMap<>();

    /**
     * Fetches a player's recent collection log items.
     *
     * This is a blocking network method.
     * Always call it from a background thread.
     */
    public synchronized List<CollectionLogItem>
    fetchRecentItems(String username)
            throws Exception
    {
        if (username == null
                || username.trim().isEmpty())
        {
            throw new IllegalArgumentException(
                    "Player name cannot be empty."
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
            return cached.items;
        }

        List<CollectionLogItem> items =
                requestRecentItems(username);

        List<CollectionLogItem> result =
                Collections.unmodifiableList(
                        new ArrayList<>(items)
                );

        cache.put(
                cacheKey,
                new CacheEntry(result, now)
        );

        return result;
    }

    private List<CollectionLogItem> requestRecentItems(
            String username) throws Exception
    {
        String encodedName = URLEncoder.encode(
                username.trim(),
                StandardCharsets.UTF_8.name()
        );

        String urlString =
                API_URL
                        + "?player="
                        + encodedName
                        + "&count="
                        + MAX_ITEMS;

        HttpURLConnection connection =
                (HttpURLConnection)
                        new URL(urlString).openConnection();

        connection.setRequestMethod("GET");

        connection.setRequestProperty(
                "Accept",
                "application/json"
        );

        connection.setRequestProperty(
                "User-Agent",
                "UnityClanRuneLitePlugin/1.0"
        );

        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);

        try
        {
            int status =
                    connection.getResponseCode();

            if (status != 200)
            {
                throw new IllegalStateException(
                        "TempleOSRS returned HTTP "
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

            JsonElement root = new JsonParser()
                    .parse(response.toString());

            if (!root.isJsonObject())
            {
                throw new IllegalStateException(
                        "Invalid TempleOSRS response."
                );
            }

            JsonObject object =
                    root.getAsJsonObject();

            JsonElement data =
                    object.get("data");

            if (data == null || data.isJsonNull())
            {
                return Collections.emptyList();
            }

            if (!data.isJsonArray())
            {
                throw new IllegalStateException(
                        "Unexpected collection log format."
                );
            }

            JsonArray itemsArray =
                    data.getAsJsonArray();

            List<CollectionLogItem> items =
                    new ArrayList<>();

            for (JsonElement element : itemsArray)
            {
                if (!element.isJsonObject())
                {
                    continue;
                }

                JsonObject item =
                        element.getAsJsonObject();

                int itemId = getInt(
                        item,
                        "id"
                );

                String itemName = getString(
                        item,
                        "name",
                        "Unknown Item"
                );

                String date = getString(
                        item,
                        "date",
                        "Unknown Date"
                );

                long dateUnix = getLong(
                        item,
                        "date_unix"
                );

                boolean notable = getBoolean(
                        item,
                        "notable_item"
                );

                items.add(
                        new CollectionLogItem(
                                itemId,
                                itemName,
                                date,
                                dateUnix,
                                notable
                        )
                );
            }

            items.sort(
                    (first, second) ->
                            Long.compare(
                                    second.getDateUnix(),
                                    first.getDateUnix()
                            )
            );

            if (items.size() > MAX_ITEMS)
            {
                return new ArrayList<>(
                        items.subList(0, MAX_ITEMS)
                );
            }

            return items;
        }
        finally
        {
            connection.disconnect();
        }
    }

    private String getString(
            JsonObject object,
            String key,
            String fallback)
    {
        if (!object.has(key)
                || object.get(key).isJsonNull())
        {
            return fallback;
        }

        return object.get(key).getAsString();
    }

    private int getInt(
            JsonObject object,
            String key)
    {
        if (!object.has(key)
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
        if (!object.has(key)
                || object.get(key).isJsonNull())
        {
            return 0L;
        }

        return object.get(key).getAsLong();
    }

    private boolean getBoolean(
            JsonObject object,
            String key)
    {
        if (!object.has(key)
                || object.get(key).isJsonNull())
        {
            return false;
        }

        return object.get(key).getAsBoolean();
    }

    public static class CollectionLogItem
    {
        private final int itemId;
        private final String name;
        private final String date;
        private final long dateUnix;
        private final boolean notable;

        public CollectionLogItem(
                int itemId,
                String name,
                String date,
                long dateUnix,
                boolean notable)
        {
            this.itemId = itemId;
            this.name = name;
            this.date = date;
            this.dateUnix = dateUnix;
            this.notable = notable;
        }

        public int getItemId()
        {
            return itemId;
        }

        public String getName()
        {
            return name;
        }

        public String getDate()
        {
            return date;
        }

        public long getDateUnix()
        {
            return dateUnix;
        }

        public boolean isNotable()
        {
            return notable;
        }
    }

    private static class CacheEntry
    {
        private final List<CollectionLogItem> items;
        private final long timestamp;

        private CacheEntry(
                List<CollectionLogItem> items,
                long timestamp)
        {
            this.items = items;
            this.timestamp = timestamp;
        }
    }
}
