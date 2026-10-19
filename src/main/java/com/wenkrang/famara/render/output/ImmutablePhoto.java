package com.wenkrang.famara.render.output;

import java.util.*;

/**
 * 输出所有权在构造时复制，消费者不能改写已发布照片。
 */
public final class ImmutablePhoto {
    private final int[] rgb;
    private final byte[] map;
    private final Map<String, String> metadata;

    public ImmutablePhoto(int[] rgb, byte[] map, Map<String, String> metadata) {
        if (rgb.length != 16384 || map.length != 16384) throw new IllegalArgumentException("照片必须为128×128");
        for (int value : rgb) if ((value & 0xff000000) != 0) throw new IllegalArgumentException("需要24位RGB");
        for (byte value : map)
            if ((value & 255) < 4) throw new IllegalArgumentException("不透明照片不能含保留透明索引");
        if (metadata.size() > 256) throw new IllegalArgumentException("照片元数据项过多");
        long characters = 0;
        for (var entry : metadata.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getKey().length() > 128)
                throw new IllegalArgumentException("照片元数据不合法");
            characters += entry.getKey().length() + entry.getValue().length();
            if (characters > 65536) throw new IllegalArgumentException("照片元数据过大");
        }
        this.rgb = rgb.clone();
        this.map = map.clone();
        this.metadata = Map.copyOf(metadata);
    }

    public int[] copyRgb() {
        return rgb.clone();
    }

    public byte[] copyMap() {
        return map.clone();
    }

    public Map<String, String> metadata() {
        return metadata;
    }
}
