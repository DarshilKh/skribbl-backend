package com.skribbl.model;

import lombok.Data;
import java.util.List;

@Data
public class DrawAction {
    private String type;
    private double x;
    private double y;
    private String color;
    private int size;
    private List<Point> points;

    @Data
    public static class Point {
        private double x;
        private double y;
    }
}