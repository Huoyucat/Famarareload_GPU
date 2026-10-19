package com.wenkrang.famara.render.math;

public record Vec3(double x, double y, double z) {
    public Vec3 {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("向量必须有限");
    }

    public Vec3 add(Vec3 b) {
        return new Vec3(x + b.x, y + b.y, z + b.z);
    }

    public Vec3 subtract(Vec3 b) {
        return new Vec3(x - b.x, y - b.y, z - b.z);
    }

    public Vec3 multiply(double s) {
        return new Vec3(x * s, y * s, z * s);
    }

    public double dot(Vec3 b) {
        return x * b.x + y * b.y + z * b.z;
    }

    public Vec3 cross(Vec3 b) {
        return new Vec3(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x);
    }

    public Vec3 normalized() {
        double scale = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
        if (scale == 0) throw new IllegalArgumentException("不能归一化零向量");
        double sx = x / scale, sy = y / scale, sz = z / scale, length = Math.sqrt(sx * sx + sy * sy + sz * sz);
        return new Vec3(sx / length, sy / length, sz / length);
    }

    public double component(int axis) {
        return switch (axis) {
            case 0 -> x;
            case 1 -> y;
            case 2 -> z;
            default -> throw new IllegalArgumentException("非法轴");
        };
    }

    public Vec3 rotate(int axis, double radians) {
        double c = Math.cos(radians), s = Math.sin(radians);
        return switch (axis) {
            case 0 -> new Vec3(x, c * y - s * z, s * y + c * z);
            case 1 -> new Vec3(c * x + s * z, y, -s * x + c * z);
            case 2 -> new Vec3(c * x - s * y, s * x + c * y, z);
            default -> throw new IllegalArgumentException("非法旋转轴");
        };
    }
}
