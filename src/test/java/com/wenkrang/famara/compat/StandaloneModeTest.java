package com.wenkrang.famara.compat;

import com.wenkrang.famara.lib.CeItems;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.*;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 供用户自行运行；核心模式不调用任何 CE API。 */
class StandaloneModeTest {
    private static final NamespacedKey ITEM_ID = new NamespacedKey("famara", "item_id");
    @BeforeEach void reset() { CeCompat.backendForTests(null); }
    @AfterEach void cleanup() { CeCompat.backendForTests(null); }

    @Test void noCePluginSelectsStandaloneInsteadOfLoadingOptionalBackend() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        PluginManager plugins = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(plugins);
        when(plugins.getPlugin("CraftEngine")).thenReturn(null);
        assertDoesNotThrow(() -> CeCompat.initialize(plugin));
        assertFalse(CeCompat.enabled());
        assertTrue(CeCompat.status().contains("未安装"));
    }

    @Test void standaloneSnapshotDoesNotInspectEntitiesOrCallCustomBlockApi() {
        BlockData data = mock(BlockData.class);
        Entity entity = mock(Entity.class);
        CeCompat.Session session = CeCompat.session();
        assertEquals(CeCompat.NOT_CUSTOM, session.block(data).rgb());
        assertNull(CeCompat.capture(entity, session, new HashSet<>()));
        verifyNoInteractions(data, entity);
    }

    private ItemStack item(ItemMeta meta) {
        ItemStack item = mock(ItemStack.class);
        when(item.getAmount()).thenReturn(1);
        when(item.getType()).thenReturn(Material.STICK);
        when(item.getItemMeta()).thenReturn(meta);
        return item;
    }

    @Test void sameDisplayNameAndCmdWithoutPdcCannotImpersonateCamera() {
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(meta.getDisplayName()).thenReturn("§f相机");
        when(meta.hasCustomModelData()).thenReturn(true);
        when(meta.getCustomModelData()).thenReturn(CeItems.CAMERA_MODEL);
        assertFalse(CeItems.is(item(meta), CeItems.CAMERA));
    }

    @Test void pdcIdentityWorksWithoutCeAndDoesNotRequireSpecificNameOrCmd() {
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(ITEM_ID, PersistentDataType.STRING)).thenReturn(CeItems.CAMERA);
        assertTrue(CeItems.is(item(meta), CeItems.CAMERA));
        assertFalse(CeItems.is(item(meta), CeItems.FILM_BOX));
    }

    @Test void verifiedLegacyIntegrationItemCanBeTaggedWithoutGuessingByName() {
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        ItemStack item = item(meta);
        CeCompat.backendForTests(new CeCompat.Backend() {
            public boolean enabled() { return true; }
            public boolean matches(ItemStack stack, String id) { return id.equals(CeItems.CAMERA); }
        });
        assertTrue(CeItems.is(item, CeItems.CAMERA));
        verify(pdc).set(ITEM_ID, PersistentDataType.STRING, CeItems.CAMERA);
        verify(item).setItemMeta(meta);
    }
}
