package com.wenkrang.famara.render.platform;

import com.wenkrang.famara.render.scene.*;
import com.wenkrang.famara.render.math.Vec3;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.util.EulerAngle;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Optional versioned API access is cached; this class runs exclusively on an entity's owner. */
final class ActorAppearanceCapture {
    private static final ClassValue<Map<String,Optional<Method>>> METHODS=new ClassValue<>(){protected Map<String,Optional<Method>> computeValue(Class<?> type){return new ConcurrentHashMap<>();}};
    static Object optional(Object target,String name){
        if(target==null)return null;var method=METHODS.get(target.getClass()).computeIfAbsent(name,key->{try{return Optional.of(target.getClass().getMethod(key));}catch(NoSuchMethodException ignored){return Optional.empty();}});if(method.isEmpty())return null;
        try{if(!method.get().canAccess(target) && !method.get().trySetAccessible())throw new IllegalAccessException("Actor implementation is inaccessible");return method.get().invoke(target);}catch(InvocationTargetException error){if(error.getCause() instanceof UnsupportedOperationException)return null;throw new IllegalStateException("Actor API failed: "+name,error.getCause());}catch(ReflectiveOperationException error){throw new IllegalStateException("Actor API failed: "+name,error);}
    }
    record ProfileSkin(String url,boolean slim){}
    static ProfileSkin profileSkin(Object profile){
        Object values=optional(profile,"properties");if(values instanceof Collection<?> properties){int count=0;for(Object value:properties){if(++count>32)break;Object name=optional(value,"getName"),encoded=optional(value,"getValue");if("textures".equals(name) && encoded instanceof String data && data.length()<=16384)try{var root=com.google.gson.JsonParser.parseString(new String(Base64.getDecoder().decode(data),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();var textures=root.getAsJsonObject("textures");if(textures!=null && textures.has("SKIN")){var skin=textures.getAsJsonObject("SKIN");String url=skin.get("url").getAsString();boolean slim=skin.has("metadata") && "slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString());return new ProfileSkin(url,slim);}}catch(RuntimeException ignored){}}
        }return new ProfileSkin("",false);
    }
    static String key(Object value){if(value==null)return "";if(value instanceof Keyed keyed)return keyed.getKey().getKey();if(value instanceof NamespacedKey key)return key.getKey();return value.toString().toLowerCase(Locale.ROOT);}
    static ActorAppearance capture(LivingEntity entity){
        var t=new HashMap<String,String>();var bones=new HashMap<String,Vec3>();String type=entity.getType().name();
        put(t,"variant",optional(entity,"getVariant"));
        switch(type){
            case "CAT"->put(t,"variant",optional(entity,"getCatType"));
            case "RABBIT"->put(t,"variant",optional(entity,"getRabbitType"));
            case "FOX"->put(t,"variant",optional(entity,"getFoxType"));
            case "PANDA"->put(t,"variant",optional(entity,"getMainGene"));
            case "LLAMA","TRADER_LLAMA"->put(t,"variant",optional(entity,"getColor"));
            case "HORSE"->{put(t,"variant",optional(entity,"getColor"));put(t,"markings",optional(entity,"getStyle"));}
            case "MOOSHROOM","MUSHROOM_COW"->put(t,"variant",optional(entity,"getVariant"));
            case "VILLAGER","ZOMBIE_VILLAGER"->{put(t,"biome",optional(entity,"getVillagerType"));put(t,"profession",optional(entity,type.equals("VILLAGER")?"getProfession":"getVillagerProfession"));put(t,"level",optional(entity,"getVillagerLevel"));}
            case "TROPICAL_FISH"->{put(t,"pattern",optional(entity,"getPattern"));color(t,"bodyColor",optional(entity,"getBodyColor"));color(t,"patternColor",optional(entity,"getPatternColor"));}
            case "COPPER_GOLEM"->{put(t,"variant",optional(entity,"getWeatheringState"));put(t,"golemState",optional(entity,"getGolemState"));}
            case "ARMOR_STAND"->{for(var entry:Map.of("head","getHeadPose","body","getBodyPose","left_arm","getLeftArmPose","right_arm","getRightArmPose","left_leg","getLeftLegPose","right_leg","getRightLegPose").entrySet()){Object pose=optional(entity,entry.getValue());if(pose instanceof EulerAngle e)bones.put(entry.getKey(),new Vec3(e.getX(),e.getY(),e.getZ()));}put(t,"hasArms",optional(entity,"hasArms"));put(t,"basePlate",optional(entity,"hasBasePlate"));put(t,"small",optional(entity,"isSmall"));}
            case "SHULKER"->put(t,"variant",optional(entity,"getColor"));
            default->{}
        }
        for(var entry:Map.ofEntries(Map.entry("size","getSize"),Map.entry("puff","getPuffState"),Map.entry("sitting","isSitting"),Map.entry("sleeping","isSleeping"),Map.entry("angry","isAngry"),Map.entry("tamed","isTamed"),Map.entry("leftHanded","isLeftHanded"),Map.entry("usingItem","isHandRaised"),Map.entry("leftHorn","hasLeftHorn"),Map.entry("rightHorn","hasRightHorn"),Map.entry("saddled","hasSaddle"),Map.entry("derp","isDerp")).entrySet())put(t,entry.getKey(),optional(entity,entry.getValue()));
        color(t,"collarColor",optional(entity,"getCollarColor"));
        if(entity instanceof Enderman enderman && enderman.getCarriedBlock()!=null)t.put("carriedBlock",enderman.getCarriedBlock().getAsString());
        if(entity instanceof Player player)t.put("leftHanded",Boolean.toString(player.getMainHand()==org.bukkit.inventory.MainHand.LEFT));
        Object parts=optional(entity,"getSkinParts");if(parts!=null)for(var entry:Map.of("hat","hasHatsEnabled","jacket","hasJacketEnabled","left_sleeve","hasLeftSleeveEnabled","right_sleeve","hasRightSleeveEnabled","left_pants","hasLeftPantsEnabled","right_pants","hasRightPantsEnabled").entrySet())put(t,"skin_"+entry.getKey(),optional(parts,entry.getValue()));
        return new ActorAppearance(t,bones);
    }
    static void put(Map<String,String> target,String key,Object value){if(value!=null)target.put(key,key(value));}
    static void color(Map<String,String> target,String name,Object value){if(value instanceof DyeColor dye)target.put(name,Integer.toString(dye.getColor().asRGB()));else if(value instanceof Color color)target.put(name,Integer.toString(color.asRGB()));}
}
