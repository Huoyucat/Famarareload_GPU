package com.wenkrang.famara.render.output;

import com.wenkrang.famara.render.asset.Texture;

import java.util.*;

/**
 * 与平台解耦的调色板。保留槽0–3不能被普通不透明照片选中。
 */
public final class MapPaletteSnapshot {
    public record Entry(int index, int rgb) {
        public Entry {
            if (index < 4 || index > 255 || (rgb & 0xff000000) != 0)
                throw new IllegalArgumentException("非法地图调色板项");
        }
    }

    private final List<Entry> entries;
    private final double[] red, green, blue;
    private final String version;

    public MapPaletteSnapshot(String version, List<Entry> input) {
        this.version = Objects.requireNonNull(version);
        entries = input.stream().sorted(Comparator.comparingInt(Entry::index)).toList();
        if (entries.isEmpty() || entries.size() > 252) throw new IllegalArgumentException("调色板必须包含有效颜色");
        Set<Integer> seen = new HashSet<>();
        red = new double[entries.size()];
        green = new double[entries.size()];
        blue = new double[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (!seen.add(e.index)) throw new IllegalArgumentException("重复地图索引");
            red[i] = Texture.linear((e.rgb >> 16) & 255);
            green[i] = Texture.linear((e.rgb >> 8) & 255);
            blue[i] = Texture.linear(e.rgb & 255);
        }
    }

    public String version() {
        return version;
    }

    public List<Entry> entries() {
        return entries;
    }

    public byte nearest(int rgb) {
        if ((rgb & 0xff000000) != 0) throw new IllegalArgumentException("需要24位RGB");
        double r = Texture.linear((rgb >> 16) & 255), g = Texture.linear((rgb >> 8) & 255), b = Texture.linear(rgb & 255);
        int best = 0;
        double cost = Double.POSITIVE_INFINITY;
        for (int i = 0; i < entries.size(); i++) {
            double dr = r - red[i], dg = g - green[i], db = b - blue[i], value = dr * dr + dg * dg + db * db;
            if (value < cost) {
                cost = value;
                best = i;
            }
        }
        return (byte) entries.get(best).index;
    }

    public int color(byte index) {
        int unsigned = index & 255;
        return entries.stream().filter(e -> e.index == unsigned).findFirst().orElseThrow(() -> new IllegalArgumentException("未知调色板索引")).rgb;
    }

    public byte[] quantize(int[] rgb) {
        if (rgb.length != 128 * 128) throw new IllegalArgumentException("地图必须是128×128");
        Map<Integer, Byte> cache = new HashMap<>();
        byte[] result = new byte[rgb.length];
        for (int i = 0; i < rgb.length; i++) result[i] = cache.computeIfAbsent(rgb[i], this::nearest);
        return result;
    }
}
