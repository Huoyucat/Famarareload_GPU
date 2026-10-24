package com.wenkrang.famara.render.scene;

import java.util.Objects;

/** Item appearance only, copied on the entity owner; no ItemStack or mutable component survives. */
public record FrozenItem(String material,String itemModel,int tintRgb,String trimPattern,String trimMaterial,
                         boolean enchanted,int customModelData,String skinUrl,String equipmentAsset,boolean charged) {
    public FrozenItem {
        Objects.requireNonNull(material);itemModel=clean(itemModel,192);trimPattern=clean(trimPattern,128);trimMaterial=clean(trimMaterial,128);skinUrl=clean(skinUrl,512);equipmentAsset=clean(equipmentAsset,192);
        if(material.length()>128 || !material.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || (tintRgb&0xff000000)!=0)throw new IllegalArgumentException("Invalid frozen equipment");
    }
    private static String clean(String value,int max){value=value==null?"":value;if(value.length()>max)throw new IllegalArgumentException("Equipment metadata exceeds limit");return value;}
    public static FrozenItem vanilla(String material){return new FrozenItem(material,"",material.startsWith("minecraft:leather_")?0xa06540:0xffffff,"","",false,0,"","",false);}
}
