package com.skribbl.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skribbl.dto.WebSocketMessage;
import com.skribbl.model.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

@Service
public class GameService {

    private final WordService wordService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(10);

    // All timer maps — every scheduled task is tracked and cancellable
    private final Map<String, ScheduledFuture<?>> roomTimers = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> hintTimers = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> countdownTimers = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> transitionTimers = new ConcurrentHashMap<>();

    public GameService(WordService wordService) {
        this.wordService = wordService;
    }

    @PreDestroy
    public void shutdown() {
        roomTimers.values().forEach(f -> f.cancel(true));
        hintTimers.values().forEach(f -> f.cancel(true));
        countdownTimers.values().forEach(f -> f.cancel(true));
        transitionTimers.values().forEach(f -> f.cancel(true));
        roomTimers.clear();
        hintTimers.clear();
        countdownTimers.clear();
        transitionTimers.clear();

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ── Game lifecycle ───────────────────────────────────────────

    public void startGame(Room room) {
        GameState state = room.getGameState();
        state.setPhase(GamePhase.WORD_SELECTION);
        state.setCurrentRound(1);
        state.setTotalRounds(room.getRounds());
        state.setDrawTime(room.getDrawTime());
        state.setMaxHints(room.getMaxHints());

        List<String> order = new ArrayList<>(room.getPlayers().keySet());
        Collections.shuffle(order);
        state.setDrawerOrder(order);
        state.setCurrentDrawerIndex(0);
        state.setCurrentDrawerId(order.get(0));

        for (Player p : room.getPlayers().values()) {
            p.setScore(0);
            p.resetGuess();
        }

        room.clearDrawHistory();
        sendWordSelection(room);
    }

    private void sendWordSelection(Room room) {
        GameState state = room.getGameState();
        state.setPhase(GamePhase.WORD_SELECTION);
        state.setCorrectGuessCount(0);
        state.resetHintState(); // ← uses new method instead of just setHintsRevealed(0)

        for (Player p : room.getPlayers().values()) {
            p.resetGuess();
        }

        room.clearDrawHistory();

        List<String> wordOptions = wordService.getRandomWords(room.getWordCount());
        String drawerId = state.getCurrentDrawerId();

        Map<String, Object> drawerPayload = new LinkedHashMap<>();
        drawerPayload.put("words", wordOptions);
        drawerPayload.put("drawTime", state.getDrawTime());
        drawerPayload.put("round", state.getCurrentRound());
        drawerPayload.put("totalRounds", state.getTotalRounds());
        sendToPlayer(room, drawerId, "word_selection", drawerPayload);

        Map<String, Object> othersPayload = new LinkedHashMap<>();
        othersPayload.put("drawerId", drawerId);
        Player drawer = room.getPlayers().get(drawerId);
        othersPayload.put("drawerName", drawer != null ? drawer.getName() : "Unknown");
        othersPayload.put("round", state.getCurrentRound());
        othersPayload.put("totalRounds", state.getTotalRounds());
        sendToAllExcept(room, drawerId, "choosing_word", othersPayload);

        // Auto-select after 15 seconds if drawer hasn't chosen
        ScheduledFuture<?> autoSelect = scheduler.schedule(() -> {
            if (state.getPhase() == GamePhase.WORD_SELECTION) {
                List<String> fallback = wordService.getRandomWords(1);
                handleWordChosen(room, drawerId, fallback.get(0));
            }
        }, 15, TimeUnit.SECONDS);
        roomTimers.put(room.getId() + "_wordselect", autoSelect);
    }

    public void handleWordChosen(Room room, String playerId, String word) {
        GameState state = room.getGameState();
        if (!playerId.equals(state.getCurrentDrawerId())) return;
        if (state.getPhase() != GamePhase.WORD_SELECTION) return;

        cancelTimer(room.getId() + "_wordselect");

        state.setCurrentWord(word.toLowerCase().trim());
        state.setCurrentHint(state.generateHint());
        state.setPhase(GamePhase.DRAWING);
        state.setRoundStartTime(System.currentTimeMillis());
        state.setRoundEndTime(System.currentTimeMillis() + (state.getDrawTime() * 1000L));

        // Tell the drawer
        Map<String, Object> drawerPayload = new LinkedHashMap<>();
        drawerPayload.put("word", state.getCurrentWord());
        drawerPayload.put("drawTime", state.getDrawTime());
        drawerPayload.put("round", state.getCurrentRound());
        drawerPayload.put("totalRounds", state.getTotalRounds());
        drawerPayload.put("hint", state.getCurrentWord().replaceAll("[^ ]", "_ ").trim());
        sendToPlayer(room, state.getCurrentDrawerId(), "round_start_drawer", drawerPayload);

        // Tell everyone else
        Map<String, Object> guesserPayload = new LinkedHashMap<>();
        guesserPayload.put("hint", state.getCurrentHint());
        guesserPayload.put("drawTime", state.getDrawTime());
        guesserPayload.put("drawerId", state.getCurrentDrawerId());
        Player drawer = room.getPlayers().get(state.getCurrentDrawerId());
        guesserPayload.put("drawerName", drawer != null ? drawer.getName() : "Unknown");
        guesserPayload.put("round", state.getCurrentRound());
        guesserPayload.put("totalRounds", state.getTotalRounds());
        guesserPayload.put("wordLength", state.getCurrentWord().length());
        sendToAllExcept(room, state.getCurrentDrawerId(), "round_start_guesser", guesserPayload);

        startRoundTimer(room);
        startHintTimer(room);
        startCountdownBroadcast(room);
    }

    // ── Timers ───────────────────────────────────────────────────

    private void startRoundTimer(Room room) {
        cancelTimer(room.getId() + "_round");
        GameState state = room.getGameState();
        ScheduledFuture<?> timer = scheduler.schedule(
                () -> endRound(room, false),
                state.getDrawTime(),
                TimeUnit.SECONDS
        );
        roomTimers.put(room.getId() + "_round", timer);
    }

    private void startCountdownBroadcast(Room room) {
        cancelTimer(room.getId() + "_countdown");
        GameState state = room.getGameState();
        ScheduledFuture<?> timer = scheduler.scheduleAtFixedRate(() -> {
            if (state.getPhase() != GamePhase.DRAWING) return;
            long remaining = (state.getRoundEndTime() - System.currentTimeMillis()) / 1000;
            if (remaining < 0) remaining = 0;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("timeLeft", remaining);
            sendToAll(room, "timer_update", payload);
        }, 1, 1, TimeUnit.SECONDS);
        countdownTimers.put(room.getId() + "_countdown", timer);
    }

    private void startHintTimer(Room room) {
        cancelTimer(room.getId() + "_hint");
        GameState state = room.getGameState();

        if (state.getMaxHints() <= 0) return;

        int interval = state.getDrawTime() / (state.getMaxHints() + 1);
        if (interval < 5) interval = 5;

        ScheduledFuture<?> timer = scheduler.scheduleAtFixedRate(() -> {
            if (state.getPhase() != GamePhase.DRAWING) return;
            if (state.getHintsRevealed() >= state.getMaxHints()) return;

            state.setHintsRevealed(state.getHintsRevealed() + 1);
            String hint = state.generatePartialHint(state.getHintsRevealed());
            state.setCurrentHint(hint);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("hint", hint);
            sendToAllExcept(room, state.getCurrentDrawerId(), "hint_update", payload);
        }, interval, interval, TimeUnit.SECONDS);
        hintTimers.put(room.getId() + "_hint", timer);
    }

    // ── Guessing ─────────────────────────────────────────────────

    public void handleGuess(Room room, String playerId, String guess) {
        GameState state = room.getGameState();
        if (state.getPhase() != GamePhase.DRAWING) return;
        if (playerId.equals(state.getCurrentDrawerId())) return;

        Player player = room.getPlayers().get(playerId);
        if (player == null || player.isHasGuessedCorrectly()) return;

        String normalizedGuess = guess.trim().toLowerCase();
        String currentWord = state.getCurrentWord().toLowerCase();

        if (normalizedGuess.equals(currentWord)) {
            // ── Correct guess ────────────────────────────────────
            player.setHasGuessedCorrectly(true);
            state.setCorrectGuessCount(state.getCorrectGuessCount() + 1);
            player.setGuessOrder(state.getCorrectGuessCount());

            long elapsed = System.currentTimeMillis() - state.getRoundStartTime();
            long totalTime = state.getDrawTime() * 1000L;
            double timeRatio = Math.max(0, 1.0 - ((double) elapsed / totalTime));
            int basePoints = (int) (500 * timeRatio) + 100;
            int orderBonus = Math.max(0, 200 - (state.getCorrectGuessCount() - 1) * 50);
            int points = basePoints + orderBonus;
            player.addScore(points);

            Player drawer = room.getPlayers().get(state.getCurrentDrawerId());
            if (drawer != null) {
                drawer.addScore(50 + (int) (100 * timeRatio));
            }

            Map<String, Object> correctPayload = new LinkedHashMap<>();
            correctPayload.put("playerId", playerId);
            correctPayload.put("playerName", player.getName());
            correctPayload.put("points", points);
            correctPayload.put("players", room.getPlayerList());
            sendToAll(room, "correct_guess", correctPayload);

            // Check if everyone has guessed
            long nonDrawerCount = room.getPlayers().entrySet().stream()
                    .filter(e -> !e.getKey().equals(state.getCurrentDrawerId()))
                    .count();

            if (state.getCorrectGuessCount() >= nonDrawerCount) {
                endRound(room, true);
            }
        } else {
            // ── Wrong guess ──────────────────────────────────────
            boolean isClose = isCloseGuess(normalizedGuess, currentWord);

            Map<String, Object> chatPayload = new LinkedHashMap<>();
            chatPayload.put("playerId", playerId);
            chatPayload.put("playerName", player.getName());
            chatPayload.put("text", guess);
            chatPayload.put("isGuess", true);
            chatPayload.put("isClose", isClose);
            sendToAll(room, "chat_message", chatPayload);

            if (isClose) {
                Map<String, Object> closePayload = new LinkedHashMap<>();
                closePayload.put("message", "'" + guess + "' is close!");
                sendToPlayer(room, playerId, "close_guess", closePayload);
            }
        }
    }

    private boolean isCloseGuess(String guess, String word) {
        if (guess.length() < 2 || word.length() < 2) return false;
        int distance = levenshteinDistance(guess, word);
        return distance == 1 || distance == 2;
    }

    private int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }
        return dp[a.length()][b.length()];
    }

    // ── Round / Game end ─────────────────────────────────────────

    /**
     * Ends the current round.
     * Synchronized per-room via the room object to prevent concurrent
     * timer + disconnect from double-ending the same round, while
     * allowing different rooms to proceed independently.
     */
    public void endRound(Room room, boolean allGuessed) {
        synchronized (room) {
            GameState state = room.getGameState();
            if (state.getPhase() == GamePhase.ROUND_END ||
                    state.getPhase() == GamePhase.GAME_OVER) {
                return;
            }

            state.setPhase(GamePhase.ROUND_END);
            cancelTimer(room.getId() + "_round");
            cancelTimer(room.getId() + "_hint");
            cancelTimer(room.getId() + "_countdown");

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("word", state.getCurrentWord());
            payload.put("players", room.getPlayerList());
            payload.put("allGuessed", allGuessed);
            payload.put("round", state.getCurrentRound());
            payload.put("totalRounds", state.getTotalRounds());
            sendToAll(room, "round_end", payload);

            // Track the transition timer so it can be cancelled on cleanup
            ScheduledFuture<?> transition = scheduler.schedule(
                    () -> nextTurn(room), 5, TimeUnit.SECONDS
            );
            transitionTimers.put(room.getId() + "_transition", transition);
        }
    }

    private void nextTurn(Room room) {
        synchronized (room) {
            // ── Guard: if players left during the 5s wait ────────
            if (room.getPlayers().size() < 2) {
                endGame(room);
                return;
            }

            GameState state = room.getGameState();
            int nextIndex = state.getCurrentDrawerIndex() + 1;

            if (nextIndex >= state.getDrawerOrder().size()) {
                if (state.getCurrentRound() >= state.getTotalRounds()) {
                    endGame(room);
                    return;
                }
                state.setCurrentRound(state.getCurrentRound() + 1);
                nextIndex = 0;
            }

            // Skip disconnected players
            List<String> order = state.getDrawerOrder();
            while (nextIndex < order.size() &&
                    !room.getPlayers().containsKey(order.get(nextIndex))) {
                nextIndex++;
            }

            if (nextIndex >= order.size()) {
                if (state.getCurrentRound() >= state.getTotalRounds()) {
                    endGame(room);
                    return;
                }
                state.setCurrentRound(state.getCurrentRound() + 1);
                nextIndex = 0;
                while (nextIndex < order.size() &&
                        !room.getPlayers().containsKey(order.get(nextIndex))) {
                    nextIndex++;
                }
                if (nextIndex >= order.size()) {
                    endGame(room);
                    return;
                }
            }

            state.setCurrentDrawerIndex(nextIndex);
            state.setCurrentDrawerId(order.get(nextIndex));
            room.clearDrawHistory();

            sendWordSelection(room);
        }
    }

    private void endGame(Room room) {
        synchronized (room) {
            GameState state = room.getGameState();

            // Prevent double-end
            if (state.getPhase() == GamePhase.GAME_OVER) return;

            state.setPhase(GamePhase.GAME_OVER);

            cancelTimer(room.getId() + "_round");
            cancelTimer(room.getId() + "_hint");
            cancelTimer(room.getId() + "_countdown");
            cancelTimer(room.getId() + "_transition");

            List<Map<String, Object>> leaderboard = new ArrayList<>(room.getPlayerList());
            leaderboard.sort((a, b) -> ((int) b.get("score")) - ((int) a.get("score")));

            String winnerName = leaderboard.isEmpty()
                    ? "Nobody"
                    : (String) leaderboard.get(0).get("name");
            String winnerId = leaderboard.isEmpty()
                    ? null
                    : (String) leaderboard.get(0).get("id");

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("winner", winnerName);
            payload.put("winnerId", winnerId);
            payload.put("leaderboard", leaderboard);
            sendToAll(room, "game_over", payload);

            // Return to lobby after 10 seconds — tracked for cleanup
            ScheduledFuture<?> returnTimer = scheduler.schedule(() -> {
                synchronized (room) {
                    // Room may have been cleaned up during the wait
                    if (room.getPlayers().isEmpty()) return;

                    state.setPhase(GamePhase.LOBBY);
                    state.setCurrentRound(0);
                    state.setCurrentDrawerIndex(-1);
                    state.setCurrentDrawerId(null);
                    state.setCurrentWord(null);
                    state.setCurrentHint(null);
                    state.resetHintState();
                    for (Player p : room.getPlayers().values()) {
                        p.setScore(0);
                        p.resetGuess();
                    }
                    room.clearDrawHistory();

                    Map<String, Object> lobbyPayload = new LinkedHashMap<>();
                    lobbyPayload.put("players", room.getPlayerList());
                    lobbyPayload.put("hostId", room.getHostId());
                    sendToAll(room, "return_to_lobby", lobbyPayload);
                }
            }, 10, TimeUnit.SECONDS);
            transitionTimers.put(room.getId() + "_returnlobby", returnTimer);
        }
    }

    // ── Drawing ──────────────────────────────────────────────────

    public void broadcastDrawData(Room room, String playerId, Map<String, Object> drawData) {
        GameState state = room.getGameState();
        if (!playerId.equals(state.getCurrentDrawerId())) return;
        if (state.getPhase() != GamePhase.DRAWING) return;

        List<Map<String, Object>> history = room.getDrawHistory();
        synchronized (history) {
            history.add(drawData);
        }

        Map<String, Object> payload = new LinkedHashMap<>(drawData);
        payload.put("playerId", playerId);
        sendToAllExcept(room, playerId, "draw_data", payload);
    }

    public void handleCanvasClear(Room room, String playerId) {
        GameState state = room.getGameState();
        if (!playerId.equals(state.getCurrentDrawerId())) return;
        room.clearDrawHistory();
        sendToAllExcept(room, playerId, "canvas_clear", new LinkedHashMap<>());
    }

    public void handleUndo(Room room, String playerId) {
        GameState state = room.getGameState();
        if (!playerId.equals(state.getCurrentDrawerId())) return;

        List<Map<String, Object>> history = room.getDrawHistory();

        // ── Synchronize the entire read-modify cycle ─────────
        synchronized (history) {
            if (history.isEmpty()) return;

            // Walk backward to find the last complete stroke
            // (draw_start → draw_move* → draw_end)
            for (int i = history.size() - 1; i >= 0; i--) {
                String type = (String) history.get(i).get("type");
                if ("draw_end".equals(type)) {
                    // Find the matching draw_start
                    int start = i;
                    while (start > 0) {
                        start--;
                        String t = (String) history.get(start).get("type");
                        if ("draw_start".equals(t)) break;
                    }

                    int end = i + 1;
                    if (start >= 0 && start < end && end <= history.size()) {
                        history.subList(start, end).clear();
                    }
                    break;
                }
            }
        }

        // Build payload from a snapshot
        Map<String, Object> payload = new LinkedHashMap<>();
        synchronized (history) {
            payload.put("drawHistory", new ArrayList<>(history));
        }
        sendToAllExcept(room, playerId, "draw_undo", payload);
    }

    // ── Messaging ────────────────────────────────────────────────

    public void sendToAll(Room room, String type, Map<String, Object> payload) {
        WebSocketMessage msg = new WebSocketMessage(type, payload);
        String json;
        try {
            json = objectMapper.writeValueAsString(msg);
        } catch (IOException e) {
            System.err.println("Failed to serialize " + type + ": " + e.getMessage());
            return;
        }
        for (WebSocketSession session : room.getSessions().values()) {
            sendRaw(session, json);
        }
    }

    public void sendToAllExcept(Room room, String excludeId, String type,
                                Map<String, Object> payload) {
        WebSocketMessage msg = new WebSocketMessage(type, payload);
        String json;
        try {
            json = objectMapper.writeValueAsString(msg);
        } catch (IOException e) {
            System.err.println("Failed to serialize " + type + ": " + e.getMessage());
            return;
        }
        for (Map.Entry<String, WebSocketSession> entry : room.getSessions().entrySet()) {
            if (!entry.getKey().equals(excludeId)) {
                sendRaw(entry.getValue(), json);
            }
        }
    }

    public void sendToPlayer(Room room, String playerId, String type,
                             Map<String, Object> payload) {
        WebSocketSession session = room.getSessions().get(playerId);
        if (session == null || !session.isOpen()) return;
        try {
            WebSocketMessage msg = new WebSocketMessage(type, payload);
            sendRaw(session, objectMapper.writeValueAsString(msg));
        } catch (IOException e) {
            System.err.println("Failed to send " + type + " to " + playerId + ": " + e.getMessage());
        }
    }

    private void sendRaw(WebSocketSession session, String json) {
        if (session == null || !session.isOpen()) return;
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            System.err.println("Failed to send to session: " + e.getMessage());
        }
    }

    // ── Timer management ─────────────────────────────────────────

    private void cancelTimer(String key) {
        ScheduledFuture<?> timer;

        timer = roomTimers.remove(key);
        if (timer != null) timer.cancel(false);

        timer = hintTimers.remove(key);
        if (timer != null) timer.cancel(false);

        timer = countdownTimers.remove(key);
        if (timer != null) timer.cancel(false);

        timer = transitionTimers.remove(key);
        if (timer != null) timer.cancel(false);
    }

    public void cleanupRoom(String roomId) {
        cancelTimer(roomId + "_round");
        cancelTimer(roomId + "_hint");
        cancelTimer(roomId + "_countdown");
        cancelTimer(roomId + "_wordselect");
        cancelTimer(roomId + "_transition");
        cancelTimer(roomId + "_returnlobby");
    }
}