package com.wenkrang.famara.render.asset;

import java.util.*;
import java.util.regex.*;

/**
 * 运行版本与资源版本独立。只转换已知版本范围内的原版别名，不改 CE 自定义命名空间。
 */
public final class VersionStateAdapter {
    public record Version(int major, int minor, int patch) implements Comparable<Version> {
        private static final Pattern FORMAT = Pattern.compile("([0-9]+)[.]([0-9]+)(?:[.]([0-9]+))?(?:[-+].*)?");

        public Version {
            if (major < 0 || minor < 0 || patch < 0) throw new IllegalArgumentException("版本不能为负");
        }

        public static Version parse(String text) {
            Matcher match = FORMAT.matcher(text);
            if (!match.matches()) throw new IllegalArgumentException("无法识别Minecraft版本：" + text);
            return new Version(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), match.group(3) == null ? 0 : Integer.parseInt(match.group(3)));
        }

        @Override
        public int compareTo(Version v) {
            int a = Integer.compare(major, v.major);
            if (a != 0) return a;
            a = Integer.compare(minor, v.minor);
            return a != 0 ? a : Integer.compare(patch, v.patch);
        }
    }

    private static final Version CHAIN_RENAME = new Version(1, 21, 9);
    private final Version runtime, resources;

    public VersionStateAdapter(Version runtime, Version resources) {
        this.runtime = Objects.requireNonNull(runtime);
        this.resources = Objects.requireNonNull(resources);
        if (!(runtime.major()==1 && runtime.minor()==21 || runtime.major()==26))
            throw new IllegalArgumentException("V2目标为Paper/Folia 1.21.x与26.x；当前版本不在目标系列");
    }
    public boolean auditedRuntime(){return runtime.major()==1 && runtime.minor()==21 && runtime.patch()<=11 || runtime.major()==26 && (runtime.minor()==1 && runtime.patch()<=2 || runtime.minor()>=2 && runtime.minor()<=3 && runtime.patch()==0);}
    public CanonicalBlockState adapt(CanonicalBlockState input,AssetGeneration actual) {
        CanonicalBlockState named=adapt(input,actual.blockKeys());if(actual.matchesState(named))return named;
        if(named.block().toString().equals("minecraft:creaking_heart") && resources.compareTo(new Version(1,21,5))>=0 && named.properties().containsKey("active") && !named.properties().containsKey("creaking_heart_state")) {
            String active=named.properties().get("active");if(!active.equals("true") && !active.equals("false"))return named;
            var properties=new TreeMap<>(named.properties());properties.remove("active");properties.put("creaking_heart_state",active.equals("true")?"awake":"uprooted");
            CanonicalBlockState migrated=new CanonicalBlockState(named.block(),properties);if(actual.matchesState(migrated))return migrated;
        }
        if(named.block().toString().equals("minecraft:creaking_heart") && resources.compareTo(new Version(1,21,5))>=0 && named.properties().containsKey("creaking") && !named.properties().containsKey("creaking_heart_state")) {
            String old=named.properties().get("creaking");String target=switch(old){case "disabled"->"uprooted";case "active","dormant"->"awake";default->null;};
            // 1.21.2/3的dormant也使用旧active模型，不能按相同单词猜成新版dormant外观。
            if(target!=null){var properties=new TreeMap<>(named.properties());properties.remove("creaking");properties.put("creaking_heart_state",target);var migrated=new CanonicalBlockState(named.block(),properties);if(actual.matchesState(migrated))return migrated;}
        }
        return named;
    }

    public CanonicalBlockState adapt(CanonicalBlockState input, Set<ResourceKey> actualDefinitions) {
        if (actualDefinitions.contains(input.block())) return input;
        if (input.block().namespace().equals("minecraft") && input.block().path().equals("chain")
                && runtime.compareTo(CHAIN_RENAME) < 0 && resources.compareTo(CHAIN_RENAME) >= 0) {
            ResourceKey replacement = ResourceKey.parse("minecraft:iron_chain");
            if (actualDefinitions.contains(replacement))
                return new CanonicalBlockState(replacement, input.properties());
        }
        throw new AssetException(AssetException.Code.MISSING, input.toString(), "没有原始定义或适用的版本别名");
    }
}
