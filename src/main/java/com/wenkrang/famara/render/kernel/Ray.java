package com.wenkrang.famara.render.kernel;

import com.wenkrang.famara.render.math.Vec3;

/**
 * t 使用世界方块距离；构造时统一方向长度。
 */
public record Ray(Vec3 origin, Vec3 direction) {
    public Ray {
        direction = direction.normalized();
    }
}
