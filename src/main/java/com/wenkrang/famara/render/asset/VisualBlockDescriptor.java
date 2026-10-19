package com.wenkrang.famara.render.asset;

import java.util.Objects;

/**
 * CE 逻辑身份与客户端可见状态分离；此对象不调用实时后端。
 */
public record VisualBlockDescriptor(Kind kind, ResourceKey logicalId, CanonicalBlockState visualState,
                                    long generation) {
    public enum Kind {VANILLA, CE}

    public VisualBlockDescriptor {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(logicalId);
        Objects.requireNonNull(visualState);
        if (generation < 0) throw new IllegalArgumentException("非法资源代际");
    }

    public static VisualBlockDescriptor vanilla(CanonicalBlockState state, long generation) {
        return new VisualBlockDescriptor(Kind.VANILLA, state.block(), state, generation);
    }
}
