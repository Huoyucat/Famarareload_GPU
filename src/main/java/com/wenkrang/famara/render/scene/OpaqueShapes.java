package com.wenkrang.famara.render.scene;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.math.*;
import java.util.*;

/** 只有每一面都存在覆盖整格的不透明表面时，才可用于提前遮挡/流体邻域判定。 */
public final class OpaqueShapes {
    private OpaqueShapes() {}
    public static boolean fullCube(List<BakedModel> models,AssetGeneration assets) {
        EnumSet<Direction> covered=EnumSet.noneOf(Direction.class);
        for(BakedModel model:models)for(BakedQuad quad:model.quads()) {
            if(quad.topology()!=BakedQuad.Topology.QUAD || quad.opacity()!=1 || quad.forceTranslucent() || !assets.texture(quad.texture()).opaque())continue;
            Direction face=quad.face();Vec3 n=face.vector();
            if(n.dot(quad.normal())<1-1e-9)continue;
            int axis=Math.abs(n.x())>.5?0:Math.abs(n.y())>.5?1:2;
            double plane=n.component(axis)>0?1:0;
            if(quad.positions().stream().anyMatch(p->Math.abs(p.component(axis)-plane)>1e-9))continue;
            int a=(axis+1)%3,b=(axis+2)%3;Set<Integer> corners=new HashSet<>();
            boolean square=true;
            for(Vec3 p:quad.positions()) {
                double u=p.component(a),v=p.component(b);
                if((Math.abs(u)>1e-9 && Math.abs(u-1)>1e-9) || (Math.abs(v)>1e-9 && Math.abs(v-1)>1e-9)) {square=false;break;}
                corners.add((u>.5?1:0)+(v>.5?2:0));
            }
            if(square && corners.size()==4)covered.add(face);
        }
        return covered.size()==6;
    }
}
