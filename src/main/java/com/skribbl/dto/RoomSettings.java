package com.skribbl.dto;

import lombok.Data;

@Data
public class RoomSettings {
    private int maxPlayers = 8;
    private int rounds = 3;
    private int drawTime = 80;
    private int wordCount = 3;
    private int maxHints = 3;
    private boolean isPrivate = false;
}