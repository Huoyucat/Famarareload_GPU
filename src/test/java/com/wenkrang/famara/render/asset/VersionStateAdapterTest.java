package com.wenkrang.famara.render.asset;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VersionStateAdapterTest {
    @Test
    void legacyChainAliasPreservesAllPropertiesForNewResources() {
        var adapter = new VersionStateAdapter(VersionStateAdapter.Version.parse("1.21.4"), VersionStateAdapter.Version.parse("26.3"));
        var state = CanonicalBlockState.parse("minecraft:chain[waterlogged=true,axis=z]");
        assertEquals("minecraft:iron_chain[axis=z,waterlogged=true]", adapter.adapt(state, Set.of(ResourceKey.parse("minecraft:iron_chain"))).toString());
    }

    @Test
    void actualResourceOverrideWinsOverAnAlias() {
        var adapter = new VersionStateAdapter(VersionStateAdapter.Version.parse("1.21.4"), VersionStateAdapter.Version.parse("26.3"));
        var state = CanonicalBlockState.parse("minecraft:chain[axis=x]");
        assertEquals(state, adapter.adapt(state, Set.of(state.block(), ResourceKey.parse("minecraft:iron_chain"))));
    }

    @Test
    void aliasesDoNotRewriteCustomNamespacesOrFutureRuntimeStates() {
        var old = new VersionStateAdapter(VersionStateAdapter.Version.parse("1.21.4"), VersionStateAdapter.Version.parse("26.3"));
        assertThrows(AssetException.class, () -> old.adapt(CanonicalBlockState.parse("custom:chain"), Set.of(ResourceKey.parse("minecraft:iron_chain"))));
        var modern = new VersionStateAdapter(VersionStateAdapter.Version.parse("26.3"), VersionStateAdapter.Version.parse("26.3"));
        assertThrows(AssetException.class, () -> modern.adapt(CanonicalBlockState.parse("minecraft:chain"), Set.of(ResourceKey.parse("minecraft:iron_chain"))));
    }

    @Test
    void versionBoundsDoNotRaiseLegacyPluginMinimumImplicitly() {
        assertThrows(IllegalArgumentException.class, () -> new VersionStateAdapter(VersionStateAdapter.Version.parse("1.20.1"), VersionStateAdapter.Version.parse("26.3")));
        assertFalse(new VersionStateAdapter(VersionStateAdapter.Version.parse("26.4"),VersionStateAdapter.Version.parse("26.3")).auditedRuntime());
        assertTrue(new VersionStateAdapter(VersionStateAdapter.Version.parse("1.21"),VersionStateAdapter.Version.parse("26.3")).auditedRuntime());
        assertTrue(VersionStateAdapter.Version.parse("26.3").compareTo(VersionStateAdapter.Version.parse("1.21.11")) > 0);
    }
}
