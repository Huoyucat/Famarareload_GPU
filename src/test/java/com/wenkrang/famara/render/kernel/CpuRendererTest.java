package com.wenkrang.famara.render.kernel;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.math.*;
import com.wenkrang.famara.render.scene.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CpuRendererTest {
    private static final VoxelTracer.Limits TRACE = new VoxelTracer.Limits(10000, 1000, 10000);

    private RenderFixtures fixture() throws Exception {
        RenderFixtures f = new RenderFixtures();
        f.png("test:tile", 2, 2, 0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff);
        f.cube("test:block/model", "test:tile");
        f.block("test:block", "test:block/model");
        return f;
    }

    private SceneSnapshot.Builder builder(AssetGeneration g) {
        SceneSnapshot.Builder b = new SceneSnapshot.Builder(g, 0, 32, 100, 10000);
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++) for (int y = 0; y < 2; y++) b.availableSection(x, y, z);
        return b;
    }

    private SceneSnapshot scene(AssetGeneration g) {
        return builder(g).put(0, 0, 0, RenderFixtures.vanilla("test:block", g.id()), new SceneSnapshot.Lighting(15, 0, 0xffffff), 0).build();
    }

    private CpuPhotoRenderer renderer(SceneSnapshot scene, Map<ResourceKey, CpuPhotoRenderer.AlphaMode> modes) {
        return new CpuPhotoRenderer(scene, new Camera(new Vec3(.5, .5, -2), Math.PI / 2, 0, Camera.Projection.PERSPECTIVE, Math.PI / 3),
                new CpuPhotoRenderer.Settings(8, 1, Texture.Filter.NEAREST, false, 0, .5, 20, 0, 0, TRACE, modes));
    }

    private static Ray ray(double x, double y, double z, double dx, double dy, double dz) {
        return new Ray(new Vec3(x, y, z), new Vec3(dx, dy, dz));
    }

    @Test
    void oneBlockHasDifferentColorsAtDifferentUvCoordinates() throws Exception {
        var r = renderer(scene(fixture().generation(1)), Map.of());
        Texture.Sample left = r.sampleRay(ray(.75, .75, -2, 0, 0, 1), () -> false);
        Texture.Sample right = r.sampleRay(ray(.25, .75, -2, 0, 0, 1), () -> false);
        assertEquals(1, left.r(), 1e-6);
        assertEquals(0, left.g(), 1e-6);
        assertEquals(0, right.r(), 1e-6);
        assertEquals(1, right.g(), 1e-6);
    }

    @Test
    void sixFacesHaveExpectedNormalAndDistances() throws Exception {
        VoxelTracer tracer = new VoxelTracer(scene(fixture().generation(1)), TRACE);
        for (Direction d : Direction.values()) {
            Vec3 normal = d.vector(), center = new Vec3(.5, .5, .5);
            var hits = tracer.trace(new Ray(center.add(normal.multiply(2)), normal.multiply(-1)), 4, () -> false);
            assertFalse(hits.isEmpty());
            assertEquals(1.5, hits.getFirst().t(), 1e-9);
            assertEquals(d, hits.getFirst().quad().face());
        }
    }

    @Test
    void originInsideBlockReturnsExitSurfaceAndNegativeCoordinatesWork() throws Exception {
        AssetGeneration g = fixture().generation(1);
        SceneSnapshot s = builder(g).put(-2, 0, -2, RenderFixtures.vanilla("test:block", 1), new SceneSnapshot.Lighting(15, 0, 0xffffff), 0).build();
        var hit = new VoxelTracer(s, TRACE).trace(ray(-1.5, .5, -1.5, 0, 0, -1), 8, () -> false).getFirst();
        assertEquals(.5, hit.t(), 1e-9);
        assertEquals(Direction.NORTH, hit.quad().face());
    }

    @Test
    void maximumDistanceIsInclusiveAndCornerTiesAreFinite() throws Exception {
        VoxelTracer tracer = new VoxelTracer(scene(fixture().generation(1)), TRACE);
        assertEquals(2, tracer.trace(ray(.5, .5, -2, 0, 0, 1), 2, () -> false).getFirst().t(), 1e-9);
        assertTrue(tracer.trace(ray(.5, .5, -2, 0, 0, 1), 1.999, () -> false).isEmpty());
        assertFalse(tracer.trace(ray(-1, 2, -1, 1, -1, 1), 8, () -> false).isEmpty());
    }

    @Test
    void missingSectionsAreNotTreatedAsAir() throws Exception {
        AssetGeneration g = fixture().generation(1);
        SceneSnapshot s = new SceneSnapshot.Builder(g, 0, 32, 10, 100).build();
        var e = assertThrows(SceneException.class, () -> new VoxelTracer(s, TRACE).trace(ray(0, .5, 0, 1, 0, 0), 8, () -> false));
        assertEquals(SceneException.Code.SCENE_INCOMPLETE, e.code());
    }

    @Test
    void outstretchedModelIsFoundOutsideItsHostAndNotCountedTwice() throws Exception {
        RenderFixtures f = fixture();
        String text = new String(f.files.get("assets/test/models/block/model.json"), java.nio.charset.StandardCharsets.UTF_8)
                .replace("[0,0,0]", "[-8,0,0]").replace("[16,16,16]", "[24,16,16]");
        f.json("assets/test/models/block/model.json", text);
        var hits = new VoxelTracer(scene(f.generation(1)), TRACE).trace(ray(-.25, .5, -2, 0, 0, 1), 8, () -> false);
        assertEquals(1, hits.size());
        assertEquals(2, hits.getFirst().t(), 1e-9);
    }

    @Test
    void transparentHoleCanRevealAnotherFaceOfTheSameModel() throws Exception {
        RenderFixtures f = fixture();
        f.png("test:hole", 1, 1, 0x00000000);
        f.png("test:red", 1, 1, 0xffff0000);
        f.json("assets/test/models/block/model.json", """
                {"textures":{"hole":"test:hole","red":"test:red"},"elements":[
                {"from":[0,0,0],"to":[16,16,0],"faces":{"north":{"texture":"#hole"}}},
                {"from":[0,0,16],"to":[16,16,16],"faces":{"north":{"texture":"#red"}}}]}
                """);
        Texture.Sample color = renderer(scene(f.generation(1)), Map.of()).sampleRay(ray(.5, .5, -2, 0, 0, 1), () -> false);
        assertEquals(1, color.r(), 1e-6);
        assertEquals(0, color.g());
    }

    @Test
    void translucentLayersCompositeInLinearPremultipliedSpace() throws Exception {
        RenderFixtures f = fixture();
        f.png("test:glass", 1, 1, 0x80ff0000);
        f.png("test:blue", 1, 1, 0xff0000ff);
        f.json("assets/test/models/block/model.json", """
                {"textures":{"glass":"test:glass","blue":"test:blue"},"elements":[
                {"from":[0,0,0],"to":[16,16,0],"faces":{"north":{"texture":"#glass"}}},
                {"from":[0,0,8],"to":[16,16,8],"faces":{"north":{"texture":"#blue"}}}]}
                """);
        var r = renderer(scene(f.generation(1)), Map.of(ResourceKey.parse("test:glass"), CpuPhotoRenderer.AlphaMode.TRANSLUCENT));
        Texture.Sample color = r.sampleRay(ray(.5, .5, -2, 0, 0, 1), () -> false);
        assertEquals(128 / 255.0, color.r(), 1e-6);
        assertEquals(127 / 255.0, color.b(), 1e-6);
    }

    @Test
    void coplanarOverlayIsNotLostBehindOpaqueBase() throws Exception {
        RenderFixtures f = fixture();
        f.png("test:glass", 1, 1, 0x8000ff00);
        f.png("test:blue", 1, 1, 0xff0000ff);
        f.json("assets/test/models/block/model.json", """
                {"textures":{"glass":"test:glass","blue":"test:blue"},"elements":[
                {"from":[0,0,0],"to":[16,16,0],"faces":{"north":{"texture":"#blue"}}},
                {"from":[0,0,0],"to":[16,16,0],"faces":{"north":{"texture":"#glass"}}}]}
                """);
        var r = renderer(scene(f.generation(1)), Map.of(ResourceKey.parse("test:glass"), CpuPhotoRenderer.AlphaMode.TRANSLUCENT));
        var color = r.sampleRay(ray(.5, .5, -2, 0, 0, 1), () -> false);
        assertEquals(128 / 255.0, color.g(), 1e-6);
        assertEquals(127 / 255.0, color.b(), 1e-6);
    }

    @Test
    void ceIdDifferentFromCarrierAndModelStillProducesTexture() throws Exception {
        RenderFixtures f = fixture();
        f.block("minecraft:note_block", "test:block/model");
        AssetGeneration g = f.generation(7);
        var descriptor = new VisualBlockDescriptor(VisualBlockDescriptor.Kind.CE, ResourceKey.parse("ce:not_a_filename"),
                CanonicalBlockState.parse("minecraft:note_block[note=2]"), 7);
        SceneSnapshot s = builder(g).put(0, 0, 0, descriptor, new SceneSnapshot.Lighting(15, 0, 0xffffff), 0).build();
        var r = renderer(s, Map.of());
        assertEquals(1, r.sampleRay(ray(.75, .75, -2, 0, 0, 1), () -> false).r(), 1e-6);
        assertEquals(1, r.sampleRay(ray(.25, .75, -2, 0, 0, 1), () -> false).g(), 1e-6);
    }

    @Test
    void pixelRenderingYieldsCancelsAndOnlyPublishesCompletedImage() throws Exception {
        var r = renderer(scene(fixture().generation(1)), Map.of());
        var cursor = r.cursor();
        assertThrows(IllegalStateException.class, cursor::copyRgb);
        assertEquals(CpuPhotoRenderer.Status.YIELDED, r.renderBatch(cursor, 100, 0, () -> 0, () -> false).status());
        assertEquals(0, cursor.completedPixels());
        assertEquals(CpuPhotoRenderer.Status.CANCELLED, r.renderBatch(cursor, 100, 100, () -> 0, () -> true).status());
        assertEquals(CpuPhotoRenderer.Status.YIELDED, r.renderBatch(cursor, 100, 100, () -> 0, () -> false).status());
        assertEquals(100, cursor.completedPixels());
        assertEquals(CpuPhotoRenderer.Status.DONE, r.renderBatch(cursor, 16384, 100, () -> 0, () -> false).status());
        int[] first = cursor.copyRgb();
        assertEquals(16384, first.length);
        int value = first[0];
        first[0] = 0x123456;
        assertEquals(value, cursor.copyRgb()[0]);
    }

    @Test
    void excessiveIntersectionWorkStopsWithStructuredFailure() throws Exception {
        var tracer = new VoxelTracer(scene(fixture().generation(1)), new VoxelTracer.Limits(100, 100, 1));
        assertEquals(SceneException.Code.GEOMETRY_LIMIT, assertThrows(SceneException.class,
                () -> tracer.trace(ray(.5, .5, -2, 0, 0, 1), 8, () -> false)).code());
    }

    @Test
    void optimizedTraversalMatchesIndependentTriangleOracle() throws Exception {
        AssetGeneration g = fixture().generation(1);
        var b = builder(g);
        for (int x = -2; x <= 2; x += 2)
            for (int z = -2; z <= 2; z += 2)
                b.put(x, 0, z, RenderFixtures.vanilla("test:block", 1), new SceneSnapshot.Lighting(15, 0, 0xffffff), 0);
        SceneSnapshot s = b.build();
        VoxelTracer tracer = new VoxelTracer(s, TRACE);
        Random random = new Random(0x1234abcd);
        for (int i = 0; i < 1000; i++) {
            Ray ray = ray(random.nextDouble() * 8 - 4, random.nextDouble() * 3 + .01, random.nextDouble() * 8 - 4,
                    random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1);
            VoxelTracer.Hit expected = oracle(s, ray, 8);
            var actual = tracer.trace(ray, 8, () -> false);
            if (expected == null) assertTrue(actual.isEmpty(), "射线 " + i);
            else {
                assertFalse(actual.isEmpty());
                var hit = actual.getFirst();
                assertEquals(expected.t(), hit.t(), 1e-8, "射线 " + i);
                assertEquals(expected.instance().id(), hit.instance().id());
                assertEquals(expected.quadIndex(), hit.quadIndex());
                assertEquals(expected.u(), hit.u(), 1e-8);
                assertEquals(expected.v(), hit.v(), 1e-8);
            }
        }
    }

    @Test
    void negativeZeroDirectionDoesNotBreakDda() throws Exception {
        VoxelTracer tracer = new VoxelTracer(scene(fixture().generation(1)), TRACE);
        var hits = tracer.trace(ray(.5, .5, -2, -0.0, -0.0, 1), 8, () -> false);
        assertEquals(2, hits.getFirst().t(), 1e-9);
        assertEquals(new Vec3(1, 0, 0), new Vec3(Double.MAX_VALUE, 0, 0).normalized());
    }

    @Test
    void legacyCenterSampleMatchesThePreviousAngleFormula() {
        Camera camera = new Camera(new Vec3(0, 0, 0), .7, .2, Camera.Projection.LEGACY_ANGULAR, 1);
        Vec3 direction = camera.ray(64, 64, .5, .5).direction();
        assertEquals(Math.cos(.7) * Math.cos(.2), direction.x(), 1e-12);
        assertEquals(Math.sin(.2), direction.y(), 1e-12);
        assertEquals(Math.sin(.7) * Math.cos(.2), direction.z(), 1e-12);
    }

    private static VoxelTracer.Hit oracle(SceneSnapshot scene, Ray ray, double distance) {
        VoxelTracer.Hit best = null;
        for (var instance : scene.instances())
            for (int m = 0; m < instance.models().size(); m++) {
                var quads = instance.models().get(m).quads();
                for (int q = 0; q < quads.size(); q++) {
                    BakedQuad quad = quads.get(q);
                    for (int[] ids : new int[][]{{0, 1, 2}, {0, 2, 3}}) {
                        Vec3 shift = new Vec3(instance.x(), instance.y(), instance.z());
                        Vec3 a = quad.positions().get(ids[0]).add(shift), b = quad.positions().get(ids[1]).add(shift), c = quad.positions().get(ids[2]).add(shift);
                        Vec3 e1 = b.subtract(a), e2 = c.subtract(a), p = ray.direction().cross(e2);
                        double det = e1.dot(p);
                        if (Math.abs(det) < 1e-12) continue;
                        double inverse = 1 / det;
                        Vec3 delta = ray.origin().subtract(a);
                        double u = delta.dot(p) * inverse;
                        if (u < 0 || u > 1) continue;
                        Vec3 cross = delta.cross(e1);
                        double v = ray.direction().dot(cross) * inverse;
                        if (v < 0 || u + v > 1) continue;
                        double t = e2.dot(cross) * inverse;
                        if (t < 0 || t > distance || best != null && t >= best.t()) continue;
                        var ua = quad.uv().get(ids[0]);
                        var ub = quad.uv().get(ids[1]);
                        var uc = quad.uv().get(ids[2]);
                        best = new VoxelTracer.Hit(instance, m, q, quad, t, ua.u() * (1 - u - v) + ub.u() * u + uc.u() * v, ua.v() * (1 - u - v) + ub.v() * u + uc.v() * v);
                    }
                }
            }
        return best;
    }

    @Test void frozenFaceLightingAndLegacyEntityApproximationStaySeparateFromTextureTint() throws Exception {
        var assets=fixture().generation(1);var sceneBuilder=builder(assets);
        var faces=new ArrayList<SceneSnapshot.FaceLight>();for(Direction d:Direction.values())faces.add(new SceneSnapshot.FaceLight(d==Direction.NORTH?15:0,0));
        sceneBuilder.put(0,0,0,RenderFixtures.vanilla("test:block",1),new SceneSnapshot.Lighting(0,0,0xffffff,0xffffff,faces),0);
        var renderer=renderer(sceneBuilder.build(),Map.of());assertEquals(1,renderer.sampleRay(ray(.75,.75,-2,0,0,1),()->false).r(),1e-6);
        var entities=builder(assets).entity(new LegacyEntity("test:old_furniture",List.of(new LegacyEntity.Box(new Vec3(0,0,-1),new Vec3(1,1,-.5))),0x00ff00));
        entities.put(0,0,0,RenderFixtures.vanilla("test:block",1),new SceneSnapshot.Lighting(15,0,0xffffff),0);
        var color=renderer(entities.build(),Map.of()).sampleRay(ray(.5,.5,-2,0,0,1),()->false);
        assertEquals(1,color.g(),1e-6);assertEquals(0,color.r(),1e-6);
    }
}
