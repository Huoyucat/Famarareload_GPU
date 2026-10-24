package com.wenkrang.famara.render.scene;
import com.wenkrang.famara.render.asset.*;

/** Steve/Alex skin UVs, independent head yaw/pitch, crouching and alternating limb swing. */
public final class PlayerModelRenderer {
    private PlayerModelRenderer(){}
    public static ActorGeometry bake(FrozenActor actor,ResourceKey key,Texture skin) {
        var b=new ActorModelBuilder(actor,.9375);b.texture(key,skin);humanoid(b,actor,key,skin,true);return b.build();
    }
    static void humanoid(ActorModelBuilder b,FrozenActor a,ResourceKey key,Texture skin,boolean player) {
        boolean crouch=a.pose().equals("CROUCHING");double headY=crouch?19.8:24,armY=crouch?18.8:22;double headYaw=-Math.toRadians(a.headYaw()-a.bodyYaw()),pitch=Math.toRadians(a.pitch());double walk=a.limbSwing(),arms=player?walk:(a.type().contains("ZOMBIE") || a.type().equals("HUSK") || a.type().equals("DROWNED")?-Math.PI/2:walk);int width=a.slim() && player?3:4;
        b.box(key,0,headY,0,-4,0,-4,8,8,8,0,0,0,pitch,headYaw,0xffffff,0);
        b.box(key,0,12,0,-4,0,-2,8,12,4,16,16,0,crouch?.5:0,0,0xffffff,0);
        b.box(key,-(4+width/2.0),armY,0,-width/2.0,-10,-2,width,12,4,40,16,0,arms+(crouch?.4:0),0,0xffffff,0);
        int leftArmU=player && skin.height()==64?32:40,leftArmV=player && skin.height()==64?48:16;
        b.box(key,4+width/2.0,armY,0,-width/2.0,-10,-2,width,12,4,leftArmU,leftArmV,0,player?-walk+(crouch?.4:0):arms,0,0xffffff,0);
        b.box(key,-1.9,crouch?12.2:12,crouch?4:0,-2,-12,-2,4,12,4,0,16,0,-walk,0,0xffffff,0);
        b.box(key,1.9,crouch?12.2:12,crouch?4:0,-2,-12,-2,4,12,4,player && skin.height()==64?16:0,player && skin.height()==64?48:16,0,walk,0,0xffffff,0);
        if(player){
            b.box(key,0,headY,0,-4,0,-4,8,8,8,32,0,.5,pitch,headYaw,0xffffff,1);
            if(skin.height()==64){
                b.box(key,0,12,0,-4,0,-2,8,12,4,16,32,.25,crouch?.5:0,0,0xffffff,1);
                b.box(key,-(4+width/2.0),armY,0,-width/2.0,-10,-2,width,12,4,40,32,.25,walk+(crouch?.4:0),0,0xffffff,1);
                b.box(key,4+width/2.0,armY,0,-width/2.0,-10,-2,width,12,4,48,48,.25,-walk+(crouch?.4:0),0,0xffffff,1);
                b.box(key,-1.9,crouch?12.2:12,crouch?4:0,-2,-12,-2,4,12,4,0,32,.25,-walk,0,0xffffff,1);
                b.box(key,1.9,crouch?12.2:12,crouch?4:0,-2,-12,-2,4,12,4,0,48,.25,walk,0,0xffffff,1);
            }
        }
    }
}
