package com.skribbl.service;

import com.skribbl.model.*;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RoomService {

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToRoom = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToPlayer = new ConcurrentHashMap<>();
    private final GameService gameService;

    public RoomService(GameService gameService) {
        this.gameService = gameService;
    }

    public Room createRoom(String hostName, WebSocketSession session, boolean isPrivate,
                           int maxPlayers, int rounds, int drawTime, int wordCount, int maxHints) {
        String roomId = generateRoomId();
        String playerId = UUID.randomUUID().toString();

        Room room = new Room(roomId, playerId, isPrivate);
        room.setMaxPlayers(Math.max(2, Math.min(20, maxPlayers)));
        room.setRounds(Math.max(1, Math.min(10, rounds)));
        room.setDrawTime(Math.max(15, Math.min(240, drawTime)));
        room.setWordCount(Math.max(1, Math.min(5, wordCount)));
        room.setMaxHints(Math.max(0, Math.min(5, maxHints)));

        Player host = new Player(playerId, hostName);
        room.addPlayer(playerId, host, session);

        rooms.put(roomId, room);
        sessionToRoom.put(session.getId(), roomId);
        sessionToPlayer.put(session.getId(), playerId);

        return room;
    }

    public Room joinRoom(String roomId, String playerName, WebSocketSession session) {
        Room room = rooms.get(roomId);
        if (room == null) return null;
        if (room.isFull()) return null;

        // FIXED: Prevent joining during active game
        if (room.getGameState().getPhase() != GamePhase.LOBBY) return null;

        String playerId = UUID.randomUUID().toString();
        Player player = new Player(playerId, playerName);
        room.addPlayer(playerId, player, session);

        sessionToRoom.put(session.getId(), roomId);
        sessionToPlayer.put(session.getId(), playerId);

        return room;
    }

    public Room getRoom(String roomId) {
        return rooms.get(roomId);
    }

    public String getRoomIdBySession(String sessionId) {
        return sessionToRoom.get(sessionId);
    }

    public String getPlayerIdBySession(String sessionId) {
        return sessionToPlayer.get(sessionId);
    }

    public void handleDisconnect(WebSocketSession session) {
        String sessionId = session.getId();
        String roomId = sessionToRoom.remove(sessionId);
        String playerId = sessionToPlayer.remove(sessionId);

        if (roomId == null || playerId == null) return;

        Room room = rooms.get(roomId);
        if (room == null) return;

        Player leavingPlayer = room.getPlayers().get(playerId);
        String leavingName = leavingPlayer != null ? leavingPlayer.getName() : "Unknown";

        room.removePlayer(playerId);

        if (room.getPlayers().isEmpty()) {
            rooms.remove(roomId);
            gameService.cleanupRoom(roomId);
            return;
        }

        if (playerId.equals(room.getHostId())) {
            String newHostId = room.getPlayers().keySet().iterator().next();
            room.setHostId(newHostId);
        }

        GameState state = room.getGameState();

        Map<String, Object> leavePayload = new LinkedHashMap<>();
        leavePayload.put("playerId", playerId);
        leavePayload.put("playerName", leavingName);
        leavePayload.put("players", room.getPlayerList());
        leavePayload.put("hostId", room.getHostId());
        gameService.sendToAll(room, "player_left", leavePayload);

        if (state.getPhase() == GamePhase.DRAWING || state.getPhase() == GamePhase.WORD_SELECTION) {
            if (playerId.equals(state.getCurrentDrawerId())) {
                gameService.endRound(room, false);
            } else if (room.getPlayers().size() < 2) {
                gameService.endRound(room, false);
            }
        }
    }

    public List<Map<String, Object>> getPublicRooms() {
        List<Map<String, Object>> publicRooms = new ArrayList<>();
        for (Room room : rooms.values()) {
            if (!room.isPrivate() && room.getGameState().getPhase() == GamePhase.LOBBY && !room.isFull()) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", room.getId());
                info.put("playerCount", room.getPlayers().size());
                info.put("maxPlayers", room.getMaxPlayers());
                info.put("rounds", room.getRounds());
                info.put("drawTime", room.getDrawTime());
                publicRooms.add(info);
            }
        }
        return publicRooms;
    }

    private String generateRoomId() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        String id = sb.toString();
        return rooms.containsKey(id) ? generateRoomId() : id;
    }
}