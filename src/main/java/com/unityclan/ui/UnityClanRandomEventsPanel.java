package com.unityclan.ui;

import com.google.gson.JsonArray;
import com.unityclan.members.ClanMemberInfo;
import java.io.OutputStream;
import java.util.List;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.Box;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Casual clan game: character names and ranks are not server-authenticated. */
public class UnityClanRandomEventsPanel extends JPanel
{
    private static final String STATUS_URL =
            "https://unity-clan-admin-portal.vercel.app/api/random-events/status";
    private static final String START_URL =
            "https://unity-clan-admin-portal.vercel.app/api/random-events/start";
    private static final String ANSWER_URL =
            "https://unity-clan-admin-portal.vercel.app/api/random-events/answer";
    private static final Color BG = Color.decode("#242424");
    private static final Color GOLD = Color.decode("#E5A23A");
    private static final Color CARD = Color.decode("#332626");
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "unity-random-events-read");
        t.setDaemon(true);
        return t;
    });
    private final JLabel state = label("Loading event status...", Color.WHITE);
    private final JLabel question = label("No active question", Color.WHITE);
    private final JLabel countdown = label("", GOLD);
    private final JLabel cooldown = label("", GOLD);
    private final JLabel leaderboard = label("No winners yet", Color.WHITE);
    private final JButton[] choices = new JButton[4];
    private final JButton trivia = new JButton("Start");
    private final JLabel feedback = label("", Color.LIGHT_GRAY);
    private String username;
    private String rank;
    private List<ClanMemberInfo> currentRoster;
    private String lastShownEventId;
    private String activeEventId;
    private String attemptedEventId;
    private boolean submitting;
    private final Timer timer;
    private boolean polling;
    private volatile boolean fetching;
    private Instant endsAt;
    private Instant nextAvailable;
    private long lastPollAt;

    public UnityClanRandomEventsPanel()
    {
        setBackground(BG);
        setLayout(new BorderLayout());
        JPanel body = new JPanel();
        body.setBackground(BG);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(4, 6, 8, 6));

        body.add(section("RANDOM TRIVIA"));
        body.add(section("EVENT CONTROLS"));
        JLabel notice = label("<span style='font-size:9px'>Casual mode - no account linking</span>", Color.LIGHT_GRAY);
        notice.setToolTipText("OSRS names and ranks are not verified by the server.");
        body.add(notice);
        JPanel controls = new JPanel(new GridLayout(1, 1, 0, 0));
        controls.setBackground(BG);
        controls.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        controls.setPreferredSize(new Dimension(180, 28));
        trivia.setMargin(new java.awt.Insets(2, 2, 2, 2));
        trivia.setFont(new Font("Arial", Font.PLAIN, 11));
        trivia.setToolTipText("Start a 60-second Trivia event");
        trivia.addActionListener(e -> startEvent("trivia"));
        trivia.setEnabled(false);
        controls.add(trivia);
        body.add(controls);
        body.add(Box.createVerticalStrut(8));

        body.add(section("LIVE EVENT"));
        body.add(state);
        body.add(question);
        body.add(countdown);
        body.add(feedback);
        JPanel answers = new JPanel(new GridLayout(4, 1, 0, 4));
        answers.setBackground(BG);
        answers.setAlignmentX(Component.LEFT_ALIGNMENT);
        answers.setMaximumSize(new Dimension(Integer.MAX_VALUE, 144));
        for (int i = 0; i < 4; i++) {
            JButton button = new JButton((char)('A' + i) + ". --");
            button.setEnabled(false);
            final int answerIndex = i;
            button.addActionListener(e -> answerEvent(answerIndex));
            button.setHorizontalAlignment(SwingConstants.LEFT);
            button.setPreferredSize(new Dimension(180, 32));
            choices[i] = button;
            answers.add(button);
        }
        body.add(answers);
        body.add(Box.createVerticalStrut(8));

        body.add(section("NEXT EVENT"));
        body.add(cooldown);
        body.add(Box.createVerticalStrut(8));
        body.add(section("TOP 5 PARTICIPANTS"));
        body.add(leaderboard);
        JLabel credit = label("Trivia: The Trivia API", Color.LIGHT_GRAY);
        body.add(credit);
        body.add(Box.createVerticalGlue());
        // Keep the content narrower than the RuneLite sidebar viewport.
        body.setPreferredSize(new Dimension(205, body.getPreferredSize().height));

        JScrollPane scroll = new JScrollPane(body,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getViewport().setBackground(BG);
        add(scroll, BorderLayout.CENTER);

        timer = new Timer(1000, e -> {
            updateClocks();
            if (polling && System.currentTimeMillis() - lastPollAt >= 15000L) refresh();
        });
    }

    private static JLabel label(String text, Color color) {
        JLabel l = new JLabel("<html>" + text + "</html>");
        l.setForeground(color);
        l.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setMaximumSize(new Dimension(Integer.MAX_VALUE, l.getPreferredSize().height + 12));
        return l;
    }
    private static JLabel section(String title) {
        JLabel l = label(title, GOLD);
        l.setFont(new Font("Arial", Font.BOLD, 12));
        return l;
    }
    private static String safe(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }
    public void setUsername(String name) {
        username = name == null ? null : name.trim();
        resolveRank();
        updateButtons();
    }

    public void updateRoster(List<ClanMemberInfo> members) {
        currentRoster = members;
        resolveRank();
        updateButtons();
    }

    private void resolveRank() {
        rank = null;
        if (username != null && currentRoster != null) {
            for (ClanMemberInfo member : currentRoster) {
                if (username.equalsIgnoreCase(member.getName())) {
                    rank = member.getRank();
                    break;
                }
            }
        }
    }

    private boolean officer() {
        if (rank == null) return false;
        String r = rank.trim().toLowerCase(java.util.Locale.ROOT);
        return r.equals("brigadier") || r.equals("captain") || r.equals("general")
                || r.equals("deputy owner") || r.equals("owner") || r.equals("champion");
    }

    private void updateButtons() {
        boolean identified = username != null && !username.isEmpty() && rank != null;
        boolean canStart = identified && officer() && !submitting
                && activeEventId == null
                && (nextAvailable == null || !Instant.now().isBefore(nextAvailable));
        trivia.setEnabled(canStart);
        boolean canAnswer = identified && !submitting && activeEventId != null
                && !activeEventId.equals(attemptedEventId)
                && endsAt != null && Instant.now().isBefore(endsAt);
        for (JButton choice : choices) choice.setEnabled(canAnswer);
    }

    private JsonObject post(String endpoint, JsonObject payload) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(7000);
            c.setReadTimeout(10000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            int status = c.getResponseCode();
            java.io.InputStream input = status < 400 ? c.getInputStream() : c.getErrorStream();
            if (input == null) throw new IllegalStateException("HTTP " + status);
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                    if (body.length() > 65536) throw new IllegalStateException("Response too large");
                }
            }
            return new JsonParser().parse(body.toString()).getAsJsonObject();
        } finally { c.disconnect(); }
    }

    private void startEvent(String type) {
        if (submitting || !officer() || username == null || activeEventId != null
                || (nextAvailable != null && Instant.now().isBefore(nextAvailable))) return;
        String eventName = "trivia".equals(type) ? "Trivia" : "Math";
        Object[] options = {"Cancel", "Start Event"};
        int decision = JOptionPane.showOptionDialog(
                this,
                "Start a " + eventName + " event?\n\n"
                        + "The event lasts 60 seconds and starts a clan-wide\n"
                        + "30-minute cooldown, even if nobody wins.",
                "Confirm Random Event",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]);
        if (decision != 1) return;
        // Recheck after the dialog in case the server status changed while it was open.
        if (submitting || activeEventId != null
                || (nextAvailable != null && Instant.now().isBefore(nextAvailable))) return;
        final String name = username;
        final String playerRank = rank;
        submitting = true;
        feedback.setText("Starting " + type + "...");
        updateButtons();
        worker.execute(() -> {
            try {
                JsonObject payload = new JsonObject();
                payload.addProperty("type", type);
                payload.addProperty("playerName", name);
                payload.addProperty("rank", playerRank);
                JsonObject result = post(START_URL, payload);
                SwingUtilities.invokeLater(() -> {
                    feedback.setText(result.has("ok") && result.get("ok").getAsBoolean()
                            ? "Event started!" : "Unable to start: " + safe(str(result, "error")));
                    submitting = false;
                    updateButtons();
                    refresh();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    feedback.setText("Start failed: " + safe(ex.getMessage() == null ? "network error" : ex.getMessage()));
                    submitting = false;
                    updateButtons();
                });
            }
        });
    }

    private void answerEvent(int index) {
        if (submitting || username == null || rank == null || activeEventId == null
                || activeEventId.equals(attemptedEventId)
                || endsAt == null || !Instant.now().isBefore(endsAt)) return;
        final String name = username;
        final String eventId = activeEventId;
        submitting = true;
        feedback.setText("Submitting answer...");
        updateButtons();
        worker.execute(() -> {
            try {
                JsonObject payload = new JsonObject();
                payload.addProperty("eventId", eventId);
                payload.addProperty("playerName", name);
                payload.addProperty("choice", index);
                JsonObject result = post(ANSWER_URL, payload);
                SwingUtilities.invokeLater(() -> {
                    String error = str(result, "error");
                    if (result.has("ok") && result.get("ok").getAsBoolean()) {
                        attemptedEventId = eventId;
                        feedback.setText(result.get("won").getAsBoolean() ? "You won!" : "Incorrect answer");
                    } else {
                        if ("already_answered".equals(error)) attemptedEventId = eventId;
                        if ("already_answered".equals(error)) feedback.setText("Already answered this event");
                        else if ("event_closed".equals(error) || "event_expired".equals(error)) feedback.setText("Event has ended");
                        else feedback.setText("Answer: " + safe(error));
                    }
                    submitting = false;
                    updateButtons();
                    refresh();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    feedback.setText("Answer failed: " + safe(ex.getMessage() == null ? "network error" : ex.getMessage()));
                    submitting = false;
                    updateButtons();
                });
            }
        });
    }

    public void setPolling(boolean enabled) {
        polling = enabled;
        if (enabled) { timer.start(); refresh(); }
        else timer.stop();
    }
    private void refresh() {
        if (fetching) return;
        fetching = true;
        lastPollAt = System.currentTimeMillis();
        worker.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(STATUS_URL).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setRequestProperty("Accept", "application/json");
                if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                        if (sb.length() > 65536) throw new IllegalStateException("Response too large");
                    }
                }
                JsonObject data = new JsonParser().parse(sb.toString()).getAsJsonObject();
                SwingUtilities.invokeLater(() -> showStatus(data));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> state.setText("Status unavailable: " + safe(ex.getMessage() == null ? "network error" : ex.getMessage())));
            } finally {
                if (c != null) c.disconnect();
                fetching = false;
            }
        });
    }
    private void showStatus(JsonObject data) {
        JsonElement active = data.get("activeEvent");
        JsonElement last = data.get("lastEvent");
        if (active != null && active.isJsonObject()) {
            JsonObject event = active.getAsJsonObject();
            state.setText("<html>LIVE: " + safe(str(event, "type")).toUpperCase() + "</html>");
            question.setText("<html>" + safe(str(event, "question")) + "</html>");
            endsAt = Instant.parse(str(event, "endsAt"));
            activeEventId = str(event, "id");
            if (!activeEventId.equals(lastShownEventId)) {
                lastShownEventId = activeEventId;
                attemptedEventId = null;
                feedback.setText("New event! Choose one answer.");
            }
            JsonArray options = event.getAsJsonArray("choices");
            for (int i = 0; i < choices.length; i++) {
                String answer = i < options.size() ? options.get(i).getAsString() : "--";
                choices[i].setText("<html>" + (char)('A' + i) + ". " + safe(answer) + "</html>");
                choices[i].setToolTipText(answer);
            }
        } else {
            endsAt = null;
            activeEventId = null;
            if (last != null && last.isJsonObject()) {
                JsonObject previous = last.getAsJsonObject();
                String eventType = str(previous, "type");
                String eventStatus = str(previous, "status");
                String winner = str(previous, "winner");
                if ("won".equals(eventStatus) && !winner.isEmpty()) {
                    state.setText("<html>WINNER: " + safe(winner) + "</html>");
                    question.setText("<html>Won the " + safe(eventType).toUpperCase(java.util.Locale.ROOT) + " event!</html>");
                } else if ("expired".equals(eventStatus)) {
                    state.setText("Event expired - no winner");
                    question.setText("<html>Last event: " + safe(eventType) + "</html>");
                } else {
                    state.setText("No active event");
                    question.setText("<html>Last event: " + safe(eventType) + "</html>");
                }
            } else {
                state.setText("No active event");
                question.setText("No previous events");
            }
            for (int i = 0; i < choices.length; i++) choices[i].setText((char)('A' + i) + ". --");
        }
        JsonElement next = data.get("nextAvailableAt");
        nextAvailable = next == null || next.isJsonNull() ? null : Instant.parse(next.getAsString());
        JsonArray top = data.getAsJsonArray("leaderboard");
        StringBuilder out = new StringBuilder("<html>");
        if (top == null || top.size() == 0) out.append("No winners yet");
        else for (int i = 0; i < Math.min(5, top.size()); i++) {
            JsonObject p = top.get(i).getAsJsonObject();
            out.append(i + 1).append(". ").append(safe(str(p, "player_name")))
                    .append(" — ").append(safe(str(p, "wins"))).append(" wins<br>");
        }
        leaderboard.setText(out.append("</html>").toString());
        updateClocks();
        updateButtons();
    }
    private void updateClocks() {
        if (endsAt != null) {
            long s = Math.max(0, endsAt.getEpochSecond() - Instant.now().getEpochSecond());
            countdown.setText("Time remaining: " + s + " seconds");
        } else countdown.setText("");
        if (nextAvailable == null || !Instant.now().isBefore(nextAvailable))
            cooldown.setText("Cooldown: ready");
        else {
            long s = nextAvailable.getEpochSecond() - Instant.now().getEpochSecond();
            cooldown.setText(String.format("Cooldown: %02d:%02d remaining", s / 60, s % 60));
        }
        updateButtons();
    }
}
