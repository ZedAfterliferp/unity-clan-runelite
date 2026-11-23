package com.unityclan.wom;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.concurrent.TimeUnit;
import java.io.InputStreamReader;
import com.unityclan.net.ClanHttpConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class WiseOldManService
{
    private static final String API =
            "https://api.wiseoldman.net/v2";

    private static final int GROUP_ID = 10453;

    private static final File COMPETITION_CACHE = new File(
            new File(System.getProperty("user.home"), ".runelite/unity-clan"), "competition-cache.json");
    private static final long COMPETITION_REFRESH_MS = TimeUnit.HOURS.toMillis(6);
    private long lastAttemptAt = 0L;
    private CompetitionData memoryCompetition;
    private boolean cacheLoaded;

    /** The plugin may poll every five minutes; only one network refresh is allowed per six hours. */
    public synchronized CompetitionData fetchCompetition() throws Exception {
        if (!cacheLoaded) {
            memoryCompetition = readCompetitionCache();
            cacheLoaded = true;
        }
        long now = System.currentTimeMillis();
        if (COMPETITION_CACHE.isFile()
                && now - COMPETITION_CACHE.lastModified() < COMPETITION_REFRESH_MS)
            return memoryCompetition;
        if (now - lastAttemptAt < COMPETITION_REFRESH_MS && lastAttemptAt != 0L)
            return memoryCompetition;
        lastAttemptAt = now;
        try {
            CompetitionData fresh = fetchCompetitionLive();
            memoryCompetition = fresh;
            writeCompetitionCache(fresh);
            return fresh;
        } catch (Exception ex) {
            if (memoryCompetition != null) return memoryCompetition;
            throw ex;
        }
    }

    private CompetitionData readCompetitionCache() {
        if (!COMPETITION_CACHE.isFile()) return null;
        try (Reader reader = new InputStreamReader(new FileInputStream(COMPETITION_CACHE), StandardCharsets.UTF_8)) {
            JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
            if (root.has("empty") && root.get("empty").getAsBoolean()) return null;
            List<PlayerStanding> standings = new ArrayList<>();
            JsonArray data = root.getAsJsonArray("standings");
            if (data != null) for (JsonElement element : data) {
                JsonObject item = element.getAsJsonObject();
                PlayerStanding standing = new PlayerStanding(item.get("name").getAsString(), item.get("gained").getAsDouble());
                standing.setRank(item.get("rank").getAsInt());
                standings.add(standing);
            }
            return new CompetitionData(root.get("id").getAsInt(), root.get("title").getAsString(),
                    root.get("type").getAsString(), Instant.parse(root.get("startsAt").getAsString()),
                    Instant.parse(root.get("endsAt").getAsString()), standings);
        } catch (Exception ignored) { return null; }
    }

    private void writeCompetitionCache(CompetitionData competition) {
        try {
            File parent = COMPETITION_CACHE.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) return;
            JsonObject root = new JsonObject();
            if (competition == null) root.addProperty("empty", true);
            else {
                root.addProperty("id", competition.getId());
                root.addProperty("title", competition.getTitle());
                root.addProperty("type", competition.getType());
                root.addProperty("startsAt", competition.getStartsAt().toString());
                root.addProperty("endsAt", competition.getEndsAt().toString());
                JsonArray standings = new JsonArray();
                for (PlayerStanding standing : competition.getStandings()) {
                    JsonObject item = new JsonObject();
                    item.addProperty("name", standing.getName());
                    item.addProperty("gained", standing.getGained());
                    item.addProperty("rank", standing.getRank());
                    standings.add(item);
                }
                root.add("standings", standings);
            }
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(COMPETITION_CACHE), StandardCharsets.UTF_8)) {
                writer.write(root.toString());
            }
        } catch (Exception ignored) { /* Cache failure must not break the dashboard. */ }
    }

    private CompetitionData fetchCompetitionLive() throws Exception
    {
        JsonArray competitions = readJsonArray(
                API + "/groups/" + GROUP_ID + "/competitions"
        );

        Instant now = Instant.now();
        JsonObject selected = null;
        Instant selectedStart = null;
        int selectedPriority = Integer.MAX_VALUE;

        for (JsonElement element : competitions)
        {
            JsonObject competition = element.getAsJsonObject();

            Instant start = Instant.parse(
                    competition.get("startsAt").getAsString()
            );

            Instant end = Instant.parse(
                    competition.get("endsAt").getAsString()
            );

            int priority;

            if (!start.isAfter(now) && end.isAfter(now))
            {
                priority = 0; // Active
            }
            else if (start.isAfter(now))
            {
                priority = 1; // Upcoming
            }
            else
            {
                priority = 2; // Completed
            }

            boolean replace = false;

            if (selected == null || priority < selectedPriority)
            {
                replace = true;
            }
            else if (priority == selectedPriority)
            {
                int comparison = start.compareTo(selectedStart);

                replace = priority == 1
                        ? comparison < 0
                        : comparison > 0;
            }

            if (replace)
            {
                selected = competition;
                selectedStart = start;
                selectedPriority = priority;
            }
        }

        if (selected == null)
        {
            return null;
        }

        int id = selected.get("id").getAsInt();

        JsonObject details = readJsonObject(
                API + "/competitions/" + id
        );

        String title = details.get("title").getAsString();
        String type = details.get("type").getAsString();

        Instant startsAt = Instant.parse(
                details.get("startsAt").getAsString()
        );

        Instant endsAt = Instant.parse(
                details.get("endsAt").getAsString()
        );

        List<PlayerStanding> standings = new ArrayList<>();

        JsonArray participations =
                details.getAsJsonArray("participations");

        if (participations != null)
        {
            for (JsonElement element : participations)
            {
                JsonObject participation =
                        element.getAsJsonObject();

                JsonObject player =
                        participation.getAsJsonObject("player");

                if (player == null)
                {
                    continue;
                }

                String name =
                        player.get("displayName").getAsString();

                double gained = getGained(participation);

                standings.add(
                        new PlayerStanding(name, gained)
                );
            }
        }

        standings.sort(
                Comparator.comparingDouble(
                        PlayerStanding::getGained
                ).reversed()
        );

        int position = 0;
        int rank = 0;
        Double previousScore = null;

        for (PlayerStanding standing : standings)
        {
            position++;

            if (previousScore == null
                    || Double.compare(
                    standing.getGained(), previousScore
            ) != 0)
            {
                rank = position;
            }

            standing.setRank(rank);
            previousScore = standing.getGained();
        }

        return new CompetitionData(
                id, title, type, startsAt, endsAt, standings
        );
    }

    private double getGained(JsonObject participation)
    {
        JsonArray deltas =
                participation.getAsJsonArray("deltas");

        if (deltas != null && deltas.size() > 0)
        {
            JsonObject selected = null;

            for (JsonElement element : deltas)
            {
                JsonObject delta = element.getAsJsonObject();

                if (delta.has("metric")
                        && "total".equals(
                        delta.get("metric").getAsString()
                ))
                {
                    selected = delta;
                    break;
                }
            }

            if (selected == null)
            {
                selected = deltas.get(0).getAsJsonObject();
            }

            JsonObject values =
                    selected.getAsJsonObject("values");

            if (values != null && values.has("gained"))
            {
                return values.get("gained").getAsDouble();
            }
        }

        // Compatibility with older WOM responses.
        JsonObject progress =
                participation.getAsJsonObject("progress");

        if (progress != null && progress.has("gained"))
        {
            return progress.get("gained").getAsDouble();
        }

        return 0;
    }

    private JsonArray readJsonArray(String address)
            throws Exception
    {
        return readJson(address).getAsJsonArray();
    }

    private JsonObject readJsonObject(String address)
            throws Exception
    {
        return readJson(address).getAsJsonObject();
    }

    private JsonElement readJson(String address) throws Exception
    {
        return WiseOldManRequestGate.execute(() -> readJsonDirect(address));
    }

    private JsonElement readJsonDirect(String address) throws Exception
    {
        ClanHttpConnection connection =
                ClanHttpConnection.open(address);

        connection.setRequestMethod("GET");
        connection.setRequestProperty(
                "Accept", "application/json"
        );
        connection.setRequestProperty(
                "User-Agent", "UnityClanRuneLitePlugin/1.0"
        );
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);

        try
        {
            int status = connection.getResponseCode();

            if (status == 429)
            {
                WiseOldManRequestGate.onRateLimited(connection.getHeaderField("Retry-After"));
            }
            if (status != 200)
            {
                throw new IllegalStateException(
                        "Wise Old Man returned HTTP " + status
                );
            }

            StringBuilder response = new StringBuilder();

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

            return new JsonParser()
                    .parse(response.toString());
        }
        finally
        {
            connection.disconnect();
        }
    }

    public static class CompetitionData
    {
        private final int id;
        private final String title;
        private final String type;
        private final Instant startsAt;
        private final Instant endsAt;
        private final List<PlayerStanding> standings;

        public CompetitionData(
                int id,
                String title,
                String type,
                Instant startsAt,
                Instant endsAt,
                List<PlayerStanding> standings)
        {
            this.id = id;
            this.title = title;
            this.type = type;
            this.startsAt = startsAt;
            this.endsAt = endsAt;
            this.standings = Collections.unmodifiableList(
                    new ArrayList<>(standings)
            );
        }

        public int getId()
        {
            return id;
        }

        public String getTitle()
        {
            return title;
        }

        public String getType()
        {
            return type;
        }

        public Instant getStartsAt()
        {
            return startsAt;
        }

        public Instant getEndsAt()
        {
            return endsAt;
        }

        public List<PlayerStanding> getStandings()
        {
            return standings;
        }

        public String getStatus()
        {
            Instant now = Instant.now();

            if (now.isBefore(startsAt))
            {
                return "Upcoming";
            }

            if (now.isBefore(endsAt))
            {
                return "Active";
            }

            return "Completed";
        }

        public PlayerStanding getPersonalStanding(
                String username)
        {
            if (username == null)
            {
                return null;
            }

            String normalized = username
                    .replace('_', ' ')
                    .trim();

            for (PlayerStanding standing : standings)
            {
                if (standing.getName()
                        .replace('_', ' ')
                        .equalsIgnoreCase(normalized))
                {
                    return standing;
                }
            }

            return null;
        }
    }

    public static class PlayerStanding
    {
        private final String name;
        private final double gained;
        private int rank;

        public PlayerStanding(String name, double gained)
        {
            this.name = name;
            this.gained = gained;
        }

        public String getName()
        {
            return name;
        }

        public double getGained()
        {
            return gained;
        }

        public int getRank()
        {
            return rank;
        }

        private void setRank(int rank)
        {
            this.rank = rank;
        }
    }
}