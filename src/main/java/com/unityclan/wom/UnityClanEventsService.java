package com.unityclan.wom;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class UnityClanEventsService
{
    private static final UnityClanEventsService SHARED = new UnityClanEventsService();
    public static UnityClanEventsService shared() { return SHARED; }

    private static final String API = "https://api.wiseoldman.net/v2";
    private static final int GROUP_ID = 10453;

    private static final long REFRESH_MS = TimeUnit.HOURS.toMillis(6);
    private static final File CACHE_FILE = new File(
            new File(System.getProperty("user.home"), ".runelite/unity-clan"),
            "events-cache.json");

    /** The cache stores raw WOM responses so event rankings survive restarts. */
    private JsonObject loadCache()
    {
        if (!CACHE_FILE.isFile()) return new JsonObject();
        try (FileReader reader = new FileReader(CACHE_FILE))
        {
            JsonElement value = new JsonParser().parse(reader);
            return value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
        }
        catch (Exception ignored) { return new JsonObject(); }
    }

    private void saveCache(JsonObject cache)
    {
        File directory = CACHE_FILE.getParentFile();
        if (!directory.exists() && !directory.mkdirs()) return;
        File temp = new File(directory, "events-cache.tmp");
        try (FileWriter writer = new FileWriter(temp))
        {
            writer.write(cache.toString());
        }
        catch (IOException ex) { return; }
        if (!temp.renameTo(CACHE_FILE))
        {
            try (FileWriter writer = new FileWriter(CACHE_FILE))
            {
                writer.write(cache.toString());
            }
            catch (IOException ignored) { /* Keep existing cache if write fails. */ }
            temp.delete();
        }
    }

    private long cachedAt(JsonObject cache)
    {
        try { return cache.get("updatedAt").getAsLong(); }
        catch (Exception ignored) { return 0L; }
    }

    /** Returns saved results without making any network requests. */
    public synchronized List<EventData> getCachedEvents()
    {
        JsonObject cache = loadCache();
        JsonObject details = cache.getAsJsonObject("details");
        if (details == null) return Collections.emptyList();
        List<EventData> events = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : details.entrySet())
        {
            try { events.add(parseEvent(entry.getValue().getAsJsonObject())); }
            catch (Exception ignored) { /* Skip damaged cache entries. */ }
        }
        events.sort(Comparator.comparing(EventData::getStartsAt).reversed());
        return Collections.unmodifiableList(events);
    }

    public synchronized long getLastRefreshTime()
    {
        return cachedAt(loadCache());
    }

    public synchronized List<EventData> fetchEvents() throws Exception
    {
        return fetchEvents(false);
    }

    public synchronized List<EventData> fetchEvents(boolean force) throws Exception
    {
        JsonObject cache = loadCache();
        long now = System.currentTimeMillis();
        long updated = cachedAt(cache);
        if (!force && updated > 0 && now - updated < REFRESH_MS)
            return getCachedEvents();

        JsonObject details = cache.getAsJsonObject("details");
        if (details == null)
        {
            details = new JsonObject();
            cache.add("details", details);
        }

        // One index request every six hours (or when manually refreshed).
        JsonArray response;
        try
        {
            response = readArray(API + "/groups/" + GROUP_ID + "/competitions");
        }
        catch (Exception ex)
        {
            if (details.size() > 0) return getCachedEvents();
            throw ex;
        }

        boolean complete = true;
        Set<String> known = new HashSet<>();
        for (JsonElement element : response)
        {
            if (!element.isJsonObject()) continue;
            JsonObject summary = element.getAsJsonObject();
            if (!summary.has("id") || summary.get("id").isJsonNull()) continue;
            String id = summary.get("id").getAsString();
            known.add(id);
            boolean needsDetails = true;
            if (details.has(id))
            {
                try
                {
                    EventData saved = parseEvent(details.getAsJsonObject(id));
                    // Completed events are immutable for caching purposes.
                    // Events still active/upcoming are refreshed on each six-hour pass.
                    needsDetails = !"Completed".equals(saved.getStatus()) ||
                            saved.getEndsAt().plusSeconds(6 * 3600).isAfter(Instant.now());
                }
                catch (Exception ignored) { /* Re-download broken entries. */ }
            }
            if (!needsDetails) continue;
            try
            {
                JsonObject event = readObject(API + "/competitions/" + id);
                parseEvent(event); // Validate before caching.
                details.add(id, event);
                saveCache(cache); // Preserve progress if a later request fails.
            }
            catch (Exception ex)
            {
                complete = false;
                System.err.println("Unity Clan: Unable to load competition " + id + ": " + ex.getMessage());
                // A shared cooldown means continuing would just cause more failures.
                if (ex.getMessage() != null &&
                        (ex.getMessage().contains("429") || ex.getMessage().contains("cooldown")))
                    break;
            }
        }
        // Keep older completed competitions even if the index is paginated.
        if (complete) cache.addProperty("updatedAt", now);
        saveCache(cache);
        return getCachedEvents();
    }

    private EventData parseEvent(JsonObject details)
    {
        int id = details.get("id").getAsInt();
        String title = details.get("title").getAsString();
        String type = details.has("type") && !details.get("type").isJsonNull()
                ? details.get("type").getAsString() : "unknown";
        Instant startsAt = Instant.parse(details.get("startsAt").getAsString());
        Instant endsAt = Instant.parse(details.get("endsAt").getAsString());
        List<Standing> standings = new ArrayList<>();

        JsonArray participations = details.getAsJsonArray("participations");
        if (participations != null)
        {
            for (JsonElement element : participations)
            {
                if (!element.isJsonObject())
                {
                    continue;
                }

                JsonObject participation = element.getAsJsonObject();
                JsonObject player = participation.getAsJsonObject("player");
                if (player == null || !player.has("displayName")
                        || player.get("displayName").isJsonNull())
                {
                    continue;
                }

                standings.add(new Standing(
                        player.get("displayName").getAsString(),
                        gained(participation)));
            }
        }

        standings.sort(Comparator.comparingDouble(Standing::getGained).reversed());
        Double previous = null;
        int rank = 0;
        for (int i = 0; i < standings.size(); i++)
        {
            Standing standing = standings.get(i);
            if (previous == null || Double.compare(previous, standing.gained) != 0)
            {
                rank = i + 1;
            }
            standing.rank = rank;
            previous = standing.gained;
        }

        return new EventData(id, title, type, startsAt, endsAt, standings);
    }

    private double gained(JsonObject participation)
    {
        JsonArray deltas = participation.getAsJsonArray("deltas");
        if (deltas != null && deltas.size() > 0)
        {
            JsonObject selected = null;
            for (JsonElement element : deltas)
            {
                JsonObject delta = element.getAsJsonObject();
                if (delta.has("metric") && !delta.get("metric").isJsonNull()
                        && "total".equals(delta.get("metric").getAsString()))
                {
                    selected = delta;
                    break;
                }
            }

            if (selected == null)
            {
                selected = deltas.get(0).getAsJsonObject();
            }

            JsonObject values = selected.getAsJsonObject("values");
            if (values != null && values.has("gained")
                    && !values.get("gained").isJsonNull())
            {
                return values.get("gained").getAsDouble();
            }
        }

        JsonObject progress = participation.getAsJsonObject("progress");
        if (progress != null && progress.has("gained")
                && !progress.get("gained").isJsonNull())
        {
            return progress.get("gained").getAsDouble();
        }
        return 0;
    }

    private JsonArray readArray(String url) throws Exception
    {
        return readJson(url).getAsJsonArray();
    }

    private JsonObject readObject(String url) throws Exception
    {
        return readJson(url).getAsJsonObject();
    }

    private JsonElement readJson(String address) throws Exception
    {
        return WiseOldManRequestGate.execute(() -> {
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "UnityClanRuneLitePlugin/1.0");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            try
            {
                int status = connection.getResponseCode();
                if (status == 429)
                    WiseOldManRequestGate.onRateLimited(connection.getHeaderField("Retry-After"));
                if (status != 200)
                    throw new IllegalStateException("Wise Old Man returned HTTP " + status);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        connection.getInputStream(), StandardCharsets.UTF_8)))
                {
                    return new JsonParser().parse(reader);
                }
            }
            finally { connection.disconnect(); }
        });
    }

    public static class EventData
    {
        private final int id;
        private final String title;
        private final String type;
        private final Instant startsAt;
        private final Instant endsAt;
        private final List<Standing> standings;

        public EventData(int id, String title, String type, Instant startsAt,
                         Instant endsAt, List<Standing> standings)
        {
            this.id = id;
            this.title = title;
            this.type = type;
            this.startsAt = startsAt;
            this.endsAt = endsAt;
            this.standings = Collections.unmodifiableList(new ArrayList<>(standings));
        }

        public int getId() { return id; }
        public String getTitle() { return title; }
        public String getType() { return type; }
        public Instant getStartsAt() { return startsAt; }
        public Instant getEndsAt() { return endsAt; }
        public List<Standing> getStandings() { return standings; }

        public String getStatus()
        {
            Instant now = Instant.now();
            if (now.isBefore(startsAt)) { return "Upcoming"; }
            if (now.isBefore(endsAt)) { return "Active"; }
            return "Completed";
        }
    }

    public static class Standing
    {
        private final String name;
        private final double gained;
        private int rank;

        public Standing(String name, double gained)
        {
            this.name = name;
            this.gained = gained;
        }

        public String getName() { return name; }
        public double getGained() { return gained; }
        public int getRank() { return rank; }
    }
}
