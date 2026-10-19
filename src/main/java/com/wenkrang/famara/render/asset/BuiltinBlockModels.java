package com.wenkrang.famara.render.asset;

import com.wenkrang.famara.render.math.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** 原版非JSON渲染形状的静态分支。纹理仍从实际资源栈读取，不按粒子色填盒子。 */
final class BuiltinBlockModels {
    private static final Set<String> INVISIBLE=Set.of("air","cave_air","void_air","barrier","structure_void","light","moving_piston");
    private static final Map<String,Integer> DYES=Map.ofEntries(
            Map.entry("white",0xf9fffe),Map.entry("orange",0xf9801d),Map.entry("magenta",0xc74ebd),Map.entry("light_blue",0x3ab3da),
            Map.entry("yellow",0xfed83d),Map.entry("lime",0x80c71f),Map.entry("pink",0xf38baa),Map.entry("gray",0x474f52),
            Map.entry("light_gray",0x9d9d97),Map.entry("cyan",0x169c9c),Map.entry("purple",0x8932b8),Map.entry("blue",0x3c44aa),
            Map.entry("brown",0x835432),Map.entry("green",0x5e7c16),Map.entry("red",0xb02e26),Map.entry("black",0x1d1d21));
    static BlockDefinition compile(ResourceKey block,ModelCompiler compiler) {
        if(!block.namespace().equals("minecraft"))return null;
        String name=block.path();
        if(INVISIBLE.contains(name))return BlockDefinition.builtin(block.toString(),state->List.of());
        if(name.equals("water") || name.equals("lava")) {
            compiler.texture(ResourceKey.parse("minecraft:block/"+name+"_still"));
            compiler.texture(ResourceKey.parse("minecraft:block/"+name+"_flow"));
            return BlockDefinition.builtin(block.toString(),state->{throw new AssetException(AssetException.Code.UNSUPPORTED,state.toString(),"流体需要冻结的邻域数据");});
        }
        if(name.equals("chest") || name.equals("trapped_chest") || name.equals("ender_chest") || name.endsWith("copper_chest"))
            return chests(block,compiler);
        if(name.equals("shulker_box") || name.endsWith("_shulker_box"))return shulker(block,compiler);
        if(name.endsWith("_banner"))return banner(block,compiler);
        String head=skullTexture(name);
        if(head!=null)return skull(block,head,compiler);
        if(name.equals("conduit")) {
            ResourceKey texture=ResourceKey.parse("minecraft:entity/conduit/base");compiler.texture(texture);
            List<BakedQuad> q=new ArrayList<>();ModelGeometry.entityBox(q,texture,5,5,5,6,6,6,0,0,32,16,Set.of(),0xffffff);
            return fixed(block,compiler,q);
        }
        if(name.equals("end_portal") || name.equals("end_gateway")) {
            ResourceKey texture=ResourceKey.parse("minecraft:entity/end_portal/end_portal");compiler.texture(texture);
            List<BakedQuad> q=new ArrayList<>();
            if(name.equals("end_portal"))ModelGeometry.face(q,texture,Direction.UP,ModelGeometry.points(Direction.UP,0,0,0,16,12,16),new double[]{0,0,16,16},16,16,0xffffff,15);
            else for(Direction d:Direction.values())ModelGeometry.face(q,texture,d,ModelGeometry.points(d,0,0,0,16,16,16),new double[]{0,0,16,16},16,16,0xffffff,15);
            return fixed(block,compiler,q);
        }
        if(name.equals("decorated_pot"))return pot(block,compiler);
        if(name.endsWith("copper_golem_statue"))return statue(block,compiler);
        return null;
    }
    private static BlockDefinition fixed(ResourceKey key,ModelCompiler compiler,List<BakedQuad> quads) {
        compiler.reserveExternalGeometry(quads.size(),key.toString());BakedModel model=new BakedModel(key,quads,false);
        return BlockDefinition.builtin(key.toString(),s->List.of(model),List.of(model));
    }
    private static BlockDefinition chests(ResourceKey key,ModelCompiler c) {
        String stem=switch(key.path().replaceFirst("^waxed_","")) {
            case "chest"->"normal";case "trapped_chest"->"trapped";case "ender_chest"->"ender";
            case "copper_chest"->"copper";case "exposed_copper_chest"->"copper_exposed";
            case "weathered_copper_chest"->"copper_weathered";case "oxidized_copper_chest"->"copper_oxidized";
            default->throw new AssetException(AssetException.Code.UNSUPPORTED,key.toString(),"未知箱子变体");
        };
        Map<String,List<BakedModel>> states=new HashMap<>();
        for(String type:List.of("single","left","right")) {
            if(key.path().equals("ender_chest") && !type.equals("single"))continue;
            ResourceKey texture=ResourceKey.parse("minecraft:entity/chest/"+stem+(type.equals("single")?"":"_"+type));c.texture(texture);
            boolean single=type.equals("single"),left=type.equals("left");
            double x=single || !left?1:0,w=single?14:15;
            Set<Direction> omit=single?Set.of():Set.of(left?Direction.WEST:Direction.EAST);
            List<BakedQuad> q=new ArrayList<>();
            ModelGeometry.entityBox(q,texture,x,0,1,w,10,14,0,19,64,64,omit,0xffffff);
            ModelGeometry.entityBox(q,texture,x,9,1,w,5,14,0,0,64,64,omit,0xffffff);
            ModelGeometry.entityBox(q,texture,single?7:left?0:15,7,15,single?2:1,4,1,0,0,64,64,omit,0xffffff);
            BakedModel base=new BakedModel(key,q,false);
            for(String facing:List.of("north","east","south","west")) {
                BakedModel model=ModelGeometry.rotateY(base,yaw(facing));c.reserveExternalGeometry(model.quads().size(),key.toString());
                states.put(type+":"+facing,List.of(model));
            }
        }
        Map<String,List<BakedModel>> frozen=Map.copyOf(states);
        return BlockDefinition.builtin(key.toString(),s->required(frozen,s.properties().getOrDefault("type","single")+":"+s.properties().getOrDefault("facing","north"),key),frozen.values().stream().flatMap(List::stream).toList());
    }
    private static BlockDefinition shulker(ResourceKey key,ModelCompiler c) {
        String color=key.path().equals("shulker_box")?"":key.path().substring(0,key.path().length()-"_shulker_box".length());
        ResourceKey texture=ResourceKey.parse("minecraft:entity/shulker/shulker"+(color.isEmpty()?"":"_"+color));c.texture(texture);
        List<BakedQuad> q=new ArrayList<>();
        ModelGeometry.entityBox(q,texture,0,0,0,16,8,16,0,28,64,64,Set.of(),0xffffff);
        ModelGeometry.entityBox(q,texture,0,4,0,16,12,16,0,0,64,64,Set.of(),0xffffff);
        BakedModel base=new BakedModel(key,q,false);Map<String,List<BakedModel>> options=new HashMap<>();
        for(Direction d:Direction.values()) {
            int x=switch(d){case DOWN->180;case NORTH,SOUTH,EAST,WEST->90;default->0;};
            int y=switch(d){case SOUTH->180;case EAST->90;case WEST->270;default->0;};
            BakedModel m=base.variant(x,y,false);c.reserveExternalGeometry(m.quads().size(),key.toString());options.put(d.name().toLowerCase(Locale.ROOT),List.of(m));
        }
        Map<String,List<BakedModel>> frozen=Map.copyOf(options);
        return BlockDefinition.builtin(key.toString(),s->required(frozen,s.properties().getOrDefault("facing","up"),key),frozen.values().stream().flatMap(List::stream).toList());
    }
    private static BlockDefinition banner(ResourceKey key,ModelCompiler c) {
        boolean wall=key.path().endsWith("_wall_banner");
        String color=key.path().substring(0,key.path().length()-(wall?"_wall_banner":"_banner").length());
        Integer dye=DYES.get(color);if(dye==null)return null;
        ResourceKey cloth=ResourceKey.parse("minecraft:entity/banner/base"),wood=ResourceKey.parse("minecraft:entity/banner/banner_base");c.texture(cloth);c.texture(wood);
        List<BakedQuad> q=new ArrayList<>();
        if(!wall)ModelGeometry.entityBox(q,wood,-1,-42,-1,2,42,2,44,0,64,64,Set.of(),0xffffff);
        ModelGeometry.entityBox(q,wood,-10,wall?-20.5:-44,wall?9.5:-1,20,2,2,0,42,64,64,Set.of(),0xffffff);
        ModelGeometry.entityBox(q,cloth,-10,wall?-20.5:-44,wall?8.5:-2,20,40,1,0,0,64,64,Set.of(),dye);
        BakedModel base=transform(new BakedModel(key,q,false),v->new Vec3(.5+v.x()*2/3,-v.y()*2/3,.5-v.z()*2/3),n->new Vec3(n.x(),-n.y(),-n.z()));
        return rotations(key,base,c,wall);
    }
    private static String skullTexture(String name) {
        String normalized=name.replace("_wall_","_");
        return switch(normalized) {
            case "skeleton_skull"->"entity/skeleton/skeleton";case "wither_skeleton_skull"->"entity/skeleton/wither_skeleton";
            case "zombie_head"->"entity/zombie/zombie";case "creeper_head"->"entity/creeper/creeper";
            case "player_head"->"entity/player/wide/steve";case "piglin_head"->"entity/piglin/piglin";
            case "dragon_head"->"entity/enderdragon/dragon";default->null;
        };
    }
    private static BlockDefinition skull(ResourceKey key,String sprite,ModelCompiler c) {
        ResourceKey texture=ResourceKey.parse("minecraft:"+sprite);Texture image=c.texture(texture);
        List<BakedQuad> q=new ArrayList<>();
        if(sprite.contains("enderdragon"))dragonParts(key,texture,q);
        else if(sprite.contains("piglin"))piglinParts(key,texture,q);
        else {
            ModelGeometry.entityBox(q,texture,-4,-8,-4,8,8,8,0,0,64,
                    sprite.contains("skeleton") || sprite.contains("creeper")?32:64,Set.of(),0xffffff);
            if(key.path().contains("player_") || key.path().contains("zombie_")) {
                List<BakedQuad> hat=new ArrayList<>();ModelGeometry.entityBox(hat,texture,-4,-8,-4,8,8,8,32,0,64,64,Set.of(),0xffffff);
                Vec3 center=new Vec3(0,-.25,0);
                q.addAll(transform(new BakedModel(key,hat,false),v->v.subtract(center).multiply(1.0625).add(center),n->n).quads());
            }
        }
        boolean wall=key.path().contains("_wall_");
        BakedModel base=transform(new BakedModel(key,q,false),v->new Vec3(.5-v.x(),(wall?.25:0)-v.y(),(wall?.75:.5)+v.z()),n->new Vec3(-n.x(),-n.y(),n.z()));
        return rotations(key,base,c,wall);
    }
    private static void piglinParts(ResourceKey key,ResourceKey texture,List<BakedQuad> out) {
        ModelGeometry.entityBox(out,texture,-5,-8,-4,10,8,8,0,0,64,64,Set.of(),0xffffff);
        ModelGeometry.entityBox(out,texture,-2,-4,-5,4,4,1,31,1,64,64,Set.of(),0xffffff);
        ModelGeometry.entityBox(out,texture,2,-2,-5,1,2,1,2,4,64,64,Set.of(),0xffffff);
        ModelGeometry.entityBox(out,texture,-3,-2,-5,1,2,1,2,0,64,64,Set.of(),0xffffff);
        for(int side:new int[]{-1,1}) {
            List<BakedQuad> ear=new ArrayList<>();ModelGeometry.entityBox(ear,texture,side>0?0:-1,0,-2,1,5,4,side>0?51:39,6,64,64,Set.of(),0xffffff);
            double angle=-side*Math.PI/6;Vec3 pivot=new Vec3(side*4.5/16,-6/16.0,0);
            out.addAll(transform(new BakedModel(key,ear,false),v->v.rotate(2,angle).add(pivot),n->n.rotate(2,angle)).quads());
        }
    }
    private static void dragonParts(ResourceKey key,ResourceKey texture,List<BakedQuad> out) {
        List<BakedQuad> local=new ArrayList<>();
        ModelGeometry.entityBox(local,texture,-6,-1,-24,12,5,16,176,44,256,256,Set.of(),0xffffff);
        ModelGeometry.entityBox(local,texture,-8,-8,-10,16,16,16,112,30,256,256,Set.of(),0xffffff);
        for(int side:new int[]{-1,1}) {
            ModelGeometry.entityBox(local,texture,side<0?-5:3,-12,-4,2,4,6,0,0,256,256,Set.of(),0xffffff);
            ModelGeometry.entityBox(local,texture,side<0?-5:3,-3,-22,2,2,4,112,0,256,256,Set.of(),0xffffff);
        }
        ModelGeometry.entityBox(local,texture,-6,4,-24,12,4,16,176,65,256,256,Set.of(),0xffffff);
        Vec3 offset=new Vec3(0,-7.986666/16,0);
        out.addAll(transform(new BakedModel(key,local,false),v->v.multiply(.75).add(offset),n->n).quads());
    }
    private static BlockDefinition pot(ResourceKey key,ModelCompiler c) {
        ResourceKey base=ResourceKey.parse("minecraft:entity/decorated_pot/decorated_pot_base");
        ResourceKey side=ResourceKey.parse("minecraft:entity/decorated_pot/decorated_pot_side");c.texture(base);c.texture(side);
        List<BakedQuad> q=new ArrayList<>();
        for(Direction face:List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST))
            ModelGeometry.face(q,side,face,ModelGeometry.points(face,1,0,1,15,16,15),new double[]{1,16,15,0},16,16,0xffffff,0);
        ModelGeometry.face(q,base,Direction.UP,ModelGeometry.points(Direction.UP,1,0,1,15,16,15),new double[]{0,13,14,27},32,32,0xffffff,0);
        ModelGeometry.face(q,base,Direction.DOWN,ModelGeometry.points(Direction.DOWN,1,0,1,15,0,15),new double[]{0,13,14,27},32,32,0xffffff,0);
        List<BakedQuad> neck=new ArrayList<>();
        ModelGeometry.entityBox(neck,base,4,17,4,8,3,8,0,0,32,32,Set.of(),0xffffff,new Vec3(-.1,-.1,-.1));
        ModelGeometry.entityBox(neck,base,5,20,5,6,1,6,0,5,32,32,Set.of(),0xffffff,new Vec3(.2,.2,.2));
        q.addAll(transform(new BakedModel(key,neck,false),v->new Vec3(v.x(),37/16.0-v.y(),1-v.z()),n->new Vec3(n.x(),-n.y(),-n.z())).quads());
        BakedModel original=new BakedModel(key,q,false);Map<String,List<BakedModel>> states=new HashMap<>();
        for(String direction:List.of("north","east","south","west")) {
            BakedModel model=ModelGeometry.rotateY(original,yaw(direction)-180);c.reserveExternalGeometry(model.quads().size(),key.toString());states.put(direction,List.of(model));
        }
        Map<String,List<BakedModel>> frozen=Map.copyOf(states);
        return BlockDefinition.builtin(key.toString(),state->required(frozen,state.properties().getOrDefault("facing","north"),key),frozen.values().stream().flatMap(List::stream).toList());
    }
    private static BlockDefinition statue(ResourceKey key,ModelCompiler c) {
        String oxidation=key.path().replaceFirst("^waxed_","").replace("copper_golem_statue","");
        String suffix=switch(oxidation){case ""->"";case "exposed_"->"_exposed";case "weathered_"->"_weathered";case "oxidized_"->"_oxidized";default->throw new AssetException(AssetException.Code.INVALID,key.toString(),"未知雕像氧化状态");};
        ResourceKey texture=ResourceKey.parse("minecraft:entity/copper_golem/copper_golem"+suffix);c.texture(texture);
        Map<String,String> methods=Map.of("standing","createBodyLayer","running","createRunningPoseBodyLayer","sitting","createSittingPoseBodyLayer","star","createStarPoseBodyLayer");
        Map<String,List<BakedModel>> variants=new HashMap<>();
        for(var entry:methods.entrySet()) {
            BakedModel base=NativeShapeBaker.statue(key,texture,c.nativeShape(entry.getValue()));
            for(String direction:List.of("north","east","south","west")) {
                BakedModel model=ModelGeometry.rotateY(base,yaw(direction)+180);c.reserveExternalGeometry(model.quads().size(),key.toString());
                variants.put(entry.getKey()+":"+direction,List.of(model));
            }
        }
        Map<String,List<BakedModel>> frozen=Map.copyOf(variants);
        return BlockDefinition.builtin(key.toString(),state->required(frozen,state.properties().getOrDefault("copper_golem_pose","standing")+":"+state.properties().getOrDefault("facing","north"),key),frozen.values().stream().flatMap(List::stream).toList());
    }
    private static BlockDefinition rotations(ResourceKey key,BakedModel base,ModelCompiler c,boolean wall) {
        Map<String,List<BakedModel>> variants=new HashMap<>();
        if(wall)for(String facing:List.of("north","east","south","west")) {
            BakedModel model=ModelGeometry.rotateY(base,(yaw(facing)+180)%360);c.reserveExternalGeometry(model.quads().size(),key.toString());variants.put(facing,List.of(model));
        } else for(int rotation=0;rotation<16;rotation++) {
            BakedModel model=ModelGeometry.rotateY(base,rotation*22.5);c.reserveExternalGeometry(model.quads().size(),key.toString());variants.put(Integer.toString(rotation),List.of(model));
        }
        Map<String,List<BakedModel>> frozen=Map.copyOf(variants);
        return BlockDefinition.builtin(key.toString(),s->required(frozen,s.properties().getOrDefault(wall?"facing":"rotation",wall?"north":"0"),key),frozen.values().stream().flatMap(List::stream).toList());
    }
    private static BakedModel transform(BakedModel model,UnaryOperator<Vec3> point,UnaryOperator<Vec3> normal) {
        return new BakedModel(model.key(),model.quads().stream().map(q->new BakedQuad(q.positions().stream().map(point).toList(),q.uv(),q.texture(),
                normal.apply(q.normal()).normalized(),Direction.closest(normal.apply(q.normal())),null,q.tintIndex(),q.shade(),q.forceTranslucent(),q.shadeDirectionOverride(),
                q.lightEmission(),q.layer(),q.topology(),q.surfaceGroup(),q.colorMultiplier(),q.opacity())).toList(),model.ambientOcclusion());
    }
    private static List<BakedModel> required(Map<String,List<BakedModel>> variants,String key,ResourceKey block) {
        List<BakedModel> value=variants.get(key);if(value==null)throw new AssetException(AssetException.Code.INVALID,block.toString(),"特殊模型状态无效："+key);return value;
    }
    private static int yaw(String facing) { return switch(facing){case "south"->0;case "west"->90;case "north"->180;case "east"->270;default->throw new IllegalArgumentException("非法朝向");}; }
}
