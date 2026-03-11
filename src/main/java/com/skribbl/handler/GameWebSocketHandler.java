package com.skribbl.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skribbl.dto.WebSocketMessage;
import com.skribbl.model.*;
import com.skribbl.service.GameService;
import com.skribbl.service.RoomService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;

@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    private final RoomService roomService;
    private final GameService gameService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GameWebSocketHandler(RoomService roomService, GameService gameService) {
        this.roomService = roomService;
        this.gameService = gameService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Connection established; wait for messages
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        WebSocketMessage msg;
        try {
            msg = objectMapper.readValue(message.getPayload(), WebSocketMessage.class);
        } catch (Exception e) {
            sendError(session, "Invalid message format");
            return;
        }

        String type = msg.getType();
        Map<String, Object> payload = msg.getPayload() != null ? msg.getPayload() : new HashMap<>();

        switch (type) {
            case "create_room"     -> handleCreateRoom(session, payload);
            case "join_room"       -> handleJoinRoom(session, payload);
            case "leave_room"      -> handleLeaveRoom(session);
            case "start_game"      -> handleStartGame(session);
            case "word_chosen"     -> handleWordChosen(session, payload);
            case "draw_data"       -> handleDrawData(session, payload);
            case "canvas_clear"    -> handleCanvasClear(session);
            case "draw_undo"       -> handleDrawUndo(session);
            case "guess"           -> handleGuess(session, payload);
            case "chat"            -> handleChat(session, payload);
            case "get_public_rooms"-> handleGetPublicRooms(session);
            case "kick_player"     -> handleKickPlayer(session, payload);
            case "update_settings" -> handleUpdateSettings(session, payload);
            default                -> sendError(session, "Unknown message type: " + type);
        }
    }

    // ── Room lifecycle ───────────────────────────────────────────

    private void handleCreateRoom(WebSocketSession session, Map<String, Object> payload) throws IOException {
        String hostName = getStringOrDefault(payload, "hostName", "Host");
        boolean isPrivate = getBooleanOrDefault(payload, "isPrivate", false);
        int maxPlayers = getIntOrDefault(payload, "maxPlayers", 8);
        int rounds = getIntOrDefault(payload, "rounds", 3);
        int drawTime = getIntOrDefault(payload, "drawTime", 80);
        int wordCount = getIntOrDefault(payload, "wordCount", 3);
        int maxHints = getIntOrDefault(payload, "maxHints", 3);

        Room room = roomService.createRoom(
                hostName, session, isPrivate, maxPlayers, rounds, drawTime, wordCount, maxHints
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("roomId", room.getId());
        response.put("playerId", room.getHostId());
        response.put("players", room.getPlayerList());
        response.put("isHost", true);
        response.put("settings", getRoomSettings(room));
        sendMessage(session, "room_created", response);
    }

    private void handleJoinRoom(WebSocketSession session, Map<String, Object> payload) throws IOException {
        String roomId = getStringOrDefault(payload, "roomId", "").toUpperCase().trim();
        String playerName = getStringOrDefault(payload, "playerName", "Player");

        Room room = roomService.joinRoom(roomId, playerName, session);
        if (room == null) {
            sendError(session, "Room not found, is full, or game already started");
            return;
        }

        String playerId = roomService.getPlayerIdBySession(session.getId());

        Map<String, Object> joinResponse = new LinkedHashMap<>();
        joinResponse.put("roomId", room.getId());
        joinResponse.put("playerId", playerId);
        joinResponse.put("players", room.getPlayerList());
        joinResponse.put("isHost", playerId.equals(room.getHostId()));
        joinResponse.put("hostId", room.getHostId());
        joinResponse.put("settings", getRoomSettings(room));
        sendMessage(session, "room_joined", joinResponse);

        Player newPlayer = room.getPlayers().get(playerId);
        Map<String, Object> broadcast = new LinkedHashMap<>();
        broadcast.put("playerId", playerId);
        broadcast.put("playerName", newPlayer.getName());
        broadcast.put("players", room.getPlayerList());
        gameService.sendToAllExcept(room, playerId, "player_joined", broadcast);
    }

    /**
     * Explicit leave — the player chose to leave.
     * Delegates to the same cleanup path as an unexpected disconnect.
     * afterConnectionClosed will also fire when the socket closes,
     * so handleDisconnect MUST be idempotent.
     */
    private void handleLeaveRoom(WebSocketSession session) {
        roomService.handleDisconnect(session);
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.NORMAL);
            }
        } catch (IOException ignored) {}
    }

    // ── Game actions ─────────────────────────────────────────────

    private void handleStartGame(WebSocketSession session) {
        String roomId = roomService.getRoomIdBySession(session.getId());
        String playerId = roomService.getPlayerIdBySession(session.getId());
        if (roomId == null || playerId == null) return;

        Room room = roomService.getRoom(roomId);
        if (room == null) return;

        if (!playerId.equals(room.getHostId())) {
            sendError(session, "Only the host can start the game");
            return;
        }

        if (room.getPlayers().size() < 2) {
            sendError(session, "Need at least 2 players to start");
            return;
        }

        if (room.getGameState().getPhase() != GamePhase.LOBBY) {
            sendError(session, "Game already in progress");
            return;
        }

        Map<String, Object> startPayload = new LinkedHashMap<>();
        startPayload.put("message", "Game starting!");
        gameService.sendToAll(room, "game_starting", startPayload);
        gameService.startGame(room);
    }

    private void handleWordChosen(WebSocketSession session, Map<String, Object> payload) {
        String roomId = roomService.getRoomIdBySession(session.getId());
        String playerId = roomService.getPlayerIdBySession(session.getId());
        if (roomId == null || playerId == null) return;

        Room room = roomService.getRoom(roomId);
        if (room == null) return;

        String word = getStringOrDefault(payload, "word", "");
        gameService.handleWordChosen(room, playerId, word);
    }

    private void handleDrawData(WebSocketSession session, Map<String, Object> payload) {
        String roomId = roomService.getRoomIdBySession(session.getId());
        String playerId = roomService.getPlayerIdBySession(session.getId());
        if (roomId == null || playerId == null) return;

        Room room = roomService.getRoom(roomId);
        if (room == null) return;

        gameService.broadcastDrawData(room, playerId, payload);
    }

    private void handleCanvasClear(WebSocketSession session) {
        String roomId = roomService.getRoomIdBySession(session.getId());
        String playerId = roomService.getPlayerIdBySession(session.getId());
        if (roomId == null || playerId == null) return;

        Room room = roomService.getRoom(roomId);
        if (room == null) return;

        gameService.handleCanvasClear(room, playerId);
    }

    private void handleDrawUndo(WebSocketSession session) {
        String roomId = roomService.getRoomIdBySession(session.getId());
        String playerId = roomService.getPlayerIdBySession(session.getId());
        if (roomId == null || playerId == null) return;

        Room room = roomService.getRoom(roomId);
        if (room == null) return;

        gameService.handleUndo(room, playerId);
    }

    private void handleGuess(WebSocketSession session, Map<String, Object> payload) {
        String roomId = roomService.getRoomIdBySession(session.getId());
        String playerId = roomService.getPlayerIdBySession(session.getId());
        if (roomId == null || playerId == null) return;

        Room room = roomService.getRoom(roomId);
        if (room == null) return;

        String text = getStringOrDefault(payload, "text", "");
        if (text.isBlank()) return;

        gameService.handleGuess(room, playerId, text);
    }

    private void handleChat(WebSocketSession session, Map<String, Object> payload) {
        String roomId = roomService.getRoomIdBySession(session.getId());
        String playerId = roomService.getPlayerIdBySession(session.getId());
        if (roomId == null || playerId == null) return;

        Room room = roomService.getRoom(roomId);
        if (room == null) return;

        String text = getStringOrDefault(payload, "text", "");
        if (text.isBlank()) return;

        // ── Prevent drawer from leaking the word in chat ─────
        GameState state = room.getGameState();
        if (state.getPhase() == GamePhase.DRAWING
                && playerId.equals(state.getCurrentDrawerId())
                && state.getCurrentWord() != null
                && text.toLowerCase().contains(state.getCurrentWord().toLowerCase())) {
            sendError(session, "You can't type the word in chat!");
            return;
        }

        Player player = room.getPlayers().get(playerId);

        Map<String, Object> chatPayload = new LinkedHashMap<>();
        chatPayload.put("playerId", playerId);
        chatPayload.put("playerName", player != null ? player.getName() : "Unknown");
        chatPayload.put("text", text);
        chatPayload.put("isGuess", false);
        chatPayload.put("isClose", false);
        gameService.sendToAll(room, "chat_message", chatPayload);
    }

    // ── Room management ──────────────────────────────────────────

    private void handleGetPublicRooms(WebSocketSession session) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rooms", roomService.getPublicRooms());
        sendMessage(session, "public_rooms", payload);
    }

    private void handleKickPlayer(WebSocketSession session, Map<String, Object> payload) {
        String roomId = roomService.getRoomIdBySession(session.getId());
        String playerId = roomService.getPlayerIdBySession(session.getId());
        if (roomId == null || playerId == null) return;

        Room room = roomService.getRoom(roomId);
        if (room == null) return;

        if (!playerId.equals(room.getHostId())) {
            sendError(session, "Only host can kick players");
            return;
        }

        String targetId = getStringOrDefault(payload, "targetId", "");
        if (targetId.isEmpty() || targetId.equals(room.getHostId())) return;

        Player target = room.getPlayers().get(targetId);
        if (target == null) return;

        var targetSession = room.getSessions().get(targetId);
        if (targetSession != null && targetSession.isOpen()) {
            Map<String, Object> kickPayload = new LinkedHashMap<>();
            kickPayload.put("message", "You have been kicked from the room");
            sendMessage(targetSession, "kicked", kickPayload);
            try {
                targetSession.close();
            } catch (IOException ignored) {}
        }
    }

    private void handleUpdateSettings(WebSocketSession session, Map<String, Object> payload) {
        String roomId = roomService.getRoomIdBySession(session.getId());
        String playerId = roomService.getPlayerIdBySession(session.getId());
        if (roomId == null || playerId == null) return;

        Room room = roomService.getRoom(roomId);
        if (room == null) return;

        if (!playerId.equals(room.getHostId())) {
            sendError(session, "Only host can change settings");
            return;
        }

        if (room.getGameState().getPhase() != GamePhase.LOBBY) return;

        if (payload.containsKey("maxPlayers"))
            room.setMaxPlayers(clamp(getIntOrDefault(payload, "maxPlayers", 8), 2, 20));
        if (payload.containsKey("rounds"))
            room.setRounds(clamp(getIntOrDefault(payload, "rounds", 3), 1, 10));
        if (payload.containsKey("drawTime"))
            room.setDrawTime(clamp(getIntOrDefault(payload, "drawTime", 80), 15, 240));
        if (payload.containsKey("wordCount"))
            room.setWordCount(clamp(getIntOrDefault(payload, "wordCount", 3), 1, 5));
        if (payload.containsKey("maxHints"))
            room.setMaxHints(clamp(getIntOrDefault(payload, "maxHints", 3), 0, 5));

        Map<String, Object> settingsPayload = new LinkedHashMap<>();
        settingsPayload.put("settings", getRoomSettings(room));
        gameService.sendToAll(room, "settings_updated", settingsPayload);
    }

    // ── Connection lifecycle ─────────────────────────────────────

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // handleDisconnect is idempotent — safe if leave_room already called it
        roomService.handleDisconnect(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        roomService.handleDisconnect(session);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void sendMessage(WebSocketSession session, String type, Map<String, Object> payload) {
        if (session == null || !session.isOpen()) return;
        try {
            WebSocketMessage msg = new WebSocketMessage(type, payload);
            String json = objectMapper.writeValueAsString(msg);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            // Log instead of silently swallowing
            System.err.println("Failed to send message type=" + type + " : " + e.getMessage());
        }
    }

    private void sendError(WebSocketSession session, String errorMessage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", errorMessage);
        sendMessage(session, "error", payload);
    }

    private Map<String, Object> getRoomSettings(Room room) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("maxPlayers", room.getMaxPlayers());
        settings.put("rounds", room.getRounds());
        settings.put("drawTime", room.getDrawTime());
        settings.put("wordCount", room.getWordCount());
        settings.put("maxHints", room.getMaxHints());
        settings.put("isPrivate", room.isPrivate());
        return settings;
    }

    /** Safe extraction — avoids ClassCastException from Jackson's type mapping */
    private String getStringOrDefault(Map<String, Object> map, String key, String fallback) {
        Object val = map.get(key);
        return val instanceof String s ? s : fallback;
    }

    private int getIntOrDefault(Map<String, Object> map, String key, int fallback) {
        Object val = map.get(key);
        return val instanceof Number n ? n.intValue() : fallback;
    }

    private boolean getBooleanOrDefault(Map<String, Object> map, String key, boolean fallback) {
        Object val = map.get(key);
        return val instanceof Boolean b ? b : fallback;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}