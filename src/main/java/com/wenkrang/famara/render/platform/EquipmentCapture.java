package com.wenkrang.famara.render.platform;

import com.wenkrang.famara.compat.RuntimeCompat;
import com.wenkrang.famara.render.scene.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import java.util.*;

/** Converts equipped items into bounded primitive appearance data before leaving the owner thread. */
final class EquipmentCapture {
    static FrozenEquipment capture(LivingEntity entity,ActorAppearance appearance){
        Map<String,FrozenItem> slots=new HashMap<>();EntityEquipment equipment=entity.getEquipment();
        if(equipment!=null){put(slots,"HEAD",equipment.getHelmet());put(slots,"CHEST",equipment.getChestplate());put(slots,"LEGS",equipment.getLeggings());put(slots,"FEET",equipment.getBoots());put(slots,"MAINHAND",equipment.getItemInMainHand());put(slots,"OFFHAND",equipment.getItemInOffHand());
            for(String name:List.of("BODY","SADDLE"))try{EquipmentSlot slot=EquipmentSlot.valueOf(name);put(slots,name,equipment.getItem(slot));}catch(IllegalArgumentException ignored){}
        }
        if(entity instanceof AbstractHorse horse){Object armor=ActorAppearanceCapture.optional(horse.getInventory(),"getArmor");if(armor instanceof ItemStack item)put(slots,"BODY",item);put(slots,"SADDLE",horse.getInventory().getSaddle());Object decor=ActorAppearanceCapture.optional(horse.getInventory(),"getDecor");if(decor instanceof ItemStack item)put(slots,"BODY",item);}
        if(entity.getType().name().equals("SULFUR_CUBE")){Object carried=ActorAppearanceCapture.optional(entity,"getEquipped");if(carried instanceof ItemStack item)put(slots,"MAINHAND",item);}
        return new FrozenEquipment(slots,appearance.flag("leftHanded"));
    }
    private static void put(Map<String,FrozenItem> slots,String slot,ItemStack stack){FrozenItem item=item(stack);if(item!=null)slots.put(slot,item);}
    static FrozenItem item(ItemStack stack){
        if(stack==null || stack.getAmount()<=0 || stack.getType().isAir())return null;
        String material=stack.getType().getKey().toString(),model="",pattern="",trim="",skin="",asset="";int color=material.startsWith("minecraft:leather_")?0xa06540:0xffffff,cmd=0;boolean glint=false,charged=false;
        if(stack.hasItemMeta()){
            ItemMeta meta=stack.getItemMeta();var data=RuntimeCompat.modelData(meta);model=data.itemModel()==null?"":data.itemModel();cmd=data.legacy()==null?0:data.legacy();glint=meta.hasEnchants();Object override=Boolean.TRUE.equals(ActorAppearanceCapture.optional(meta,"hasEnchantmentGlintOverride"))?ActorAppearanceCapture.optional(meta,"getEnchantmentGlintOverride"):null;if(override instanceof Boolean flag)glint=flag;
            if(meta instanceof LeatherArmorMeta leather)color=leather.getColor().asRGB();else{Object tint=meta instanceof PotionMeta?ActorAppearanceCapture.optional(meta,"computeEffectiveColor"):null;if(tint==null && Boolean.TRUE.equals(ActorAppearanceCapture.optional(meta,"hasColor")))tint=ActorAppearanceCapture.optional(meta,"getColor");if(tint instanceof Color value)color=value.asRGB();}
            if(meta instanceof ArmorMeta armor && armor.hasTrim()){var decoration=armor.getTrim();pattern=decoration.getPattern().getKey().toString();trim=decoration.getMaterial().getKey().toString();}
            if(meta instanceof SkullMeta skull){var profile=skull.getPlayerProfile();if(profile!=null && profile.getTextures().getSkin()!=null)skin=profile.getTextures().getSkin().toString();}
            if(meta instanceof CrossbowMeta crossbow)charged=crossbow.hasChargedProjectiles();
            Object equippable=Boolean.TRUE.equals(ActorAppearanceCapture.optional(meta,"hasEquippable"))?ActorAppearanceCapture.optional(meta,"getEquippable"):null;Object id=ActorAppearanceCapture.optional(equippable,"getModel");if(id!=null)asset=id.toString();
        }
        return new FrozenItem(material,model,color,pattern,trim,glint,cmd,skin,asset,charged);
    }
}
