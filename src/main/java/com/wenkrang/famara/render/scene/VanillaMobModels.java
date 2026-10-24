package com.wenkrang.famara.render.scene;

import com.wenkrang.famara.render.asset.*;
import java.util.*;

/** Explicit model families; unknown mobs are reported rather than drawn as collision boxes. */
public final class VanillaMobModels {
    private VanillaMobModels(){}
    public static boolean supports(String type){return VanillaActorRegistry.supports(type);}
    public static String texturePath(String type){return VanillaActorRegistry.texture(type);}
    public static ActorGeometry bake(FrozenActor a,ResourceKey key,Texture texture,ResourceKey woolKey,Texture wool){
        var b=new ActorModelBuilder(a,a.type().equals("CAVE_SPIDER")?.7:1);b.texture(key,texture);
        switch(a.type()){
            case "ZOMBIE","HUSK","DROWNED"->PlayerModelRenderer.humanoid(b,a,key,texture,false);
            case "SKELETON","STRAY","BOGGED","WITHER_SKELETON"->skeleton(b,a,key);
            case "CREEPER"->{head(b,a,key,18,0,8,8,8,0,0);box(b,key,0,6,0,8,12,4,16,16);legs(b,a,key,2,3,6,4,6,4,0,16);}
            case "PIG"->{head(b,a,key,8,10,8,8,8,0,0);box(b,key,0,6,0,10,8,16,28,8);legs(b,a,key,3,5,6,4,6,4,0,16);b.box(key,0,8,14,-2,1,0,4,3,1,16,16,0,0,0,0xffffff,0);}
            case "COW"->{head(b,a,key,12,9,8,8,6,0,0);box(b,key,0,12,0,12,10,18,18,4);legs(b,a,key,4,6,12,4,12,4,0,16);for(int s:new int[]{-1,1})b.box(key,s*4,20,9,-1,-1,-1,1,3,1,22,0,0,0,0,0xffffff,0);}
            case "SHEEP"->{sheep(b,a,key,0xffffff,0,0);if(!a.sheared() && wool!=null){b.texture(woolKey,wool);sheep(b,a,woolKey,a.tintRgb(),.6,1);}}
            case "CHICKEN"->{head(b,a,key,9,4,4,6,3,0,0);box(b,key,0,5,0,6,6,8,0,9);legs(b,a,key,2,1,5,1,5,1,26,0);for(int s:new int[]{-1,1})b.box(key,s*3.5,5,0,-.5,0,-3,1,4,6,24,13,0,0,0,0xffffff,0);box(b,key,0,11,6,4,2,2,14,0);}
            case "SPIDER","CAVE_SPIDER"->{head(b,a,key,4,7,8,8,8,32,4);box(b,key,0,4,0,6,6,6,0,0);box(b,key,0,3,-7,10,8,12,0,12);for(int s:new int[]{-1,1})for(int i=0;i<4;i++)b.box(key,s*4,5,3-i*2,s<0?-15:0,-1,-1,15,2,2,18,0,0,0,s*(i-1.5)*.25,0xffffff,0);}
            case "ENDERMAN"->{head(b,a,key,40,0,8,8,8,0,0);box(b,key,0,28,0,8,12,4,32,16);for(int s:new int[]{-1,1}){b.box(key,s*5,38,0,-1,-28,-1,2,30,2,56,0,0,s*a.limbSwing(),0,0xffffff,0);b.box(key,s*2,28,0,-1,-28,-1,2,28,2,56,0,0,-s*a.limbSwing(),0,0xffffff,0);}}
            default->throw new IllegalArgumentException("Unsupported living model: "+a.type());
        }
        return b.build();
    }
    private static void skeleton(ActorModelBuilder b,FrozenActor a,ResourceKey k){head(b,a,k,24,0,8,8,8,0,0);box(b,k,0,12,0,8,12,4,16,16);for(int s:new int[]{-1,1}){b.box(k,s*5,22,0,-1,-10,-1,2,12,2,40,16,0,s*a.limbSwing(),0,0xffffff,0);b.box(k,s*2,12,0,-1,-12,-1,2,12,2,0,16,0,-s*a.limbSwing(),0,0xffffff,0);}}
    private static void sheep(ActorModelBuilder b,FrozenActor a,ResourceKey k,int tint,double inflate,int layer){b.box(k,0,12,8,-3,0,-4,6,6,8,0,0,inflate,Math.toRadians(a.pitch()),-Math.toRadians(a.headYaw()-a.bodyYaw()),tint,layer);b.box(k,0,12,0,-4,0,-8,8,8,16,28,8,inflate,0,0,tint,layer);for(int s:new int[]{-1,1})for(int f:new int[]{-1,1})b.box(k,s*3,12,f*5,-2,-12,-2,4,12,4,0,16,inflate*.5,s*f*a.limbSwing(),0,tint,layer);}
    private static void head(ActorModelBuilder b,FrozenActor a,ResourceKey k,double y,double z,int w,int h,int d,int u,int v){b.box(k,0,y,z,-w/2.0,0,-d/2.0,w,h,d,u,v,0,Math.toRadians(a.pitch()),-Math.toRadians(a.headYaw()-a.bodyYaw()),0xffffff,0);}
    private static void box(ActorModelBuilder b,ResourceKey k,double x,double y,double z,int w,int h,int d,int u,int v){b.box(k,x,y,z,-w/2.0,0,-d/2.0,w,h,d,u,v,0,0,0,0xffffff,0);}
    private static void legs(ActorModelBuilder b,FrozenActor a,ResourceKey k,int x,int z,int y,int w,int h,int d,int u,int v){for(int s:new int[]{-1,1})for(int f:new int[]{-1,1})b.box(k,s*x,y,f*z,-w/2.0,-h,-d/2.0,w,h,d,u,v,0,s*f*a.limbSwing(),0,0xffffff,0);}
}
