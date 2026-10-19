package com.wenkrang.famara.render.platform;

import com.wenkrang.famara.render.output.MapPaletteSnapshot;
import org.bukkit.map.MapPalette;

import java.util.*;
import java.nio.ByteBuffer;
import java.security.*;

/**
 * 平台边界只复制当前 API 的颜色表；量化内核不调用 Bukkit。
 */
public final class MapPaletteAdapter {
    private MapPaletteAdapter() {
    }

    @SuppressWarnings("deprecation")
    public static MapPaletteSnapshot snapshot() {
        List<MapPaletteSnapshot.Entry> entries = new ArrayList<>();
        for (int index = 4; index < 256; index++) {
            try {
                java.awt.Color color = MapPalette.getColor((byte) index);
                entries.add(new MapPaletteSnapshot.Entry(index, color.getRGB() & 0xffffff));
            } catch (IllegalArgumentException | IndexOutOfBoundsException ignored) {
                // 老 API 的尾部索引不存在，不补造现代调色板项。
            }
        }
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (var entry : entries)
                hash.update(ByteBuffer.allocate(8).putInt(entry.index()).putInt(entry.rgb()).array());
            return new MapPaletteSnapshot("bukkit-snapshot-" + HexFormat.of().formatHex(hash.digest()), entries);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
