package com.wenkrang.famara.render.asset;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

class AssetCompilerTest {
    @TempDir
    Path directory;

    private RenderFixtures cube() throws Exception {
        RenderFixtures f = new RenderFixtures();
        f.png("test:tile", 2, 2, 0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff);
        f.cube("test:block/shape", "test:tile");
        f.block("test:block", "test:block/shape");
        return f;
    }

    @Test
    void resourceKeysAndStatesAreCanonicalAndRejectEscapes() {
        assertEquals("minecraft:block/stone", ResourceKey.parse("block/stone").toString());
        for (String value : List.of("../stone", "a//b", "/absolute", "test:../../x", "Test:a", "x:"))
            assertThrows(IllegalArgumentException.class, () -> ResourceKey.parse(value), value);
        assertEquals(CanonicalBlockState.parse("test:block[b=2,a=1]"), CanonicalBlockState.parse("test:block[a=1,b=2]"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalBlockState.parse("test:block[a=1,a=2]"));
        assertThrows(UnsupportedOperationException.class, () -> CanonicalBlockState.parse("test:block[a=1]").properties().clear());
    }

    @Test
    void inputsAndByteAccessorsCannotMutatePack() throws Exception {
        RenderFixtures f = cube();
        ResourcePack pack = f.pack();
        String path = "assets/test/textures/tile.png";
        byte original = pack.require(path).copyBytes()[0];
        f.files.get(path)[0] = 0;
        byte[] copy = pack.require(path).copyBytes();
        copy[0] = 1;
        assertEquals(original, pack.require(path).copyBytes()[0]);
        assertEquals(6, new ModelCompiler(pack, RenderFixtures.LIMITS).compile(ResourceKey.parse("test:block/shape")).quads().size());
    }

    @Test
    void inheritanceAndChildVariablesCompileVisibleGeometry() throws Exception {
        RenderFixtures f = cube();
        f.png("test:child", 1, 1, 0xff123456);
        f.json("assets/test/models/block/child.json", "{\"parent\":\"test:block/shape\",\"textures\":{\"all\":\"test:child\"}}");
        f.block("test:block", "test:block/child");
        AssetGeneration g = f.generation(1);
        BakedModel m = g.resolve(RenderFixtures.vanilla("test:block", 1), 0, 0, 0, 1).getFirst();
        assertEquals(6, m.quads().size());
        assertTrue(m.quads().stream().allMatch(q -> q.texture().equals(ResourceKey.parse("test:child"))));
        assertThrows(UnsupportedOperationException.class, () -> m.quads().clear());
    }

    @Test
    void parentAndTextureCyclesAreReported() throws Exception {
        RenderFixtures f = cube();
        f.json("assets/test/models/block/a.json", "{\"parent\":\"test:block/b\"}");
        f.json("assets/test/models/block/b.json", "{\"parent\":\"test:block/a\"}");
        assertEquals(AssetException.Code.INVALID, assertThrows(AssetException.class, () -> new ModelCompiler(f.pack(), RenderFixtures.LIMITS)
                .compile(ResourceKey.parse("test:block/a"))).code());
        f.json("assets/test/models/block/child.json", "{\"parent\":\"test:block/shape\",\"textures\":{\"all\":\"#again\",\"again\":\"#all\"}}");
        assertEquals(AssetException.Code.INVALID, assertThrows(AssetException.class, () -> new ModelCompiler(f.pack(), RenderFixtures.LIMITS)
                .compile(ResourceKey.parse("test:block/child"))).code());
    }

    @Test
    void duplicateJsonAndUnknownVisualFieldsAreNotSilentlyAccepted() throws Exception {
        RenderFixtures f = cube();
        f.json("assets/test/models/block/shape.json", "{\"elements\":[],\"elements\":[]}");
        assertEquals(AssetException.Code.INVALID, assertThrows(AssetException.class, () -> new ModelCompiler(f.pack(), RenderFixtures.LIMITS)
                .compile(ResourceKey.parse("test:block/shape"))).code());
        f.json("assets/test/models/block/shape.json", "{\"elements\":[],\"unexpected_visual_extension\":true}");
        assertEquals(AssetException.Code.UNSUPPORTED, assertThrows(AssetException.class, () -> new ModelCompiler(f.pack(), RenderFixtures.LIMITS)
                .compile(ResourceKey.parse("test:block/shape"))).code());
    }

    @Test
    void variantsUseSpecificPropertiesAndStableWeightedChoices() throws Exception {
        RenderFixtures f = cube();
        f.cube("test:block/other", "test:tile");
        f.json("assets/test/blockstates/block.json", """
                {"variants":{"":{"model":"test:block/shape"},"facing=north":[
                {"model":"test:block/other","weight":3},{"model":"test:block/shape","weight":1}]}}
                """);
        AssetGeneration g = f.generation(1);
        Set<ResourceKey> chosen = new HashSet<>();
        for (int x = -50; x < 50; x++) {
            var descriptor = RenderFixtures.vanilla("test:block[waterlogged=false,facing=north]", 1);
            var first = g.resolve(descriptor, x, 2, 3, 22);
            assertEquals(first, g.resolve(descriptor, x, 2, 3, 22));
            chosen.add(first.getFirst().key());
        }
        assertEquals(2, chosen.size());
        assertEquals(ResourceKey.parse("test:block/shape"), g.resolve(RenderFixtures.vanilla("test:block[facing=south]", 1), 0, 0, 0, 1).getFirst().key());
    }

    @Test
    void multipartKeepsAllMatchingPartsAndNestedConditions() throws Exception {
        RenderFixtures f = cube();
        f.json("assets/test/blockstates/block.json", """
                {"multipart":[{"apply":{"model":"test:block/shape"}},
                {"when":{"OR":[{"north":"true|low"},{"AND":[{"south":"true"},{"wet":"false"}]}]},
                 "apply":{"model":"test:block/shape","y":90,"uvlock":true}}]}
                """);
        AssetGeneration g = f.generation(1);
        assertEquals(2, g.resolve(RenderFixtures.vanilla("test:block[north=low]", 1), 0, 0, 0, 0).size());
        assertEquals(1, g.resolve(RenderFixtures.vanilla("test:block[north=false,south=true,wet=true]", 1), 0, 0, 0, 0).size());
    }

    @Test
    void transparentHighPriorityTextureIsNotReplacedByOpaqueLowerLayer() throws Exception {
        RenderFixtures low = cube(), high = new RenderFixtures();
        high.png("test:tile", 1, 1, 0x00123456);
        ResourcePack layered = ResourcePack.layer(List.of(high.pack(), low.pack()), RenderFixtures.LIMITS);
        Texture texture = Texture.decode(layered, ResourceKey.parse("test:tile"), RenderFixtures.LIMITS);
        assertEquals(0, texture.sample(.5, .5, 0, 0, Texture.Filter.NEAREST).a());
        assertFalse(texture.opaque());
    }

    @Test
    void brokenHighPriorityTextureFailsInsteadOfFallingThrough() throws Exception {
        RenderFixtures low = cube(), high = new RenderFixtures();
        high.files.put("assets/test/textures/tile.png", new byte[]{1, 2, 3});
        ResourcePack layered = ResourcePack.layer(List.of(high.pack(), low.pack()), RenderFixtures.LIMITS);
        assertEquals(AssetException.Code.INVALID, assertThrows(AssetException.class,
                () -> Texture.decode(layered, ResourceKey.parse("test:tile"), RenderFixtures.LIMITS)).code());
    }

    @Test
    void newerSpriteBindingPreservesForceTranslucent() throws Exception {
        RenderFixtures f = cube();
        f.json("assets/test/models/block/child.json", """
                {"parent":"test:block/shape","textures":{"all":{"sprite":"test:tile","force_translucent":true}}}
                """);
        var m = new ModelCompiler(f.pack(), RenderFixtures.LIMITS).compile(ResourceKey.parse("test:block/child"));
        assertTrue(m.quads().stream().allMatch(BakedQuad::forceTranslucent));
    }

    @Test
    void emptyGenericModelsAreValidButSpecialBlocksNeedTheirRealResources() throws Exception {
        RenderFixtures f = cube();
        f.json("assets/test/models/block/shape.json", "{\"textures\":{\"particle\":\"test:tile\"}}");
        f.cube("test:block/healthy", "test:tile");
        f.block("test:healthy", "test:block/healthy");
        f.block("minecraft:copper_golem_statue","test:block/shape");
        AssetGeneration g=f.generation(1);
        assertTrue(g.resolve(RenderFixtures.vanilla("test:block",1),0,0,0,0).getFirst().quads().isEmpty());
        assertFalse(g.issues().isEmpty());
        assertEquals(AssetException.Code.MISSING,assertThrows(AssetException.class,
                ()->g.resolve(RenderFixtures.vanilla("minecraft:copper_golem_statue",1),0,0,0,0)).code());
        assertEquals(AssetException.Code.MISSING, assertThrows(AssetException.class,
                () -> g.resolve(RenderFixtures.vanilla("test:absent", 1), 0, 0, 0, 0)).code());
    }

    @Test
    void animationUsesConfiguredFirstIndexAndFrameDuration() throws Exception {
        RenderFixtures f = new RenderFixtures();
        f.png("test:animated", 1, 2, 0xffff0000, 0xff0000ff);
        f.json("assets/test/textures/animated.png.mcmeta", "{\"animation\":{\"frames\":[{\"index\":1,\"time\":2},0]}}");
        Texture t = Texture.decode(f.pack(), ResourceKey.parse("test:animated"), RenderFixtures.LIMITS);
        assertEquals(2, t.frameCount());
        assertEquals(1, t.sample(.5, .5, 0, 0, Texture.Filter.NEAREST).b(), 1e-6);
        assertEquals(1, t.sample(.5, .5, 0, 2, Texture.Filter.NEAREST).r(), 1e-6);
        assertEquals(t.sample(.5, .5, 0, 0, Texture.Filter.NEAREST), t.sample(.5, .5, 0, 3, Texture.Filter.NEAREST));
    }

    @Test
    void bilinearFiltersPremultipliedColorWithoutBlackHalo() throws Exception {
        RenderFixtures f = new RenderFixtures();
        f.png("test:edge", 2, 1, 0xffff0000, 0x00000000);
        Texture t = Texture.decode(f.pack(), ResourceKey.parse("test:edge"), RenderFixtures.LIMITS);
        Texture.Sample s = t.sample(.5, .5, 0, 0, Texture.Filter.BILINEAR);
        assertEquals(.5, s.a(), 1e-6);
        assertEquals(.5, s.r(), 1e-6);
        assertEquals(0, s.b());
    }

    @Test
    void imageDimensionsAndEntryBudgetsFailBeforeUnboundedAllocation() throws Exception {
        RenderFixtures f = cube();
        AssetLimits small = new AssetLimits(100, 2000000, 64000000, 1, 10, 10, 10, 10, 1000, 4);
        assertEquals(AssetException.Code.LIMIT, assertThrows(AssetException.class,
                () -> Texture.decode(f.pack(), ResourceKey.parse("test:tile"), small)).code());
        assertThrows(AssetException.class, () -> ResourcePack.memory("tiny", Map.of("large", new byte[20]),
                new AssetLimits(1, 10, 20, 1, 1, 1, 1, 1, 1, 1)));
    }

    @Test
    void duplicateDirectoryAndZipInputsHaveTheSameVisibleContents() throws Exception {
        RenderFixtures f = cube();
        Path folder = directory.resolve("folder");
        Files.createDirectories(folder);
        Path archive = directory.resolve("test.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (var e : f.files.entrySet()) {
                Path file = folder.resolve(e.getKey());
                Files.createDirectories(file.getParent());
                Files.write(file, e.getValue());
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        ResourcePack a = ResourcePack.load(folder, RenderFixtures.LIMITS), b = ResourcePack.load(archive, RenderFixtures.LIMITS);
        assertEquals(a.paths(), b.paths());
        for (String path : a.paths()) assertArrayEquals(a.require(path).copyBytes(), b.require(path).copyBytes());
    }

    @Test
    void unsafeZipPathIsRejected() throws Exception {
        Path archive = directory.resolve("unsafe.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            out.putNextEntry(new ZipEntry("../escape"));
            out.write(1);
            out.closeEntry();
        }
        assertEquals(AssetException.Code.INVALID, assertThrows(AssetException.class,
                () -> ResourcePack.load(archive, RenderFixtures.LIMITS)).code());
    }

    @Test
    void oldGenerationsLiveUntilTheLastLeaseCloses() throws Exception {
        RenderFixtures f = cube();
        AssetRepository repository = new AssetRepository(2);
        long one = repository.requestBuild();
        assertTrue(repository.publish(one, f.generation(one)));
        AssetRepository.Lease old = repository.acquire();
        long two = repository.requestBuild();
        assertTrue(repository.publish(two, f.generation(two)));
        assertEquals(1, old.generation().id());
        assertEquals(2, repository.retainedGenerations());
        assertFalse(repository.publish(one, f.generation(one)));
        old.close();
        old.close();
        assertEquals(1, repository.retainedGenerations());
        AssetRepository.Lease active = repository.acquire();
        repository.close();
        assertEquals(2, active.generation().id());
        assertThrows(IllegalStateException.class, repository::acquire);
        active.close();
        assertEquals(0, repository.retainedGenerations());
    }

    @Test
    void retainedGenerationLimitDoesNotEvictLiveLease() throws Exception {
        RenderFixtures f = cube();
        AssetRepository r = new AssetRepository(1);
        long a = r.requestBuild();
        assertTrue(r.publish(a, f.generation(a)));
        var lease = r.acquire();
        long b = r.requestBuild();
        assertFalse(r.publish(b, f.generation(b)));
        lease.close();
        assertTrue(r.publish(b, f.generation(b)));
        r.close();
    }

    @Test
    void ceLogicalIdResolvesActualVisualStateAndRejectsAnotherGeneration() throws Exception {
        RenderFixtures f = cube();
        f.block("minecraft:note_block", "test:block/shape");
        AssetGeneration g = f.generation(4);
        var d = new VisualBlockDescriptor(VisualBlockDescriptor.Kind.CE, ResourceKey.parse("ce:unrelated_id"),
                CanonicalBlockState.parse("minecraft:note_block[note=2]"), 4);
        assertEquals(ResourceKey.parse("test:block/shape"), g.resolve(d, 0, 0, 0, 0).getFirst().key());
        var wrong = new VisualBlockDescriptor(d.kind(), d.logicalId(), d.visualState(), 5);
        assertEquals(AssetException.Code.GENERATION_MISMATCH, assertThrows(AssetException.class, () -> g.resolve(wrong, 0, 0, 0, 0)).code());
    }

    @Test
    void latestMetadataExpandedRotationAndShadeDirectionArePreserved() throws Exception {
        RenderFixtures f = cube();
        f.json("assets/test/models/block/new.json", """
                {"__comment":"fixture","textures":{"all":"test:tile"},"elements":[{
                "name":"rotated","__comment":"not a visual field","shade_direction_override":"up",
                "from":[0,0,0],"to":[16,16,16],
                "rotation":{"axis":"y","angle":-67.5,"origin":[8,8,8]},
                "faces":{"north":{"texture":"#all"}}}]}
                """);
        BakedQuad q = new ModelCompiler(f.pack(), RenderFixtures.LIMITS).compile(ResourceKey.parse("test:block/new")).quads().getFirst();
        assertEquals(com.wenkrang.famara.render.math.Direction.UP, q.shadeDirectionOverride());
        assertEquals(Math.sin(Math.toRadians(67.5)), q.normal().x(), 1e-9);
        assertNotEquals(0, q.positions().getFirst().z());
    }

    @Test
    void failedBuildDoesNotReplaceTheLastValidGeneration() throws Exception {
        RenderFixtures f = cube();
        AssetRepository r = new AssetRepository(2);
        long first = r.requestBuild();
        assertTrue(r.publish(first, f.generation(first)));
        long next = r.requestBuild();
        f.files.remove("assets/test/models/block/shape.json");
        assertThrows(AssetException.class, () -> f.generation(next));
        try (var lease = r.acquire()) {
            assertEquals(first, lease.generation().id());
        }
        r.close();
    }

    @Test
    void bareFaceSlotNamesResolveVariablesRatherThanGuessingPngPaths() throws Exception {
        RenderFixtures f = cube();
        String path = "assets/test/models/block/shape.json";
        f.json(path, new String(f.files.get(path), java.nio.charset.StandardCharsets.UTF_8).replace("#all", "all"));
        var model = new ModelCompiler(f.pack(), RenderFixtures.LIMITS).compile(ResourceKey.parse("test:block/shape"));
        assertTrue(model.quads().stream().allMatch(q -> q.texture().equals(ResourceKey.parse("test:tile"))));
    }

    @Test
    void nearMipBaseRetainsNearestPixelTexture() throws Exception {
        RenderFixtures f = cube();
        Texture t = Texture.decode(f.pack(), ResourceKey.parse("test:tile"), RenderFixtures.LIMITS);
        assertEquals(t.sample(.49, .25, 0, 0, Texture.Filter.NEAREST), t.sample(.49, .25, 0, 0, Texture.Filter.MIPMAP));
        assertNotEquals(t.sample(.49, .25, 0, 0, Texture.Filter.BILINEAR), t.sample(.49, .25, 0, 0, Texture.Filter.MIPMAP));
    }

    @Test
    void directoryMutationDuringBuildIsDetectedDeterministically() throws Exception {
        Path folder = directory.resolve("mutable");
        Files.createDirectories(folder);
        Path file = folder.resolve("pack.mcmeta");
        Files.writeString(file, "{}");
        var error = assertThrows(AssetException.class, () -> ResourcePack.load(folder, RenderFixtures.LIMITS, () -> {
            try {
                Files.writeString(file, "{\"changed\":true}");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }));
        assertEquals(AssetException.Code.SOURCE_CHANGED, error.code());
    }

    @Test
    void compressedEntryCannotExceedItsDeclaredReadBudget() throws Exception {
        Path archive = directory.resolve("large-entry.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            out.putNextEntry(new ZipEntry("assets/test/large.json"));
            out.write(new byte[8192]);
            out.closeEntry();
        }
        AssetLimits small = new AssetLimits(100, 1024, 64000000, 100, 10, 10, 10, 10, 1000, 4);
        assertEquals(AssetException.Code.LIMIT, assertThrows(AssetException.class, () -> ResourcePack.load(archive, small)).code());
    }

    @Test
    void jsonAndParentDepthAreExplicitlyLimited() throws Exception {
        AssetLimits small = new AssetLimits(100, 2000000, 64000000, 1000000, 100, 10000, 2, 4, 1000, 16);
        String nested = "{}";
        for (int i = 0; i < 10; i++) nested = "{\"a\":" + nested + "}";
        ResourcePack pack = ResourcePack.memory("nested", Map.of("nested.json", nested.getBytes(java.nio.charset.StandardCharsets.UTF_8)), small);
        assertEquals(AssetException.Code.LIMIT, assertThrows(AssetException.class, () -> BoundedJson.read(pack.require("nested.json"), "nested.json", small)).code());
        RenderFixtures f = cube();
        f.json("assets/test/models/block/a.json", "{\"parent\":\"test:block/b\"}");
        f.json("assets/test/models/block/b.json", "{\"parent\":\"test:block/shape\"}");
        assertEquals(AssetException.Code.LIMIT, assertThrows(AssetException.class, () -> new ModelCompiler(f.pack(), small).compile(ResourceKey.parse("test:block/a"))).code());
    }

}
