package com.skribbl.dto;

import lombok.Data;
import java.util.Map;

@Data
public class WebSocketMessage {
    private String type;
    private Map<String, Object> payload;

    public WebSocketMessage() {}

    public WebSocketMessage(String type, Map<String, Object> payload) {
        this.type = type;
        this.payload = payload;
    }
}