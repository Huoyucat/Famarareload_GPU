package com.wenkrang.famara.render.math;

import java.util.Locale;

public enum Direction {
    DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);
    private final Vec3 vector;

    Direction(int x, int y, int z) {
        vector = new Vec3(x, y, z);
    }

    public Vec3 vector() {
        return vector;
    }

    public static Direction parse(String s) {
        return valueOf(s.toUpperCase(Locale.ROOT));
    }

    public static Direction closest(Vec3 v) {
        Direction best = NORTH;
        double score = -Double.MAX_VALUE;
        for (Direction d : values())
            if (d.vector.dot(v) > score) {
                best = d;
                score = d.vector.dot(v);
            }
        return best;
    }
}
