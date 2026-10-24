package com.wenkrang.famara.render.platform;
import com.wenkrang.famara.render.scene.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EquipmentCaptureTest {
    @Test void equippedStacksAreReducedToIndependentPrimitiveSnapshots(){
        var zombie=mock(Zombie.class);when(zombie.getType()).thenReturn(EntityType.ZOMBIE);var gear=mock(EntityEquipment.class);when(zombie.getEquipment()).thenReturn(gear);var sword=mock(ItemStack.class);when(sword.getAmount()).thenReturn(1);when(sword.getType()).thenReturn(Material.DIAMOND_SWORD);when(sword.hasItemMeta()).thenReturn(true);var meta=mock(ItemMeta.class);when(sword.getItemMeta()).thenReturn(meta);when(meta.hasEnchants()).thenReturn(true);when(gear.getItemInMainHand()).thenReturn(sword);
        var captured=EquipmentCapture.capture(zombie,ActorAppearance.EMPTY);when(sword.getType()).thenReturn(Material.STONE);assertEquals("minecraft:diamond_sword",captured.get("MAINHAND").material());assertTrue(captured.get("MAINHAND").enchanted());assertEquals(1,captured.slots().size());verify(sword,never()).serialize();
    }
    interface ModernMeta extends ItemMeta {boolean hasEnchantmentGlintOverride();Boolean getEnchantmentGlintOverride();}
    @Test void unsetOptionalGlintComponentIsNeverRead(){var stack=mock(ItemStack.class);when(stack.getAmount()).thenReturn(1);when(stack.getType()).thenReturn(Material.DIAMOND_SWORD);when(stack.hasItemMeta()).thenReturn(true);var meta=mock(ModernMeta.class);when(stack.getItemMeta()).thenReturn(meta);when(meta.hasEnchants()).thenReturn(true);when(meta.hasEnchantmentGlintOverride()).thenReturn(false);assertTrue(EquipmentCapture.item(stack).enchanted());verify(meta,never()).getEnchantmentGlintOverride();when(meta.hasEnchantmentGlintOverride()).thenReturn(true);when(meta.getEnchantmentGlintOverride()).thenReturn(false);assertFalse(EquipmentCapture.item(stack).enchanted());}
    private static final class PrivateTrait {public boolean isSitting(){return true;}}
    @Test void optionalTraitsCanBeReadFromNonPublicImplementationClasses(){assertEquals(true,ActorAppearanceCapture.optional(new PrivateTrait(),"isSitting"));}
    @Test void emptyAndAirItemsProduceNoEquipment(){assertNull(EquipmentCapture.item(null));var air=mock(ItemStack.class);when(air.getAmount()).thenReturn(1);when(air.getType()).thenReturn(Material.AIR);assertNull(EquipmentCapture.item(air));verify(air,never()).getItemMeta();}
    @Test void customLeatherDyeIsCopiedOnTheOwner(){var armor=mock(ItemStack.class);when(armor.getAmount()).thenReturn(1);when(armor.getType()).thenReturn(Material.LEATHER_CHESTPLATE);when(armor.hasItemMeta()).thenReturn(true);var meta=mock(LeatherArmorMeta.class);when(meta.getColor()).thenReturn(Color.fromRGB(0x12abef));when(armor.getItemMeta()).thenReturn(meta);assertEquals(0x12abef,EquipmentCapture.item(armor).tintRgb());}
}
