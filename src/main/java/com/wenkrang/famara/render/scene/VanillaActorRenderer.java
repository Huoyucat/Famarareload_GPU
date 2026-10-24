package com.wenkrang.famara.render.scene;

import com.wenkrang.famara.render.asset.*;
import org.joml.Matrix4d;
import java.util.*;

/** Complete 26.2 registry backed by extracted vanilla meshes, with layered species appearances. */
public final class VanillaActorRenderer {
    private VanillaActorRenderer(){}
    public static ActorGeometry bake(FrozenActor a,ResourceKey skin,Texture playerSkin,ActorResources r){
        var catalog=r.catalog();String model=catalog.model(a);boolean babyLayer=model.contains("_baby#") || a.type().equals("SULFUR_CUBE");
        var b=new ActorModelBuilder(a,catalog.scale(a),a.baby() && !babyLayer);ResourceKey texture=skin==null?texture(a,r):skin;Texture image=playerSkin==null?r.texture(texture):playerSkin;
        int tint=a.type().equals("TROPICAL_FISH")?(int)a.appearance().number("bodyColor",0xffffff):0xffffff;
        Map<String,Matrix4d> bones=catalog.emit(b,a,model,texture,image,tint,0);
        String base=model.substring(0,model.indexOf('#'));
        switch(a.type()){
            case "SHEEP"->{if(!a.sheared())layer(b,a,r,base+"#wool","entity/sheep/sheep_wool"+(model.contains("_baby")?"_baby":""),a.tintRgb(),1);}
            case "SLIME"->layer(b,a,r,"minecraft:slime#outer","entity/slime/slime",0xffffff,1);
            case "SULFUR_CUBE"->layer(b,a,r,base+"#inner","entity/sulfur_cube/sulfur_cube_inner"+(a.baby()?"_small":""),0xffffff,0);
            case "DROWNED"->layer(b,a,r,base+"#outer","entity/zombie/drowned_outer_layer"+(model.contains("_baby")?"_baby":""),0xffffff,1);
            case "STRAY"->layer(b,a,r,base+"#outer","entity/skeleton/stray_overlay",0xffffff,1);
            case "BOGGED"->{if(!a.sheared())layer(b,a,r,base+"#outer","entity/skeleton/bogged_overlay",0xffffff,1);}
            case "PARCHED"->layer(b,a,r,base+"#outer","entity/skeleton/parched",0xffffff,1);
            case "BREEZE"->layer(b,a,r,"minecraft:breeze#wind","entity/breeze/breeze_wind",0xffffff,1);
            case "HORSE"->{String marking=switch(a.appearance().value("markings","none")){case "white"->"white";case "whitefield"->"whitefield";case "white_dots"->"whitedots";case "black_dots"->"blackdots";default->"";};if(!marking.isEmpty())layer(b,a,r,model,"entity/horse/horse_markings_"+marking+(model.contains("_baby")?"_baby":""),0xffffff,1);}
            case "VILLAGER","ZOMBIE_VILLAGER"->{String family=a.type().equals("VILLAGER")?"villager":"zombie_villager";String biome=a.appearance().value("biome","plains"),job=a.appearance().value("profession","none");layer(b,a,r,model,"entity/"+family+"/"+(model.contains("_baby")?"baby/":"type/")+biome,0xffffff,1);if(!Set.of("none","nitwit").contains(job))layer(b,a,r,model,"entity/"+family+"/profession/"+job,0xffffff,2);}
            case "WOLF"->{if(a.appearance().flag("tamed"))layer(b,a,r,model,"entity/wolf/wolf_collar"+(model.contains("_baby")?"_baby":""),(int)a.appearance().number("collarColor",0xb02e26),1);}
            case "CAT"->{if(a.appearance().flag("tamed"))layer(b,a,r,base+"#collar","entity/cat/cat_collar"+(model.contains("_baby")?"_baby":""),(int)a.appearance().number("collarColor",0xb02e26),1);}
            case "TRADER_LLAMA"->layer(b,a,r,"minecraft:llama"+(a.baby()?"_baby":"")+"#decor","entity/equipment/llama_body/trader_llama"+(a.baby()?"_baby":""),0xffffff,1);
            case "TROPICAL_FISH"->{String pattern=a.appearance().value("pattern","kob");List<String> patterns=model.contains("large")?List.of("flopper","stripey","glitter","blockfish","betty","clayfish"):List.of("kob","sunstreak","snooper","dasher","brinely","spotty");int id=Math.max(0,patterns.indexOf(pattern))+1;layer(b,a,r,base+"#pattern","entity/fish/tropical_"+(model.contains("large")?"b":"a")+"_pattern_"+id,(int)a.appearance().number("patternColor",0xffffff),1);}
            default->{}
        }
        EquipmentModelRenderer.equip(b,a,model,bones,r);
        return b.build();
    }
    private static void layer(ActorModelBuilder b,FrozenActor a,ActorResources r,String model,String texture,int tint,int layer){var key=new ResourceKey("minecraft",texture);if(!r.catalog().has(model) || !r.has(key.file("textures",".png")))throw new IllegalStateException("Missing vanilla appearance layer: "+model+" / "+texture);r.catalog().emit(b,a,model,key,r.texture(key),tint,layer);}
    private static ResourceKey texture(FrozenActor a,ActorResources r){
        String path=VanillaActorRegistry.texture(a.type()),v=a.appearance().value("variant",a.variant()).toLowerCase(Locale.ROOT);
        switch(a.type()){
            case "COW","PIG","CHICKEN"->{if(Set.of("cold","warm").contains(v))path=path.replace("temperate",v);}
            case "AXOLOTL"->{if(Set.of("lucy","wild","gold","cyan","blue").contains(v))path="axolotl/axolotl_"+v;}
            case "CAT"->{if(!v.isEmpty())path="cat/cat_"+v;}
            case "FOX"->{if(v.equals("snow"))path="fox/fox_snow";if(a.appearance().flag("sleeping"))path+="_sleep";}
            case "FROG"->{if(Set.of("warm","cold","temperate").contains(v))path="frog/frog_"+v;}
            case "HORSE"->{if(!v.isEmpty())path="horse/horse_"+switch(v){case "dark_brown"->"darkbrown";default->v;};}
            case "MOOSHROOM"->{if(v.equals("brown"))path="cow/mooshroom_brown";}
            case "PANDA"->{if(!v.isEmpty() && !v.equals("normal"))path="panda/panda_"+v;}
            case "PARROT"->{if(!v.isEmpty())path="parrot/parrot_"+switch(v){case "red"->"red_blue";case "cyan"->"yellow_blue";case "gray"->"grey";default->v;};}
            case "RABBIT"->{if(!v.isEmpty())path="rabbit/rabbit_"+switch(v){case "black_and_white"->"white_splotched";case "salt_and_pepper"->"salt";case "the_killer_bunny"->"caerbannog";default->v;};}
            case "LLAMA","TRADER_LLAMA"->{if(!v.isEmpty())path="llama/llama_"+v;}
            case "TROPICAL_FISH"->{if(r.catalog().model(a).contains("large"))path="fish/tropical_b";}
            case "SHULKER"->{if(!v.isEmpty())path="shulker/shulker_"+v;}
            case "COPPER_GOLEM"->{if(!v.isEmpty() && !v.equals("unaffected"))path+="_"+v;}
            case "WOLF"->{if(!v.isEmpty() && !v.equals("pale"))path="wolf/wolf_"+v;if(a.appearance().flag("angry"))path+="_angry";else if(a.appearance().flag("tamed"))path+="_tame";}
            case "SULFUR_CUBE"->{if(a.baby())path+="_small";}
            case "ZOMBIE_NAUTILUS"->{if(v.equals("coral"))path+="_coral";}
            default->{}
        }
        String full="entity/"+path;if(a.baby() && r.has("assets/minecraft/textures/"+full+"_baby.png"))full+="_baby";
        var key=new ResourceKey("minecraft",full);if(!r.has(key.file("textures",".png")))throw new IllegalStateException("Vanilla entity appearance missing: "+a.type()+" / "+key);return key;
    }
}
