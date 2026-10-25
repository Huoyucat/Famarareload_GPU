package com.wenkrang.famara.render.scene;

import com.wenkrang.famara.render.asset.*;
import java.util.*;

/** 服务端真实biome元数据与资源包LUT组合；不读取客户端默认biome定义。 */
public final class BiomeColors {
    public record Climate(double temperature,double downfall,int water,Integer grass,Integer foliage,Integer dryFoliage) {
        public Climate {
            if(!Double.isFinite(temperature) || !Double.isFinite(downfall))
                throw new IllegalArgumentException("生物群系气候数据不是有限数：temperature="+temperature+", downfall="+downfall);
            if((water&0xff000000)!=0)throw new IllegalArgumentException("生物群系水色未规范为RGB：0x"+Integer.toHexString(water));
            for(Integer color:Arrays.asList(grass,foliage,dryFoliage))if(color!=null && (color&0xff000000)!=0)throw new IllegalArgumentException("biome颜色不是RGB");
        }
    }
    private static final Set<String> GRASS=Set.of("grass_block","short_grass","grass","tall_grass","fern","large_fern","potted_fern","sugar_cane","bush","pink_petals","wildflowers");
    public static boolean usesGrass(CanonicalBlockState state){return state.block().namespace().equals("minecraft") && GRASS.contains(state.block().path());}
    private BiomeColors() {}
    public static int lookup(AssetGeneration assets,String type,double temperature,double downfall) {
        double t=Math.clamp(temperature,0,1),humidity=Math.clamp(downfall,0,1)*t;
        int x=(int)((1-t)*255),y=(int)((1-humidity)*255);
        Texture texture=assets.texture(ResourceKey.parse("minecraft:colormap/"+type));
        if(texture.width()!=256 || texture.height()!=256 || texture.frameCount()!=1 || !texture.opaque())
            throw new AssetException(AssetException.Code.INVALID,"colormap/"+type,"染色LUT必须为256×256静态不透明图");
        var color=texture.sample((x+.5)/256,(y+.5)/256,0,0,Texture.Filter.NEAREST);
        return Texture.srgb(color.r())<<16 | Texture.srgb(color.g())<<8 | Texture.srgb(color.b());
    }
    public static int grass(AssetGeneration assets,Climate biome) {
        return biome.grass!=null?biome.grass:lookup(assets,"grass",biome.temperature,biome.downfall);
    }
    public static int tint(AssetGeneration assets,CanonicalBlockState state,Climate biome,int modifiedGrass) {
        if(!state.block().namespace().equals("minecraft"))return 0xffffff;
        String name=state.block().path();
        if(name.equals("redstone_wire"))return redstone(Integer.parseInt(state.properties().getOrDefault("power","0")));
        // Java 26.2 does not register cherry leaves in BlockColors: their pink
        // shade is in the texture and they must not receive the green foliage LUT.
        if(name.equals("cherry_leaves"))return 0xffffff;
        if(name.equals("spruce_leaves"))return 0x619961;
        if(name.equals("birch_leaves"))return 0x80a755;
        if(name.equals("mangrove_leaves"))return biome.foliage!=null?biome.foliage:0x92c648;
        if(name.equals("lily_pad"))return 0x208030;
        if(usesGrass(state))return modifiedGrass;
        if(name.equals("dry_grass") || name.equals("short_dry_grass") || name.equals("tall_dry_grass"))
            return biome.dryFoliage!=null?biome.dryFoliage:lookup(assets,"dry_foliage",biome.temperature,biome.downfall);
        if(name.endsWith("_leaves") || name.equals("vine"))
            return biome.foliage!=null?biome.foliage:lookup(assets,"foliage",biome.temperature,biome.downfall);
        if(name.equals("attached_melon_stem") || name.equals("attached_pumpkin_stem"))return 0xe0c71c;
        if(name.equals("melon_stem") || name.equals("pumpkin_stem")) {
            int age=Integer.parseInt(state.properties().getOrDefault("age","0"));if(age<0 || age>7)throw new IllegalArgumentException("瓜茎年龄无效");
            return age*32<<16 | (255-age*8)<<8 | age*4;
        }
        return 0xffffff;
    }
    public static int redstone(int power) {
        if(power<0 || power>15)throw new IllegalArgumentException("红石能量无效");
        float value=power/15f,r=power==0?.3f:value*.6f+.4f,g=Math.max(0,value*value*.7f-.5f),b=Math.max(0,value*value*.6f-.7f);
        return (int)(r*255)<<16 | (int)(g*255)<<8 | (int)(b*255);
    }
}
