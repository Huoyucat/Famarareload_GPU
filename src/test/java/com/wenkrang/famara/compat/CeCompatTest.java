package com.wenkrang.famara.compat;

import com.wenkrang.famara.lib.ColorManager;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CeCompatTest {
    @org.junit.jupiter.api.BeforeEach
    void selectOptionalBackend() throws ReflectiveOperationException { CeCompat.backendForTests(new CraftEngineBackend()); }
    @org.junit.jupiter.api.AfterEach
    void resetOptionalBackend() { CeCompat.backendForTests(null); }

    private static final String ID = "test:chair";
    private CeCompat.Session session() {
        return new CeCompat.Session(new ColorManager.View(Map.of(ID, 0x654321), Map.of(ID, 0x654321), Map.of(), List.of()),
                PackColorIndex.Result.EMPTY);
    }
    private ItemStack stack() {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.STICK);
        when(stack.getAmount()).thenReturn(1);
        return stack;
    }

    @Test void ordinaryEntitiesAndModeledLivingCarriersNeverCreateShapes() {
        try (var furniture = mockStatic(CraftEngineFurniture.class)) {
            for (Class<? extends Entity> type : List.of(Player.class, LivingEntity.class, ArmorStand.class, Boat.class, Entity.class))
                assertNull(CeCompat.capture(mock(type), session(), new HashSet<>()), type.getName());
        }
    }

    @Test void vanillaItemsInEverySupportedCarrierAreIgnored() {
        try (var furniture = mockStatic(CraftEngineFurniture.class); var items = mockStatic(CraftEngineItems.class)) {
            ItemStack stack = stack();
            Item drop = mock(Item.class); when(drop.getItemStack()).thenReturn(stack);
            ItemDisplay display = mock(ItemDisplay.class); when(display.getItemStack()).thenReturn(stack);
            when(display.getItemDisplayTransform()).thenReturn(ItemDisplay.ItemDisplayTransform.FIXED);
            ItemFrame frame = mock(ItemFrame.class); when(frame.getItem()).thenReturn(stack);
            for (Entity entity : List.of(drop, display, frame)) assertNull(CeCompat.capture(entity, session(), new HashSet<>()));
        }
    }

    @Test void ceDropDisplayAndFrameUseCeIdentityAndManualColor() {
        try (var furniture = mockStatic(CraftEngineFurniture.class); var items = mockStatic(CraftEngineItems.class)) {
            ItemStack stack = stack();
            items.when(() -> CraftEngineItems.getCustomItemId(stack)).thenReturn(Key.of(ID));
            Item drop = mock(Item.class); when(drop.getItemStack()).thenReturn(stack);
            ItemDisplay display = mock(ItemDisplay.class); when(display.getItemStack()).thenReturn(stack);
            when(display.getItemDisplayTransform()).thenReturn(ItemDisplay.ItemDisplayTransform.FIXED);
            when(display.getTransformation()).thenReturn(new Transformation(new Vector3f(1, 0, 0), new Quaternionf(), new Vector3f(2, 1, 1), new Quaternionf()));
            GlowItemFrame frame = mock(GlowItemFrame.class); when(frame.getItem()).thenReturn(stack);
            when(frame.getFacing()).thenReturn(BlockFace.NORTH);
            for (Entity entity : List.of(drop, display, frame)) {
                when(entity.getLocation()).thenReturn(new Location(null, 10000000.25, 70, 4));
                var shape = CeCompat.capture(entity, session(), new HashSet<>());
                assertNotNull(shape);
                assertEquals(ID, shape.id());
                assertEquals(0x654321, shape.color().toARGB() & 0xffffff);
                assertFalse(shape.boxes().isEmpty());
                double[] box = shape.boxes().getFirst();
                assertTrue(box[3] > box[0]); assertTrue(box[4] > box[1]); assertTrue(box[5] > box[2]);
                if (entity == drop) assertEquals(10000000, box[0], 0.000001);
                if (entity == display) assertEquals(10000000.25, box[0], 0.000001);
                if (entity == frame) assertEquals(0.05, box[5] - box[2], 0.000001);
            }
        }
    }

    @Test void furnitureDoesNotFallBackToIndependentItemWhenItsShapeIsUnavailable() {
        try (var api = mockStatic(CraftEngineFurniture.class); var items = mockStatic(CraftEngineItems.class)) {
            ItemDisplay display = mock(ItemDisplay.class);
            api.when(() -> CraftEngineFurniture.isFurniture(display)).thenReturn(true);
            assertNull(CeCompat.capture(display, session(), new HashSet<>()));
            verify(display, never()).getItemStack();
            items.verifyNoInteractions();
        }
    }

    @Test void seatsAndCollidersCannotBeRenderedAsIndependentItems() {
        try (var api = mockStatic(CraftEngineFurniture.class)) {
            ItemDisplay seat = mock(ItemDisplay.class), collider = mock(ItemDisplay.class);
            api.when(() -> CraftEngineFurniture.isSeat(seat)).thenReturn(true);
            api.when(() -> CraftEngineFurniture.isCollisionEntity(collider)).thenReturn(true);
            assertNull(CeCompat.capture(seat, session(), new HashSet<>()));
            assertNull(CeCompat.capture(collider, session(), new HashSet<>()));
            verify(seat, never()).getItemStack(); verify(collider, never()).getItemStack();
        }
    }

    @Test void failedCeStateResolutionIsReportedNotMistakenForVanilla() {
        try (var api = mockStatic(CraftEngineBlocks.class)) {
            BlockData data = mock(BlockData.class);
            when(data.getAsString()).thenReturn("minecraft:note_block[note=1]");
            api.when(() -> CraftEngineBlocks.getCustomBlockState(data)).thenThrow(new IllegalStateException("测试失败"));
            var color = session().block(data);
            assertEquals(CeCompat.NO_COLOR, color.rgb());
            assertEquals("minecraft:note_block[note=1]", color.id());
        }
    }

    @Test void nullCeStateKeepsOriginalBlockColorPath() {
        try (var api = mockStatic(CraftEngineBlocks.class)) {
            assertEquals(CeCompat.NOT_CUSTOM, session().block(mock(BlockData.class)).rgb());
        }
    }

    @Test void transformedBoxHandlesNegativeScaleRotationAndZeroDimensions() {
        double[] box = CeCompat.transformedBox(new Matrix4f().rotateY((float) (Math.PI / 2)).scale(-2, 1, 3), .5f, .5f, .5f);
        assertNotNull(box);
        assertEquals(3, box[3] - box[0], 0.00001);
        assertEquals(2, box[5] - box[2], 0.00001);
        assertNull(CeCompat.transformedBox(new Matrix4f().scale(0), .5f, .5f, .5f));
    }

    @Test void staleResourceBuildCannotOverwriteNewerResult() {
        long oldTicket = CeCompat.requestRebuild();
        long latestTicket = CeCompat.requestRebuild();
        var latest = new PackColorIndex.Result(Map.of("test:new", state -> 0x123456), Map.of(), List.of());
        assertTrue(CeCompat.publish(latest, latestTicket));
        assertFalse(CeCompat.publish(PackColorIndex.Result.EMPTY, oldTicket));
    }

    @Test void totallyBrokenResourceBuildPreservesPreviousIndex() {
        long ticket = CeCompat.requestRebuild();
        assertFalse(CeCompat.publish(new PackColorIndex.Result(Map.of(), Map.of(), List.of("损坏 JSON")), ticket));
    }
}
