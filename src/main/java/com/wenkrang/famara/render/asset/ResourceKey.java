package com.wenkrang.famara.render.asset;

import java.util.Objects;

/**
 * Minecraft 资源标识，不是操作系统路径。
 */
public record ResourceKey(String namespace, String path) {
    public ResourceKey {
        Objects.requireNonNull(namespace);
        Objects.requireNonNull(path);
        if (!namespace.matches("[a-z0-9_.-]+") || !path.matches("[a-z0-9_./-]+"))
            throw new IllegalArgumentException("非法资源标识：" + namespace + ":" + path);
        for (String part : path.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new IllegalArgumentException("资源路径包含非法分段：" + path);
    }

    public static ResourceKey parse(String value) {
        int colon = value.indexOf(':');
        return colon < 0 ? new ResourceKey("minecraft", value)
                : new ResourceKey(value.substring(0, colon), value.substring(colon + 1));
    }

    public String file(String category, String suffix) {
        return "assets/" + namespace + "/" + category + "/" + path + suffix;
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
