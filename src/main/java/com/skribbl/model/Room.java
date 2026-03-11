package com.skribbl.model;

import lombok.Data;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Data
public class Room {
    private String id;
    private String hostId;
    private boolean isPrivate;
    private int maxPlayers;
    private int rounds;
    private int drawTime;
    private int wordCount;
    private int maxHints;
    private Map<String, Player> players;
    private Map<String, WebSocketSession> sessions;
    private GameState gameState;
    private List<Map<String, Object>> drawHistory;

    public Room(String id, String hostId, boolean isPrivate) {
        this.id = id;
        this.hostId = hostId;
        this.isPrivate = isPrivate;
        this.maxPlayers = 8;
        this.rounds = 3;
        this.drawTime = 80;
        this.wordCount = 3;
        this.maxHints = 3;
        this.players = new ConcurrentHashMap<>();
        this.sessions = new ConcurrentHashMap<>();
        this.gameState = new GameState();
        this.drawHistory = Collections.synchronizedList(new ArrayList<>());
    }

    public void addPlayer(String playerId, Player player, WebSocketSession session) {
        players.put(playerId, player);
        sessions.put(playerId, session);
    }

    public void removePlayer(String playerId) {
        players.remove(playerId);
        sessions.remove(playerId);
    }

    public boolean isFull() {
        return players.size() >= maxPlayers;
    }

    public List<Map<String, Object>> getPlayerList() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Player> entry : players.entrySet()) {
            Map<String, Object> playerInfo = new LinkedHashMap<>();
            playerInfo.put("id", entry.getValue().getId());
            playerInfo.put("name", entry.getValue().getName());
            playerInfo.put("score", entry.getValue().getScore());
            playerInfo.put("isHost", entry.getKey().equals(hostId));
            playerInfo.put("hasGuessedCorrectly", entry.getValue().isHasGuessedCorrectly());
            list.add(playerInfo);
        }
        return list;
    }

    public void clearDrawHistory() {
        drawHistory.clear();
    }
}