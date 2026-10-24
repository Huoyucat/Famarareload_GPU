package com.wenkrang.famara.render.scene;
import java.util.*;
import com.wenkrang.famara.render.math.Vec3;
/** Bounded, immutable species/pose traits. All API objects are converted on the owner. */
public record ActorAppearance(Map<String,String> traits,Map<String,Vec3> bones) {
    public static final ActorAppearance EMPTY=new ActorAppearance(Map.of(),Map.of());
    public ActorAppearance{traits=Map.copyOf(traits);bones=Map.copyOf(bones);if(traits.size()>32 || bones.size()>24 || traits.entrySet().stream().anyMatch(e->e.getKey().length()>64 || e.getValue().length()>128))throw new IllegalArgumentException("Actor traits exceed limits");}
    public String value(String key,String fallback){return traits.getOrDefault(key,fallback);}
    public boolean flag(String key){return Boolean.parseBoolean(value(key,"false"));}
    public double number(String key,double fallback){try{double n=Double.parseDouble(value(key,""));return Double.isFinite(n)?n:fallback;}catch(NumberFormatException ignored){return fallback;}}
}
