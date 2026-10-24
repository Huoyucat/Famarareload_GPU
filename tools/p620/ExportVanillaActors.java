package baseline.actors;

import com.google.gson.*;
import net.minecraft.client.model.geom.*;
import net.minecraft.client.model.geom.builders.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;

/** Build-time only. Exports immutable vertex/UV data; no game classes enter the plugin. */
public final class ExportVanillaActors {
    static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    static Map<String,Object> part(String name,ModelPart part)throws Exception{
        var out=new LinkedHashMap<String,Object>();out.put("name",name);out.put("pose",List.of(part.x,part.y,part.z,part.xRot,part.yRot,part.zRot,part.xScale,part.yScale,part.zScale));
        var polygons=new ArrayList<Object>();
        for(var cube:(List<ModelPart.Cube>)field(part,"cubes"))for(var polygon:cube.polygons){if(Arrays.stream(polygon.vertices()).anyMatch(v->!Float.isFinite(v.u()) || !Float.isFinite(v.v())))continue;var vertices=new ArrayList<Object>();for(var v:polygon.vertices())vertices.add(List.of(v.x(),v.y(),v.z(),v.u(),v.v()));polygons.add(vertices);}
        out.put("visible",part.visible);out.put("skipDraw",part.skipDraw);out.put("faces",polygons);var children=new ArrayList<Object>();for(var child:((Map<String,ModelPart>)field(part,"children")).entrySet())children.add(part(child.getKey(),child.getValue()));out.put("children",children);return out;
    }
    static final Map<String,String> CLASSES=new HashMap<>();
    static String camel(String value){StringBuilder out=new StringBuilder();for(String w:value.split("_"))if(!w.isEmpty())out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));return out.toString();}
    static boolean neutral(String key,ModelPart root) {
        String base=key.substring(key.indexOf(':')+1,key.indexOf('#'));String name=camel(base)+"Model";
        if(!key.endsWith("#main"))name="HumanoidModel";
        name=switch(base){case "giant"->"ZombieModel";case "elder_guardian"->"GuardianModel";case "player","player_slim"->"PlayerModel";case "wolf"->"AdultWolfModel";case "wolf_baby"->"BabyWolfModel";case "cat","ocelot"->"AdultCatModel";case "cat_baby","ocelot_baby"->"BabyCatModel";case "trader_llama"->"LlamaModel";case "camel_husk"->"CamelModel";case "cave_spider"->"SpiderModel";case "glow_squid"->"SquidModel";case "husk"->"ZombieModel";case "mooshroom"->"CowModel";case "wither"->"WitherBossModel";case "wither_skeleton","stray","parched"->"SkeletonModel";case "piglin_brute"->"PiglinModel";case "zoglin"->"HoglinModel";case "mule","skeleton_horse","zombie_horse"->"HorseModel";case "evoker","illusioner","vindicator","pillager"->"IllagerModel";default->name;};
        if(!key.endsWith("#main") && !Set.of("cape","ears").contains(key.substring(key.indexOf('#')+1)))name="HumanoidModel";
        String path=CLASSES.get(name);try{if(path==null || Modifier.isAbstract(Class.forName(path).getModifiers())){String alternative=base.endsWith("_baby")?"Baby"+camel(base.substring(0,base.length()-5))+"Model":"Adult"+name;String found=CLASSES.get(alternative);if(found!=null)path=found;}}catch(ClassNotFoundException ignored){}if(path==null)return false;
        try {
            Class<?> type=Class.forName(path);if(Modifier.isAbstract(type.getModifiers()))return false;Object model;
            try{model=type.getConstructor(ModelPart.class).newInstance(root);}catch(NoSuchMethodException ignored){model=type.getConstructor(ModelPart.class,boolean.class).newInstance(root,base.equals("player_slim"));}
            Method setup=Arrays.stream(type.getMethods()).filter(m->m.getName().equals("setupAnim") && m.getParameterCount()==1 && m.getParameterTypes()[0]!=Object.class).min(Comparator.comparingInt(m->-depth(m.getParameterTypes()[0]))).orElse(null);if(setup==null)return true;
            Object state=setup.getParameterTypes()[0].getConstructor().newInstance();
            for(String field:List.of("ageScale","scale"))try{setup.getParameterTypes()[0].getField(field).setFloat(state,1);}catch(ReflectiveOperationException ignored){}
            try{setup.getParameterTypes()[0].getField("entityType").set(state,net.minecraft.world.entity.EntityTypes.class.getField(base.toUpperCase(Locale.ROOT)).get(null));}catch(ReflectiveOperationException ignored){}
            setup.invoke(model,state);return true;
        }catch(Throwable ignored){for(var part:root.getAllParts())part.resetPose();return false;}
    }
    static int depth(Class<?> t){return t.getSuperclass()==null?0:1+depth(t.getSuperclass());}
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Output JSON path required");Path output=Path.of(args[0]).toAbsolutePath().normalize();
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        try(var jar=new java.util.jar.JarFile(Path.of(LayerDefinitions.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toFile())){jar.stream().map(java.util.jar.JarEntry::getName).filter(n->n.startsWith("net/minecraft/client/model/") && n.endsWith("Model.class")).forEach(n->CLASSES.put(n.substring(n.lastIndexOf('/')+1,n.length()-6),n.substring(0,n.length()-6).replace('/','.')));}
        var models=new TreeMap<String,Object>();var posed=new ArrayList<String>();for(var entry:LayerDefinitions.createRoots().entrySet()){String key=entry.getKey().toString();var root=entry.getValue().bakeRoot();if(neutral(key,root))posed.add(key);models.put(key,part("root",root));}
        var doc=new LinkedHashMap<String,Object>();doc.put("format",1);doc.put("version","26.2");doc.put("models",models);doc.put("neutralPosed",posed);Files.writeString(output,new Gson().toJson(doc)+"\n");System.out.println("layers="+models.size());for(var k:models.keySet())System.out.println(k);
    }
}
