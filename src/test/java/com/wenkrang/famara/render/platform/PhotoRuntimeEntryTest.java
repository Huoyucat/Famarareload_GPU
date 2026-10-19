package com.wenkrang.famara.render.platform;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.*;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 入口拒绝/模式切换的单元证据，不代替真实背包或Folia集成。 */
class PhotoRuntimeEntryTest {
    @BeforeEach void before()throws Exception {PhotoRuntime.stop();mode(PhotoRuntime.Mode.LEGACY);}
    @AfterEach void after()throws Exception {PhotoRuntime.stop();mode(PhotoRuntime.Mode.LEGACY);}
    private void mode(PhotoRuntime.Mode mode)throws Exception {Field field=PhotoRuntime.class.getDeclaredField("mode");field.setAccessible(true);field.set(null,mode);}
    private record Held(Player player,PlayerInventory inventory,PlayerInteractEvent event,ItemStack item,ItemMeta meta,PersistentDataContainer data) {}
    private Held held(String identity) {
        Player player=mock(Player.class);PlayerInventory inventory=mock(PlayerInventory.class);PlayerInteractEvent event=mock(PlayerInteractEvent.class);
        ItemStack item=mock(ItemStack.class);ItemMeta meta=mock(ItemMeta.class);PersistentDataContainer data=mock(PersistentDataContainer.class);
        when(player.getInventory()).thenReturn(inventory);when(inventory.getItemInMainHand()).thenReturn(item);when(event.getPlayer()).thenReturn(player);
        when(item.getItemMeta()).thenReturn(meta);when(item.getAmount()).thenReturn(1);when(item.getType()).thenReturn(Material.STICK);when(meta.getPersistentDataContainer()).thenReturn(data);
        when(meta.getLore()).thenReturn(List.of("相机","说明","§7已装填彩色胶卷（16 / 16）"));
        when(data.get(new NamespacedKey("famara","item_id"),PersistentDataType.STRING)).thenReturn(identity);
        return new Held(player,inventory,event,item,meta,data);
    }
    @Test void texturedWithoutReadyConfigurationNeverCallsLegacyOrDebitsFilm() throws Exception {
        mode(PhotoRuntime.Mode.TEXTURED);Held h=held("famara:camera");
        when(h.data.get(new NamespacedKey("famara","viewfinder_open"),PersistentDataType.BYTE)).thenReturn((byte)1);
        assertTrue(PhotoRuntime.interact(h.event));verify(h.event).setCancelled(true);
        verify(h.inventory,never()).setItemInMainHand(any());verify(h.meta,never()).setLore(any());
        verify(h.player).sendMessage(contains("不扣胶卷"));
    }
    @Test void v2FilmIsInterceptedEvenWhenModeHasSwitchedBackToLegacy() {
        Held h=held("famara:photo");when(h.data.get(new NamespacedKey("famara","photo_request_v2"),PersistentDataType.STRING)).thenReturn(UUID.randomUUID().toString());
        assertTrue(PhotoRuntime.interact(h.event));verify(h.event).setCancelled(true);verify(h.inventory,never()).setItemInMainHand(any());
        verify(h.player).sendMessage(contains("物品已保留"));assertTrue(PhotoRuntime.progress(h.item).tracked());
    }
    @Test void untaggedLegacyCameraKeepsExistingEntry() {
        Held h=held("famara:camera");assertFalse(PhotoRuntime.interact(h.event));verify(h.event,never()).setCancelled(anyBoolean());
    }
    @Test void cameraWithUnloadedDebtCannotEraseCounterByUsingLegacy() {
        Held h=held("famara:camera");when(h.data.get(new NamespacedKey("famara","camera_id"),PersistentDataType.STRING)).thenReturn(UUID.randomUUID().toString());
        when(h.data.get(new NamespacedKey("famara","film_applied_spent"),PersistentDataType.LONG)).thenReturn(1L);
        assertTrue(PhotoRuntime.interact(h.event));verify(h.event).setCancelled(true);verify(h.inventory,never()).setItemInMainHand(any());
    }

    @Test void oldFilmedCameraCanStillPullLegacyFilmAfterModeSwitch()throws Exception {
        mode(PhotoRuntime.Mode.TEXTURED);Held h=held("famara:camera");
        when(h.meta.getDisplayName()).thenReturn("§f相机（Shift + 右键拉出撕拉片）");when(h.player.isSneaking()).thenReturn(true);
        assertFalse(PhotoRuntime.interact(h.event));verify(h.event,never()).setCancelled(anyBoolean());
    }
}
