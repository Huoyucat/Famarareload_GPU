package com.wenkrang.famara.render.scene;

import com.google.gson.*;
import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.math.Vec3;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Quaterniond;
import com.wenkrang.famara.render.math.Direction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Vanilla item-definition graph, inherited display transforms, block geometry and extruded alpha sprites. */
public final class ItemModelRenderer {
    private static final AssetLimits LIMITS=new AssetLimits(128,131072,4L*1024*1024,65536,4096,4096,32,32,100000,64);
    private record Choice(ResourceKey model,JsonObject special,Matrix4d transform,List<Integer> tints){}
    private record Prepared(BakedModel model,Matrix4d display,Matrix4d transform,List<Integer> tints){}
    private final ActorResources source;
    private final LinkedHashMap<String,List<Prepared>> cache=new LinkedHashMap<>(16,.75f,true);
    private long cacheBytes;
    public ItemModelRenderer(ActorResources source){this.source=source;}
    public void append(ActorModelBuilder builder,FrozenActor actor,FrozenItem item,Matrix4d attachment,boolean left,int layer){
        appendContext(builder,actor,item,attachment,left,layer,left?"thirdperson_lefthand":"thirdperson_righthand");
    }
    public void appendHead(ActorModelBuilder builder,FrozenActor actor,FrozenItem item,Matrix4d attachment,int layer){appendContext(builder,actor,item,attachment,false,layer,"head");}
    private void appendContext(ActorModelBuilder builder,FrozenActor actor,FrozenItem item,Matrix4d attachment,boolean left,int layer,String context){
        boolean using=actor.appearance().flag("usingItem");
        String cacheKey=item.material()+"|"+item.itemModel()+"|"+context+"|"+item.charged()+"|"+using+"|"+item.tintRgb();
        var prepared=cache.get(cacheKey);if(prepared==null){prepared=prepare(item,context,using);long bytes=prepared.stream().mapToLong(p->512L+p.model.quads().size()*768L).sum();if(bytes<=262144){while(!cache.isEmpty() && (cacheBytes+bytes>262144 || cache.size()>=32)){var iterator=cache.entrySet().iterator();var old=iterator.next();cacheBytes-=old.getValue().stream().mapToLong(p->512L+p.model.quads().size()*768L).sum();iterator.remove();}cache.put(cacheKey,prepared);cacheBytes+=bytes;}}
        for(Prepared p:prepared){Matrix4d matrix=new Matrix4d(attachment).scale(16).mul(p.display).translate(-.5,-.5,-.5).mul(p.transform);
            for(var quad:p.model.quads()){
                Texture texture=source.texture(quad.texture());builder.texture(quad.texture(),texture);List<Vec3> vertices=new ArrayList<>(4);
                for(Vec3 point:quad.positions()){Vector3d v=matrix.transformPosition(point.x(),point.y(),point.z(),new Vector3d());vertices.add(new Vec3(v.x,source.catalog().origin(actor)-v.y,-v.z));}
                int tint=quad.tintIndex()>=0 && quad.tintIndex()<p.tints.size()?p.tints.get(quad.tintIndex()):quad.colorMultiplier();builder.face(quad.texture(),vertices,quad.uv(),tint,layer,quad.opacity(),quad.forceTranslucent(),quad.lightEmission());if(item.enchanted()){ResourceKey glint=EquipmentModelRenderer.glint(quad.texture(),source);builder.texture(glint,source.texture(glint));builder.face(glint,vertices,quad.uv(),0xffffff,layer+8,1);}
            }
        }
    }
    private List<Prepared> prepare(FrozenItem item,String context,boolean using){
        ResourceKey id=ResourceKey.parse(item.itemModel().isEmpty()?item.material():item.itemModel());String path=id.file("items",".json");if(!id.namespace().equals("minecraft") && !source.has(path) && !source.has(new ResourceKey(id.namespace(),"item/"+id.path()).file("models",".json")))id=ResourceKey.parse(item.material());path=id.file("items",".json");JsonObject definition=json(path);List<Choice> choices=new ArrayList<>();
        if(definition==null){ResourceKey model=new ResourceKey(id.namespace(),"item/"+id.path());if(!source.has(model.file("models",".json")))throw new IllegalStateException("Item definition missing: "+id);choices.add(new Choice(model,null,new Matrix4d(),List.of()));}
        else select(definition.getAsJsonObject("model"),item,context,using,new Matrix4d(),choices,0);
        List<Prepared> result=new ArrayList<>();for(Choice choice:choices){var files=new HashMap<String,byte[]>();Raw raw=raw(choice.model,files,new HashSet<>(),0);BakedModel baked;
            if(choice.special!=null)baked=special(choice.special,choice.model);
            else if(raw.generated)baked=sprite(choice.model,raw.textures);
            else baked=new ModelCompiler(ResourcePack.memory("actor-item",files,LIMITS),LIMITS,source::texture).compile(choice.model);
            Matrix4d display=display(raw.display,context);result.add(new Prepared(baked,display,choice.transform,choice.tints));
        }return List.copyOf(result);
    }
    private JsonObject json(String path){byte[] data=source.read(path);if(data==null)return null;if(data.length>131072)throw new IllegalStateException("Oversized item model");return JsonParser.parseString(new String(data,StandardCharsets.UTF_8)).getAsJsonObject();}
    private void select(JsonObject node,FrozenItem item,String context,boolean using,Matrix4d parent,List<Choice> out,int depth){
        if(node==null || depth>24 || out.size()>16)throw new IllegalStateException("Item graph exceeds budget");Matrix4d transform=new Matrix4d(parent);if(node.has("transformation"))transform.mul(transformation(node.getAsJsonObject("transformation")));
        String type=node.get("type").getAsString();switch(type){
            case "minecraft:empty","minecraft:bundle/selected_item"->{}
            case "minecraft:model"->{var tints=new ArrayList<Integer>();if(node.has("tints"))for(var tint:node.getAsJsonArray("tints"))tints.add(tint(tint.getAsJsonObject(),item));out.add(new Choice(ResourceKey.parse(node.get("model").getAsString()),null,transform,List.copyOf(tints)));}
            case "minecraft:special"->out.add(new Choice(ResourceKey.parse(node.get("base").getAsString()),node.getAsJsonObject("model"),transform,List.of(item.tintRgb())));
            case "minecraft:composite"->{for(var child:node.getAsJsonArray("models"))select(child.getAsJsonObject(),item,context,using,transform,out,depth+1);}
            case "minecraft:condition"->{String property=node.get("property").getAsString();boolean value=property.endsWith("using_item")?using:property.endsWith("has_component") && item.charged();select(node.getAsJsonObject(value?"on_true":"on_false"),item,context,using,transform,out,depth+1);}
            case "minecraft:select"->{String property=node.get("property").getAsString();String value=property.endsWith("display_context")?context:property.endsWith("charge_type")?(item.charged()?"arrow":"none"):"";JsonObject chosen=null;for(var entry:node.getAsJsonArray("cases")){JsonObject row=entry.getAsJsonObject();JsonElement when=row.get("when");if(when.isJsonArray()?java.util.stream.StreamSupport.stream(when.getAsJsonArray().spliterator(),false).anyMatch(v->v.getAsString().equals(value)):when.getAsString().equals(value)){chosen=row.getAsJsonObject("model");break;}}if(chosen==null && node.has("fallback"))chosen=node.getAsJsonObject("fallback");if(chosen!=null)select(chosen,item,context,using,transform,out,depth+1);}
            case "minecraft:range_dispatch"->{JsonObject chosen=node.has("fallback")?node.getAsJsonObject("fallback"):null;double value=0;for(var entry:node.getAsJsonArray("entries")){JsonObject row=entry.getAsJsonObject();if(row.get("threshold").getAsDouble()<=value)chosen=row.getAsJsonObject("model");}if(chosen!=null)select(chosen,item,context,using,transform,out,depth+1);}
            default->throw new IllegalStateException("Unsupported vanilla item graph: "+type);
        }
    }
    private static int tint(JsonObject tint,FrozenItem item){String type=tint.get("type").getAsString();if(type.endsWith("constant"))return tint.get("value").getAsInt()&0xffffff;if(item.tintRgb()!=0xffffff)return item.tintRgb();return switch(type){case "minecraft:grass"->0x91bd59;case "minecraft:potion"->tint.has("default")?tint.get("default").getAsInt()&0xffffff:0x385dc6;default->tint.has("default")?tint.get("default").getAsInt()&0xffffff:0xffffff;};}
    private record Raw(Map<String,String> textures,JsonObject display,boolean generated){}
    private Raw raw(ResourceKey model,Map<String,byte[]> files,Set<ResourceKey> chain,int depth){
        if(depth>32 || !chain.add(model) || files.size()>=128)throw new IllegalStateException("Item parent chain exceeds limit");String path=model.file("models",".json");byte[] bytes=source.read(path);if(bytes==null)throw new IllegalStateException("Item model missing: "+model);files.put(path,bytes);if(files.values().stream().mapToLong(b->b.length).sum()>524288)throw new IllegalStateException("Item source graph too large");JsonObject object=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();Map<String,String> textures=new HashMap<>();JsonObject display=new JsonObject();boolean generated=false;
        if(object.has("parent")){String parent=object.get("parent").getAsString();if(parent.equals("builtin/generated") || parent.equals("minecraft:builtin/generated"))generated=true;else if(!parent.equals("builtin/entity") && !parent.equals("minecraft:builtin/entity")){Raw inherited=raw(ResourceKey.parse(parent),files,chain,depth+1);textures.putAll(inherited.textures);for(var e:inherited.display.entrySet())display.add(e.getKey(),e.getValue());generated=inherited.generated;}}
        if(object.has("textures"))for(var entry:object.getAsJsonObject("textures").entrySet())textures.put(entry.getKey(),entry.getValue().isJsonObject()?entry.getValue().getAsJsonObject().get("sprite").getAsString():entry.getValue().getAsString());if(object.has("display"))for(var entry:object.getAsJsonObject("display").entrySet())display.add(entry.getKey(),entry.getValue());chain.remove(model);return new Raw(Map.copyOf(textures),display,generated);
    }
    private static Matrix4d display(JsonObject display,String context){JsonObject transform=display.has(context)?display.getAsJsonObject(context):null;if(transform==null && context.endsWith("lefthand") && display.has("thirdperson_righthand"))transform=display.getAsJsonObject("thirdperson_righthand");Matrix4d m=new Matrix4d();if(transform==null)return m;Vector3d t=vector(transform,"translation",new Vector3d()).div(16),r=vector(transform,"rotation",new Vector3d()).mul(Math.PI/180),s=vector(transform,"scale",new Vector3d(1));boolean left=context.endsWith("lefthand");return m.translate(left?-t.x:t.x,t.y,t.z).rotateXYZ(r.x,left?-r.y:r.y,left?-r.z:r.z).scale(s);}
    private static Vector3d vector(JsonObject object,String name,Vector3d fallback){if(!object.has(name))return fallback;var a=object.getAsJsonArray(name);return new Vector3d(a.get(0).getAsDouble(),a.get(1).getAsDouble(),a.get(2).getAsDouble());}
    private static Matrix4d transformation(JsonObject object){Matrix4d m=new Matrix4d().translate(vector(object,"translation",new Vector3d()));for(String name:List.of("left_rotation","right_rotation")){if(name.equals("right_rotation"))m.scale(vector(object,"scale",new Vector3d(1)));if(object.has(name)){var q=object.getAsJsonArray(name);if(q.size()==4)m.rotate(new Quaterniond(q.get(0).getAsDouble(),q.get(1).getAsDouble(),q.get(2).getAsDouble(),q.get(3).getAsDouble()).normalize());}}return m;}
    private BakedModel sprite(ResourceKey model,Map<String,String> bindings){List<BakedQuad> faces=new ArrayList<>();for(int layer=0;layer<8;layer++){String binding=bindings.get("layer"+layer);if(binding==null)break;Set<String> visited=new HashSet<>();while(binding.startsWith("#")){if(!visited.add(binding))throw new IllegalStateException("Item texture cycle");binding=Objects.requireNonNull(bindings.get(binding.substring(1)));}ResourceKey key=ResourceKey.parse(binding);Texture texture=source.texture(key);int w=texture.width(),h=texture.height();if(w>128 || h>128)throw new IllegalStateException("Generated item resolution too large");double z0=(7.5+layer*.01)/16,z1=(8.5+layer*.01)/16;
            add(faces,key,List.of(new Vec3(0,0,z1),new Vec3(1,0,z1),new Vec3(1,1,z1),new Vec3(0,1,z1)),List.of(new BakedQuad.Uv(0,1),new BakedQuad.Uv(1,1),new BakedQuad.Uv(1,0),new BakedQuad.Uv(0,0)),layer);
            add(faces,key,List.of(new Vec3(1,0,z0),new Vec3(0,0,z0),new Vec3(0,1,z0),new Vec3(1,1,z0)),List.of(new BakedQuad.Uv(1,1),new BakedQuad.Uv(0,1),new BakedQuad.Uv(0,0),new BakedQuad.Uv(1,0)),layer);
            for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(opaque(texture,x,y)){double l=(double)x/w,r=(double)(x+1)/w,top=1-(double)y/h,bottom=1-(double)(y+1)/h;var uv=Collections.nCopies(4,new BakedQuad.Uv((x+.5)/w,(y+.5)/h));if(!opaque(texture,x-1,y))add(faces,key,List.of(new Vec3(l,bottom,z0),new Vec3(l,bottom,z1),new Vec3(l,top,z1),new Vec3(l,top,z0)),uv,layer);if(!opaque(texture,x+1,y))add(faces,key,List.of(new Vec3(r,bottom,z1),new Vec3(r,bottom,z0),new Vec3(r,top,z0),new Vec3(r,top,z1)),uv,layer);if(!opaque(texture,x,y-1))add(faces,key,List.of(new Vec3(l,top,z1),new Vec3(r,top,z1),new Vec3(r,top,z0),new Vec3(l,top,z0)),uv,layer);if(!opaque(texture,x,y+1))add(faces,key,List.of(new Vec3(l,bottom,z0),new Vec3(r,bottom,z0),new Vec3(r,bottom,z1),new Vec3(l,bottom,z1)),uv,layer);}
        }if(faces.isEmpty())throw new IllegalStateException("Generated item has no texture layers: "+model);return new BakedModel(model,faces,false);}
    private static boolean opaque(Texture t,int x,int y){return x>=0 && y>=0 && x<t.width() && y<t.height() && t.sample((x+.5)/t.width(),(y+.5)/t.height(),0,0,Texture.Filter.NEAREST).a()>.01;}
    private static void add(List<BakedQuad> faces,ResourceKey key,List<Vec3> points,List<BakedQuad.Uv> uv,int tint){if(faces.size()>=4096)throw new IllegalStateException("Item geometry exceeds 4096 faces");var n=points.get(1).subtract(points.get(0)).cross(points.get(2).subtract(points.get(0))).normalized();faces.add(new BakedQuad(points,uv,key,n,Direction.closest(n),null,tint,true,false,null,0,0,BakedQuad.Topology.QUAD,faces.size(),0xffffff,1));}
    private BakedModel special(JsonObject special,ResourceKey model){String type=special.get("type").getAsString().replace("minecraft:","");String layer,texture;
        if(type.equals("banner") || type.equals("decorated_pot")){
            List<BakedQuad> faces=new ArrayList<>();String base=type.equals("banner")?"standing_banner":"decorated_pot_base",extra=type.equals("banner")?"standing_banner#flag":"decorated_pot_sides#main";
            ResourceKey first=ResourceKey.parse(type.equals("banner")?"minecraft:entity/banner/banner_base":"minecraft:entity/decorated_pot/decorated_pot_base"),second=ResourceKey.parse(type.equals("banner")?"minecraft:entity/banner/banner_base":"minecraft:entity/decorated_pot/decorated_pot_side");source.texture(first);source.texture(second);rawSpecial(source.catalog().require("minecraft:"+base+"#main"),new Matrix4d(),first,faces);int offset=faces.size();rawSpecial(source.catalog().require("minecraft:"+extra),new Matrix4d(),second,faces);
            int color=type.equals("banner")?dye(special.get("color").getAsString()):0xffffff;for(int i=offset;i<faces.size();i++){var q=faces.get(i);faces.set(i,new BakedQuad(q.positions(),q.uv(),q.texture(),q.normal(),q.face(),null,-1,q.shade(),q.forceTranslucent(),q.shadeDirectionOverride(),q.lightEmission(),q.layer(),q.topology(),q.surfaceGroup(),color,q.opacity()));}return new BakedModel(model,faces,false);
        }
        switch(type){
            case "shield"->{layer="shield#main";texture="entity/shield/shield_base_nopattern";}
            case "trident"->{layer="trident#main";texture="entity/trident/trident";}
            case "player_head"->{layer="player_head#main";texture="entity/player/wide/steve";}
            case "head"->{String kind=special.get("kind").getAsString();layer=switch(kind){case "dragon"->"dragon_skull";case "piglin"->"piglin_head";case "player"->"player_head";case "zombie"->"zombie_head";case "creeper"->"creeper_head";case "wither_skeleton"->"wither_skeleton_skull";default->"skeleton_skull";}+"#main";texture=switch(kind){case "dragon"->"entity/enderdragon/dragon";case "piglin"->"entity/piglin/piglin";case "player"->"entity/player/wide/steve";case "zombie"->"entity/zombie/zombie";case "creeper"->"entity/creeper/creeper";case "wither_skeleton"->"entity/skeleton/wither_skeleton";default->"entity/skeleton/skeleton";};}
            case "chest"->{layer="chest#main";String id=special.has("texture")?special.get("texture").getAsString().replace("minecraft:",""):"normal";texture="entity/chest/"+id;}
            case "shulker_box"->{layer="shulker_box#main";String id=special.has("texture")?special.get("texture").getAsString().replace("minecraft:",""):"shulker";texture="entity/shulker/"+id;}
            case "conduit"->{layer="conduit#shell";texture="entity/conduit/base";}
            case "banner"->{layer="standing_banner#main";texture="entity/banner/banner_base";}
            case "decorated_pot"->{layer="decorated_pot_base#main";texture="entity/decorated_pot/decorated_pot_base";}
            case "copper_golem_statue"->{String pose=special.has("pose")?special.get("pose").getAsString():"standing";layer="copper_golem"+(pose.equals("standing")?"":"_"+pose)+"#main";String full=special.get("texture").getAsString();ResourceKey id=ResourceKey.parse(full);texture=id.namespace()+":"+id.path().replaceFirst("^textures/","").replaceFirst("\\.png$","");}
            default->throw new IllegalStateException("Unsupported vanilla special item: "+type);
        }
        ResourceKey key=ResourceKey.parse(texture);source.texture(key);List<BakedQuad> faces=new ArrayList<>();rawSpecial(source.catalog().require("minecraft:"+layer),new Matrix4d(),key,faces);return new BakedModel(model,faces,false);
    }
    private static int dye(String color){return switch(color){case "orange"->0xf9801d;case "magenta"->0xc74ebd;case "light_blue"->0x3ab3da;case "yellow"->0xfed83d;case "lime"->0x80c71f;case "pink"->0xf38baa;case "gray"->0x474f52;case "light_gray"->0x9d9d97;case "cyan"->0x169c9c;case "purple"->0x8932b8;case "blue"->0x3c44aa;case "brown"->0x835432;case "green"->0x5e7c16;case "red"->0xb02e26;case "black"->0x1d1d21;default->0xf9fffe;};}
    private static void rawSpecial(ActorModelCatalog.Part part,Matrix4d parent,ResourceKey key,List<BakedQuad> out){float[] p=part.pose();Matrix4d m=new Matrix4d(parent).translate(p[0]/16.0,p[1]/16.0,p[2]/16.0).rotateZYX(p[5],p[4],p[3]).scale(p[6],p[7],p[8]);for(int f=0;f<part.faces().length;f+=20){List<Vec3> points=new ArrayList<>(4);List<BakedQuad.Uv> uv=new ArrayList<>(4);for(int v=0;v<4;v++){int i=f+v*5;var x=m.transformPosition(part.faces()[i]/16.0,part.faces()[i+1]/16.0,part.faces()[i+2]/16.0,new Vector3d());points.add(new Vec3(x.x,x.y,x.z));uv.add(new BakedQuad.Uv(part.faces()[i+3],part.faces()[i+4]));}var cross=points.get(1).subtract(points.get(0)).cross(points.get(2).subtract(points.get(0)));if(cross.dot(cross)>1e-18)add(out,key,points,uv,-1);}for(var child:part.children())rawSpecial(child,m,key,out);}
}
