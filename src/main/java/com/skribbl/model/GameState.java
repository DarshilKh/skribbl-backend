package com.skribbl.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.Collections;
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

    // ── NEW: tracks which letter positions have been revealed ──
    private List<Integer> revealedHintIndices;

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
        this.revealedHintIndices = new ArrayList<>();
    }

    /**
     * Resets hint state for a new round/turn.
     * Called from GameService.sendWordSelection().
     */
    public void resetHintState() {
        this.hintsRevealed = 0;
        this.revealedHintIndices.clear();
    }

    /**
     * Generates the initial hint: all letters replaced with underscores,
     * spaces preserved.
     */
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

    /**
     * Reveals letters incrementally. Previously revealed letters are
     * always preserved — new letters are added on top.
     *
     * @param totalToReveal cumulative count of letters that should
     *                      be visible (e.g. 1 on first hint, 2 on second)
     */
    public String generatePartialHint(int totalToReveal) {
        if (currentWord == null) return "";

        // ── Find indices that are still hidden ────────────────
        List<Integer> hiddenIndices = new ArrayList<>();
        for (int i = 0; i < currentWord.length(); i++) {
            if (currentWord.charAt(i) != ' ' && !revealedHintIndices.contains(i)) {
                hiddenIndices.add(i);
            }
        }

        // ── Reveal NEW indices (on top of previously revealed) ─
        Collections.shuffle(hiddenIndices);
        int newToReveal = Math.min(
                totalToReveal - revealedHintIndices.size(),
                hiddenIndices.size()
        );
        for (int i = 0; i < Math.max(0, newToReveal); i++) {
            revealedHintIndices.add(hiddenIndices.get(i));
        }

        // ── Build hint string ──────────────────────────────────
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < currentWord.length(); i++) {
            if (currentWord.charAt(i) == ' ') {
                result.append(' ');
            } else if (revealedHintIndices.contains(i)) {
                result.append(currentWord.charAt(i));
            } else {
                result.append('_');
            }
            if (i < currentWord.length() - 1) result.append(' ');
        }
        return result.toString();
    }
}