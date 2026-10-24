package com.wenkrang.famara.render.scene;

import com.google.gson.stream.JsonReader;
import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.math.Vec3;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable compact vertex data extracted from the exact vanilla 26.2 model definitions. No game classes. */
public final class ActorModelCatalog {
    public record Part(String name,float[] pose,float[] faces,List<Part> children,boolean visible,boolean skipDraw){}
    private final Map<String,Part> models;
    private final long retainedBytes;
    private ActorModelCatalog(Map<String,Part> models,long retained){this.models=Map.copyOf(models);retainedBytes=retained;}
    public long retainedBytes(){return retainedBytes;}
    public Set<String> models(){return models.keySet();}
    public boolean has(String model){return models.containsKey(model);}
    public Part require(String model){Part p=models.get(model);if(p==null)throw new IllegalArgumentException("Missing 26.2 actor model: "+model);return p;}
    public static ActorModelCatalog load() {
        try(InputStream input=Objects.requireNonNull(ActorModelCatalog.class.getResourceAsStream("/render-actors/models-26.2.json"),"Bundled actor geometry missing");var reader=new JsonReader(new InputStreamReader(input,StandardCharsets.UTF_8))){
            Map<String,Part> models=new HashMap<>();long[] bytes={0};reader.beginObject();while(reader.hasNext()){String name=reader.nextName();if(name.equals("models")){reader.beginObject();while(reader.hasNext()){if(models.size()>=512)throw new IOException("Too many vanilla layers");String key=reader.nextName();models.put(key,part(reader,0,bytes));}reader.endObject();}else if(name.equals("version")){if(!reader.nextString().equals("26.2"))throw new IOException("Wrong entity geometry version");}else reader.skipValue();}reader.endObject();if(models.isEmpty() || bytes[0]>8L*1024*1024)throw new IOException("Actor catalog budget exceeded");return new ActorModelCatalog(models,bytes[0]+models.size()*192L);
        }catch(IOException error){throw new IllegalStateException("Vanilla actor catalog unavailable",error);}
    }
    private static Part part(JsonReader in,int depth,long[] bytes)throws IOException {
        if(depth>32 || bytes[0]>8L*1024*1024)throw new IOException("Actor hierarchy exceeds budget");String name="";boolean visible=true,skipDraw=false;float[] pose=null,faces=null;List<Part> children=new ArrayList<>();
        in.beginObject();while(in.hasNext())switch(in.nextName()){
            case "name"->name=in.nextString();
            case "visible"->visible=in.nextBoolean();
            case "skipDraw"->skipDraw=in.nextBoolean();
            case "pose"->{pose=new float[9];in.beginArray();for(int i=0;i<9;i++)pose[i]=number(in);in.endArray();}
            case "faces"->{List<float[]> polygons=new ArrayList<>();in.beginArray();while(in.hasNext()){if(polygons.size()>=2048)throw new IOException("Model part too complex");float[] polygon=new float[20];in.beginArray();for(int v=0;v<4;v++){in.beginArray();for(int c=0;c<5;c++)polygon[v*5+c]=number(in);in.endArray();}in.endArray();polygons.add(polygon);}in.endArray();faces=new float[polygons.size()*20];for(int i=0;i<polygons.size();i++)System.arraycopy(polygons.get(i),0,faces,i*20,20);}
            case "children"->{in.beginArray();while(in.hasNext()){if(children.size()>=128)throw new IOException("Actor child count exceeded");children.add(part(in,depth+1,bytes));}in.endArray();}
            default->in.skipValue();
        }in.endObject();if(name.length()>64 || pose==null || faces==null)throw new IOException("Incomplete model part");bytes[0]+=256L+4L*(pose.length+faces.length)+children.size()*16L;return new Part(name,pose,faces,List.copyOf(children),visible,skipDraw);
    }
    private static float number(JsonReader in)throws IOException{float v=(float)in.nextDouble();if(!Float.isFinite(v) || Math.abs(v)>16384)throw new IOException("Invalid model coordinate");return v;}
    public String model(FrozenActor a){
        String base=VanillaActorRegistry.model(a.type());String variant=a.appearance().value("variant",a.variant()).toLowerCase(Locale.ROOT);
        if(a.type().equals("PLAYER") || a.type().equals("MANNEQUIN"))base=a.slim()?"player_slim":"player";
        if(Set.of("COW","PIG","CHICKEN").contains(a.type()) && Set.of("cold","warm").contains(variant) && has("minecraft:"+variant+"_"+base+"#main"))base=variant+"_"+base;
        if(a.type().equals("PUFFERFISH"))base="pufferfish_"+switch((int)a.appearance().number("puff",0)){case 1->"medium";case 2->"big";default->"small";};
        if(a.type().equals("TROPICAL_FISH") && Set.of("flopper","stripey","glitter","blockfish","betty","clayfish").contains(a.appearance().value("pattern","")))base="tropical_fish_large";
        if(a.type().equals("SALMON") && Set.of("small","large").contains(variant))base="salmon_"+variant;
        if(a.type().equals("SULFUR_CUBE") && a.baby())base="sulfur_cube_small";
        if(a.type().equals("ARMOR_STAND") && a.appearance().flag("small"))base="armor_stand_small";
        if(a.type().equals("ZOMBIE_NAUTILUS") && variant.equals("coral"))base="zombie_nautilus_coral";
        if(a.type().equals("COPPER_GOLEM") && Set.of("sitting","running","star").contains(a.appearance().value("golemState","")))base="copper_golem_"+a.appearance().value("golemState","");
        if(a.baby() && has("minecraft:"+base+"_baby#main"))base+="_baby";else if(a.baby() && has("minecraft:"+VanillaActorRegistry.model(a.type())+"_baby#main"))base=VanillaActorRegistry.model(a.type())+"_baby";
        return "minecraft:"+base+"#main";
    }
    public double scale(FrozenActor a){return switch(a.type()){
        case "PLAYER","MANNEQUIN"->.9375;
        case "SLIME","MAGMA_CUBE"->Math.max(1,Math.min(16,a.appearance().number("size",1)));
        case "SULFUR_CUBE"->Math.max(1,Math.min(2,a.appearance().number("size",a.baby()?1:2)))*(a.baby()?1:.5)*.999;
        case "PHANTOM"->1+.15*Math.max(0,Math.min(32,a.appearance().number("size",0)));
        default->1;
    };}
    public double origin(FrozenActor a){return a.type().equals("SULFUR_CUBE")?24.016-((a.baby()?1.24:.98)-1.0/16)*16:24.016;}
    /** No textures, skin downloads or quad allocation. Unknown item extents retain the actor. */
    public ActorBounds conservativeBounds(FrozenActor a){
        if(a.equipment().get("MAINHAND")!=null || a.equipment().get("OFFHAND")!=null || !a.appearance().value("carriedBlock","").isEmpty())return null;
        String main=model(a),base=main.substring(0,main.indexOf('#'));Set<String> layers=new HashSet<>();
        for(String key:models.keySet())if(key.startsWith(base+"#"))layers.add(key);
        for(var entry:a.equipment().slots().entrySet()){
            String slot=entry.getKey(),material=entry.getValue().material(),extra;
            switch(slot){
                case "HEAD","CHEST","LEGS","FEET"->{
                    if(slot.equals("CHEST") && material.equals("minecraft:elytra"))extra="minecraft:elytra"+(a.baby()?"_baby":"")+"#main";
                    else {String suffix=switch(slot){case "HEAD"->"helmet";case "CHEST"->"chestplate";case "LEGS"->"leggings";default->"boots";};if(!material.endsWith("_"+suffix))return null;extra=base+"#"+suffix;}
                }
                case "BODY"->{String family=switch(a.type()){case "HORSE"->"horse_armor";case "SKELETON_HORSE","ZOMBIE_HORSE"->"undead_horse_armor";case "WOLF"->"wolf_armor";case "NAUTILUS","ZOMBIE_NAUTILUS"->"nautilus_armor";case "LLAMA","TRADER_LLAMA"->"llama"+(a.baby()?"_baby":"");case "HAPPY_GHAST"->"happy_ghast"+(a.baby()?"_baby":"")+"_harness";default->"";};if(family.isEmpty())return null;extra="minecraft:"+family+(a.type().equals("LLAMA") || a.type().equals("TRADER_LLAMA")?"#decor":"#main");}
                case "SADDLE"->extra="minecraft:"+a.type().toLowerCase(Locale.ROOT)+"#saddle";
                default->{return null;}
            }
            if(!has(extra))return null;layers.add(extra);
        }
        if(a.appearance().flag("saddled")){String saddle="minecraft:"+a.type().toLowerCase(Locale.ROOT)+"#saddle";if(has(saddle))layers.add(saddle);}
        // Trader llama decoration uses the llama rather than trader-llama family.
        if(a.type().equals("TRADER_LLAMA"))layers.add("minecraft:llama"+(a.baby()?"_baby":"")+"#decor");
        double factor=scale(a)*a.scale()*(a.baby() && !main.contains("_baby#") && !a.type().equals("SULFUR_CUBE")?.5:1)/16;
        double[] bounds={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        for(String key:layers){if(!has(key))return null;bounds(a,require(key),new Matrix4d(),factor,bounds);}
        return ActorBounds.finish(bounds);
    }
    private void bounds(FrozenActor a,Part p,Matrix4d parent,double factor,double[] bounds){
        // Include even hidden layers/parts. Overestimating can only retain extra actors.
        Matrix4d matrix=pose(a,p,parent);Vector3d v=new Vector3d();double angle=-Math.toRadians(a.bodyYaw()),cos=Math.cos(angle),sin=Math.sin(angle);
        for(int i=0;i<p.faces.length;i+=5){matrix.transformPosition(p.faces[i],p.faces[i+1],p.faces[i+2],v);double x=v.x*factor,y=(origin(a)-v.y)*factor,z=-v.z*factor;ActorBounds.include(bounds,a.position().x()+cos*x+sin*z,a.position().y()+y,a.position().z()-sin*x+cos*z);}
        for(Part child:p.children)bounds(a,child,matrix,factor,bounds);
    }
    public Map<String,Matrix4d> emit(ActorModelBuilder builder,FrozenActor actor,String model,ResourceKey texture,Texture image,int tint,int layer){return emit(builder,actor,model,texture,image,tint,layer,1);}
    public Map<String,Matrix4d> emit(ActorModelBuilder builder,FrozenActor actor,String model,ResourceKey texture,Texture image,int tint,int layer,double opacity){
        builder.texture(texture,image);Map<String,Matrix4d> bones=new HashMap<>();emit(builder,actor,require(model),new Matrix4d(),texture,tint,layer,opacity,bones);return bones;
    }
    private void emit(ActorModelBuilder b,FrozenActor a,Part p,Matrix4d parent,ResourceKey texture,int tint,int layer,double opacity,Map<String,Matrix4d> bones){
        if(hidden(a,p.name(),layer) || !visible(a,p))return;Matrix4d matrix=pose(a,p,parent);bones.putIfAbsent(p.name(),matrix);
        for(int f=0;!p.skipDraw && f<p.faces.length;f+=20){List<Vec3> positions=new ArrayList<>(4);List<BakedQuad.Uv> uv=new ArrayList<>(4);for(int v=0;v<4;v++){int i=f+v*5;Vector3d point=matrix.transformPosition(p.faces[i],p.faces[i+1],p.faces[i+2],new Vector3d());positions.add(new Vec3(point.x,origin(a)-point.y,-point.z));uv.add(new BakedQuad.Uv(p.faces[i+3],p.faces[i+4]));}b.face(texture,positions,uv,tint,layer,opacity);}
        for(Part child:p.children)emit(b,a,child,matrix,texture,tint,layer,opacity,bones);
    }
    private static boolean visible(FrozenActor a,Part part){
        String name=part.name();boolean player=a.type().equals("PLAYER") || a.type().equals("MANNEQUIN");
        if(player && Set.of("hat","jacket","right_sleeve","left_sleeve","right_pants","left_pants").contains(name))return !a.appearance().traits().containsKey("skin_"+name) || a.appearance().flag("skin_"+name);
        if(Set.of("EVOKER","ILLUSIONER","PILLAGER","VINDICATOR").contains(a.type())){boolean holding=a.equipment().get("MAINHAND")!=null || a.equipment().get("OFFHAND")!=null;if(name.equals("arms"))return !holding;if(name.equals("right_arm") || name.equals("left_arm"))return holding;}
        return part.visible();
    }
    private static boolean hidden(FrozenActor a,String name,int layer){
        if(a.type().equals("ARMOR_STAND") && layer==0){if((name.equals("right_arm") || name.equals("left_arm")) && !a.appearance().flag("hasArms"))return true;if(name.equals("base_plate") && a.appearance().traits().containsKey("basePlate") && !a.appearance().flag("basePlate"))return true;}
        if(a.type().equals("GOAT")){if(name.equals("left_horn") && a.appearance().traits().containsKey("leftHorn") && !a.appearance().flag("leftHorn"))return true;if(name.equals("right_horn") && a.appearance().traits().containsKey("rightHorn") && !a.appearance().flag("rightHorn"))return true;}
        return false;
    }
    private static Matrix4d pose(FrozenActor a,Part part,Matrix4d parent){
        float[] p=part.pose;double x=p[0],y=p[1],z=p[2],rx=p[3],ry=p[4],rz=p[5];String name=part.name;
        Vec3 explicit=a.appearance().bones().get(name);
        if(explicit!=null){rx=explicit.x();ry=explicit.y();rz=explicit.z();}
        else if(name.equals("head") || name.equals("head_parts")){rx+=Math.toRadians(a.pitch());ry+=Math.toRadians(a.headYaw()-a.bodyYaw());}
        boolean left=name.startsWith("left"),arm=name.equals("right_arm") || name.equals("left_arm"),leg=name.endsWith("leg");
        if(arm || leg){rx+=(left?-1:1)*(arm?1:-1)*a.limbSwing();if(arm){String slot=(left==a.equipment().leftHanded())?"MAINHAND":"OFFHAND";if(a.equipment().get(slot)!=null)rx-=a.appearance().flag("usingItem")?1.1:.2;}}
        if(a.pose().equals("CROUCHING")){if(name.equals("body")){rx+=.5;y+=3.2;}if(name.equals("head"))y+=4.2;if(arm){y+=3.2;rx+=.4;}if(name.equals("right_leg") || name.equals("left_leg")){y+=.2;z+=4;}}
        return new Matrix4d(parent).translate(x,y,z).rotateZYX(rz,ry,rx).scale(p[6],p[7],p[8]);
    }
}
