package com.wenkrang.famara.compat;

import org.bukkit.DyeColor;
import org.bukkit.block.data.BlockData;

/** 旧 API 没有地图色接口时的少量动态材质语义；其他材质仍由实际贴图色表负责。 */
public final class LegacyBlockColors {
    private LegacyBlockColors() {}
    public static Integer color(BlockData state) {
        String material = state.getMaterial().name();
        if (material.endsWith("_BANNER")) {
            String dye = material.replace("_WALL_BANNER", "").replace("_BANNER", "");
            try { return DyeColor.valueOf(dye).getColor().asRGB(); }
            catch (IllegalArgumentException ignored) { return null; }
        }
        if (material.equals("REDSTONE_WIRE")) {
            int power = property(state.getAsString(), "power", 0);
            float value = Math.max(0, Math.min(15, power)) / 15.0f;
            float red = power == 0 ? 0.3f : value * 0.6f + 0.4f;
            float green = Math.max(0, value * value * 0.7f - 0.5f);
            float blue = Math.max(0, value * value * 0.6f - 0.7f);
            return (int) (red * 255) << 16 | (int) (green * 255) << 8 | (int) (blue * 255);
        }
        if (material.endsWith("MELON_STEM") || material.endsWith("PUMPKIN_STEM")) {
            int age = material.startsWith("ATTACHED_") ? 7 : property(state.getAsString(), "age", 7);
            age = Math.max(0, Math.min(7, age));
            return age * 32 << 16 | (255 - age * 8) << 8 | age * 4;
        }
        return null;
    }
    private static int property(String state, String key, int fallback) {
        int start = state.indexOf(key + "=");
        if (start < 0) return fallback;
        start += key.length() + 1;
        int end = start;
        while (end < state.length() && Character.isDigit(state.charAt(end))) end++;
        try { return Integer.parseInt(state.substring(start, end)); }
        catch (NumberFormatException e) { return fallback; }
    }
}
