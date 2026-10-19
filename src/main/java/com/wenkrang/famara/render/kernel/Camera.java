package com.wenkrang.famara.render.kernel;

import com.wenkrang.famara.render.math.Vec3;

public record Camera(Vec3 origin, double yaw, double pitch, Projection projection, double horizontalFov) {
    public enum Projection {LEGACY_ANGULAR, PERSPECTIVE}

    public static final int SIZE = 128;

    public Camera {
        if (origin == null || projection == null || !Double.isFinite(yaw) || !Double.isFinite(pitch)
                || !Double.isFinite(horizontalFov) || horizontalFov <= 0 || horizontalFov >= Math.PI)
            throw new IllegalArgumentException("相机参数不合法");
    }

    public Ray ray(int x, int y, double offsetX, double offsetY) {
        if (x < 0 || x >= SIZE || y < 0 || y >= SIZE || !(offsetX >= 0 && offsetX < 1) || !(offsetY >= 0 && offsetY < 1))
            throw new IllegalArgumentException("像素或样本位置不合法");
        if (projection == Projection.LEGACY_ANGULAR) {
            double angleX = yaw + (x + offsetX - .5 - 64) / 128.0;
            double angleY = pitch - (y + offsetY - .5 - 64) / 128.0;
            return new Ray(origin, new Vec3(Math.cos(angleX) * Math.cos(angleY), Math.sin(angleY), Math.sin(angleX) * Math.cos(angleY)));
        }
        Vec3 forward = new Vec3(Math.cos(yaw) * Math.cos(pitch), Math.sin(pitch), Math.sin(yaw) * Math.cos(pitch));
        Vec3 right = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 up = right.cross(forward).normalized();
        double sx = 2 * (x + offsetX) / SIZE - 1, sy = 1 - 2 * (y + offsetY) / SIZE;
        double tan = Math.tan(horizontalFov / 2);
        return new Ray(origin, forward.add(right.multiply(sx * tan)).add(up.multiply(sy * tan)));
    }
}
