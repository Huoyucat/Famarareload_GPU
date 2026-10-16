package com.wenkrang.famara.lib;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public record Translation(YamlConfiguration ref) {
    public static Translation CURRENT = new Translation(null);
    @NotNull
    public String of(@NotNull String key) {
        if (Objects.isNull(ref)) {
            return key;
        } else {
            return Objects.requireNonNullElse(ref.getString(key), key);
        }
    }

    /**
     * 获取翻译，旧版 language.yml 中没有该键时使用默认值
     */
    @NotNull
    public String of(@NotNull String key, @NotNull String def) {
        if (Objects.isNull(ref)) return def;
        return Objects.requireNonNullElse(ref.getString(key), def);
    }

    public static void setCurrent(YamlConfiguration ref) {
        CURRENT = new Translation(ref);
    }
}
