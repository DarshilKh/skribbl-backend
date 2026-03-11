package com.skribbl.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class GameState {
    private GamePhase phase;
    private int currentRound;
    private int totalRounds;
    private String currentDrawerId;
    private String currentWord;
    private String currentHint;
    private int drawTime;
    private long roundStartTime;
    private long roundEndTime;
    private List<String> drawerOrder;
    private int currentDrawerIndex;
    private int hintsRevealed;
    private int maxHints;
    private int correctGuessCount;

    public GameState() {
        this.phase = GamePhase.LOBBY;
        this.currentRound = 0;
        this.totalRounds = 3;
        this.drawTime = 80;
        this.drawerOrder = new ArrayList<>();
        this.currentDrawerIndex = -1;
        this.hintsRevealed = 0;
        this.maxHints = 3;
        this.correctGuessCount = 0;
    }

    public String generateHint() {
        if (currentWord == null) return "";
        StringBuilder hint = new StringBuilder();
        for (int i = 0; i < currentWord.length(); i++) {
            if (currentWord.charAt(i) == ' ') {
                hint.append("  ");
            } else {
                hint.append("_ ");
            }
        }
        return hint.toString().trim();
    }

    public String generatePartialHint(int revealCount) {
        if (currentWord == null) return "";
        char[] hintChars = new char[currentWord.length()];
        boolean[] revealed = new boolean[currentWord.length()];

        for (int i = 0; i < currentWord.length(); i++) {
            if (currentWord.charAt(i) == ' ') {
                hintChars[i] = ' ';
                revealed[i] = true;
            } else {
                hintChars[i] = '_';
                revealed[i] = false;
            }
        }

        List<Integer> hiddenIndices = new ArrayList<>();
        for (int i = 0; i < currentWord.length(); i++) {
            if (!revealed[i]) {
                hiddenIndices.add(i);
            }
        }

        java.util.Collections.shuffle(hiddenIndices);
        int toReveal = Math.min(revealCount, hiddenIndices.size());
        for (int i = 0; i < toReveal; i++) {
            hintChars[hiddenIndices.get(i)] = currentWord.charAt(hiddenIndices.get(i));
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < hintChars.length; i++) {
            result.append(hintChars[i]);
            if (i < hintChars.length - 1) result.append(' ');
        }
        return result.toString();
    }
}