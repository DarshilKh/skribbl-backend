package com.skribbl.model;

import lombok.Data;

@Data
public class Player {
    private String id;
    private String name;
    private int score;
    private boolean hasGuessedCorrectly;
    private int guessOrder;

    public Player(String id, String name) {
        this.id = id;
        this.name = name;
        this.score = 0;
        this.hasGuessedCorrectly = false;
        this.guessOrder = 0;
    }

    public void addScore(int points) {
        this.score += points;
    }

    public void resetGuess() {
        this.hasGuessedCorrectly = false;
        this.guessOrder = 0;
    }
}