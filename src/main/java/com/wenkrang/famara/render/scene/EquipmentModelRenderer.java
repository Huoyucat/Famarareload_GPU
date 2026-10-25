package com.wenkrang.famara.render.scene;

import com.google.gson.*;
import com.wenkrang.famara.render.asset.*;
import org.joml.Matrix4d;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Armor follows the same bones as the body; item meshes are transformed into real hands/head slots. */
public final class EquipmentModelRenderer {
    private EquipmentModelRenderer(){}
    public static void equip(ActorModelBuilder b,FrozenActor a,String model,Map<String,Matrix4d> bones,ActorResources r){
        String base=model.substring(0,model.indexOf('#'));
        for(String slot:List.of("HEAD","CHEST","LEGS","FEET")){
            FrozenItem item=a.equipment().get(slot);if(item==null)continue;
            String layer=switch(slot){case "HEAD"->"helmet";case "CHEST"->"chestplate";case "LEGS"->"leggings";default->"boots";};String armor=base+"#"+layer;
            if(item.material().equals("minecraft:elytra") && slot.equals("CHEST")){wear(b,a,r,"minecraft:elytra"+(a.baby()?"_baby":"")+"#main",item,"wings",10);continue;}
            if(r.catalog().has(armor) && isArmor(item.material())){
                String kind=slot.equals("LEGS")?"humanoid_leggings":a.baby()?"humanoid_baby":"humanoid";wear(b,a,r,armor,item,kind,10);
                if(!item.trimPattern().isEmpty())trim(b,a,r,armor,item,kind.equals("humanoid_baby")?"humanoid":kind,12);
            }else if(slot.equals("HEAD")){
                Matrix4d head=bones.get("head");if(head==null)head=bones.get("head_parts");if(head!=null)r.items().appendHead(b,a,item,new Matrix4d(head).translate(0,-4,0),20);
            }
        }
        FrozenItem body=a.equipment().get("BODY");String bodyModel=null,bodyKind=null;
        switch(a.type()){
            case "HORSE"->{bodyModel="horse_armor";bodyKind="horse_body";}
            case "SKELETON_HORSE","ZOMBIE_HORSE"->{bodyModel="undead_horse_armor";bodyKind="horse_body";}
            case "WOLF"->{bodyModel="wolf_armor";bodyKind="wolf_body";}
            case "NAUTILUS","ZOMBIE_NAUTILUS"->{bodyModel="nautilus_armor";bodyKind="nautilus_body";}
            case "LLAMA","TRADER_LLAMA"->{bodyModel="llama"+(a.baby()?"_baby":"");bodyKind="llama_body";}
            case "HAPPY_GHAST"->{bodyModel="happy_ghast"+(a.baby()?"_baby":"")+"_harness";bodyKind="happy_ghast_body";}
            default->{}
        }
        if(body!=null && bodyModel!=null)wear(b,a,r,"minecraft:"+bodyModel+(bodyKind.equals("llama_body")?"#decor":"#main"),body,bodyKind,10);
        FrozenItem saddle=a.equipment().get("SADDLE");if(saddle==null && a.appearance().flag("saddled"))saddle=FrozenItem.vanilla("minecraft:saddle");
        if(saddle!=null){String family=a.type().toLowerCase(Locale.ROOT);String saddleModel="minecraft:"+family+"#saddle";if(r.catalog().has(saddleModel))wear(b,a,r,saddleModel,saddle,family+"_saddle",10);}
        for(boolean offhand:List.of(false,true)){
            FrozenItem item=a.equipment().get(offhand?"OFFHAND":"MAINHAND");if(item==null)continue;boolean left=offhand?!a.equipment().leftHanded():a.equipment().leftHanded();String arm=left?"left_arm":"right_arm";Matrix4d hand=bones.get(arm);
            if(hand!=null){double reach=reach(r.catalog().require(model),arm);double side=a.slim()?.5:1;hand=new Matrix4d(hand).rotateX(-Math.PI/2).rotateY(Math.PI).translate(left?-side:side,2,-reach);}
            else if(a.type().equals("SULFUR_CUBE"))hand=new Matrix4d().scale(.8);
            else if(a.type().equals("FOX") && bones.containsKey("head"))hand=new Matrix4d(bones.get("head")).translate(0,3,-6).rotateX(Math.PI/2);
            else hand=new Matrix4d().translate(left?4:-4,14,-4);
            r.items().append(b,a,item,hand,left,20);
        }
        String carried=a.appearance().value("carriedBlock","");if(!carried.isEmpty()){int props=carried.indexOf('[');FrozenItem item=FrozenItem.vanilla(props<0?carried:carried.substring(0,props));r.items().append(b,a,item,new Matrix4d().translate(0,12,-7).scale(.5),false,20);}
    }
    private static double reach(ActorModelCatalog.Part p,String arm){if(p.name().equals(arm)){double max=0;for(int i=1;i<p.faces().length;i+=5)max=Math.max(max,p.faces()[i]);return max>0?max:10;}for(var child:p.children()){double n=reach(child,arm);if(n>=0)return n;}return -1;}
    private static boolean isArmor(String item){return item.endsWith("_helmet") || item.endsWith("_chestplate") || item.endsWith("_leggings") || item.endsWith("_boots");}
    private static String asset(FrozenItem item){if(!item.equipmentAsset().isEmpty())return item.equipmentAsset();String name=ResourceKey.parse(item.material()).path();if(name.equals("elytra"))return "minecraft:elytra";if(name.equals("wolf_armor"))return "minecraft:armadillo_scute";if(name.equals("turtle_helmet"))return "minecraft:turtle_scute";if(name.endsWith("_carpet"))return "minecraft:"+name;if(name.endsWith("_harness"))return "minecraft:"+name;for(String suffix:List.of("_horse_armor","_nautilus_armor","_helmet","_chestplate","_leggings","_boots"))if(name.endsWith(suffix)){name=name.substring(0,name.length()-suffix.length());break;}return "minecraft:"+(name.equals("golden")?"gold":name);}
    private static void wear(ActorModelBuilder b,FrozenActor a,ActorResources r,String model,FrozenItem item,String kind,int layer){
        if(!r.catalog().has(model))throw new IllegalStateException("Missing armor geometry: "+model);ResourceKey asset=ResourceKey.parse(asset(item));byte[] bytes=r.read(asset.file("equipment",".json"));if(bytes==null)throw new IllegalStateException("Missing equipment asset: "+asset);JsonObject root=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();JsonObject layers=root.getAsJsonObject("layers");if(!layers.has(kind))throw new IllegalStateException("Equipment cannot render in "+kind+": "+asset);
        int index=0;for(var entry:layers.getAsJsonArray(kind)){JsonObject definition=entry.getAsJsonObject();if(definition.has("dyeable") && !definition.getAsJsonObject("dyeable").has("color_when_undyed") && item.tintRgb()==0xffffff)continue;String reference=definition.get("texture").getAsString();ResourceKey id=reference.indexOf(':')>=0?ResourceKey.parse(reference):new ResourceKey("minecraft",reference),texture=new ResourceKey(id.namespace(),"entity/equipment/"+kind+"/"+id.path());int color=definition.has("dyeable")?item.tintRgb():0xffffff;r.catalog().emit(b,a,model,texture,r.texture(texture),color,layer+index++);if(item.enchanted()){ResourceKey glint=glint(texture,r);r.catalog().emit(b,a,model,glint,r.texture(glint),0xffffff,layer+7);}}
    }
    private static void trim(ActorModelBuilder b,FrozenActor a,ActorResources r,String model,FrozenItem item,String kind,int layer){
        ResourceKey pattern=ResourceKey.parse(item.trimPattern()),material=ResourceKey.parse(item.trimMaterial());String asset=ResourceKey.parse(asset(item)).path();String palette=material.path();if(palette.equals(asset))palette+="_darker";String paletteName=palette;
        ResourceKey key=new ResourceKey("famara","trim/"+kind+"/"+pattern.namespace()+"/"+pattern.path()+"/"+material.namespace()+"/"+palette);Texture texture=r.cached(key);
        if(texture==null){BufferedImage mask=image(r.read(new ResourceKey(pattern.namespace(),"trims/entity/"+kind+"/"+pattern.path()).file("textures",".png"))),from=image(r.read("assets/minecraft/textures/trims/color_palettes/trim_palette.png")),to=image(r.read(new ResourceKey(material.namespace(),"trims/color_palettes/"+paletteName).file("textures",".png")));if(from.getWidth()*from.getHeight()!=to.getWidth()*to.getHeight())throw new IllegalStateException("Trim palette shape mismatch");Map<Integer,Integer> colors=new HashMap<>();for(int i=0;i<from.getWidth()*from.getHeight();i++)colors.put(from.getRGB(i%from.getWidth(),i/from.getWidth())&0xffffff,to.getRGB(i%to.getWidth(),i/to.getWidth()));for(int y=0;y<mask.getHeight();y++)for(int x=0;x<mask.getWidth();x++){int pixel=mask.getRGB(x,y),mapped=colors.getOrDefault(pixel&0xffffff,pixel);mask.setRGB(x,y,((pixel>>>24)*(mapped>>>24)/255)<<24 | mapped&0xffffff);}texture=r.generated(key,png(mask));}
        r.catalog().emit(b,a,model,key,texture,0xffffff,layer);
    }
    static ResourceKey glint(ResourceKey base,ActorResources r){ResourceKey key=new ResourceKey("famara","glint/"+base.namespace()+"/"+base.path());if(r.cached(key)!=null)return key;Texture source=r.texture(base);BufferedImage image=new BufferedImage(source.width(),source.height(),BufferedImage.TYPE_INT_ARGB);for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){double alpha=source.sample((x+.5)/source.width(),(y+.5)/source.height(),0,0,Texture.Filter.NEAREST).a();int shade=((x+y*2)&15)<4?0xce7fff:0x6329a6;image.setRGB(x,y,((int)Math.round(alpha*72))<<24|shade);}r.generated(key,png(image));return key;}
    private static BufferedImage image(byte[] bytes){if(bytes==null)throw new IllegalStateException("Equipment image missing");try(ImageInputStream in=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){var readers=ImageIO.getImageReaders(in);if(!readers.hasNext())throw new IOException("Invalid PNG");ImageReader reader=readers.next();try{reader.setInput(in,true,true);if(!reader.getFormatName().equalsIgnoreCase("png") || (long)reader.getWidth(0)*reader.getHeight(0)>65536)throw new IOException("Oversized equipment image");return reader.read(0);}finally{reader.dispose();}}catch(IOException error){throw new IllegalStateException(error);}}
    private static byte[] png(BufferedImage image){try{var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}catch(IOException error){throw new IllegalStateException(error);}}
}
