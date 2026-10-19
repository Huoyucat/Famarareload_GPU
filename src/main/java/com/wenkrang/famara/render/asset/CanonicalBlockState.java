package com.wenkrang.famara.render.asset;

import java.util.*;

/**
 * 不依赖 Bukkit 的规范状态，属性次序不参与身份。
 */
public record CanonicalBlockState(ResourceKey block, SortedMap<String, String> properties) {
    public CanonicalBlockState {
        Objects.requireNonNull(block);
        TreeMap<String, String> copy = new TreeMap<>(properties);
        copy.forEach((k, v) -> {
            if (!k.matches("[a-z0-9_]+") || !v.matches("[a-z0-9_.-]+"))
                throw new IllegalArgumentException("非法方块属性：" + k + "=" + v);
        });
        properties = Collections.unmodifiableSortedMap(copy);
    }

    public static CanonicalBlockState parse(String value) {
        if(value.startsWith("Block{")) {
            int end=value.indexOf('}');if(end<6)throw new IllegalArgumentException("方块状态包装不完整");
            value=value.substring(6,end)+value.substring(end+1);
        }
        int start = value.indexOf('[');
        if (start < 0) return new CanonicalBlockState(ResourceKey.parse(value), new TreeMap<>());
        if (!value.endsWith("]")) throw new IllegalArgumentException("方块状态缺少闭括号");
        TreeMap<String, String> props = new TreeMap<>();
        String body = value.substring(start + 1, value.length() - 1);
        if (!body.isEmpty()) for (String entry : body.split(",", -1)) {
            String[] pair = entry.split("=", -1);
            if (pair.length != 2 || props.putIfAbsent(pair[0], pair[1]) != null)
                throw new IllegalArgumentException("重复或非法方块属性：" + entry);
        }
        return new CanonicalBlockState(ResourceKey.parse(value.substring(0, start)), props);
    }

    @Override
    public String toString() {
        if (properties.isEmpty()) return block.toString();
        return block + "[" + String.join(",", properties.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).toList()) + "]";
    }
}
