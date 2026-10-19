package com.wenkrang.famara.render.kernel;

import com.wenkrang.famara.render.math.Vec3;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CaptureFrustumTest {
    @Test void allSampledCameraRaysRemainInsideCandidateVolumeAtEdgesAndNearPoles() {
        Random random=new Random(13);
        for(var projection:Camera.Projection.values())for(double pitch:new double[]{-Math.PI/2,-1.2,0,1.2,Math.PI/2}) {
            Camera camera=new Camera(new Vec3(-100.5,63.2,3.4),1.7,pitch,projection,1.1);var frustum=new CaptureFrustum(camera,16);
            for(int n=0;n<1000;n++) {
                int x=n<4?(n%2)*127:random.nextInt(128),y=n<4?(n/2)*127:random.nextInt(128);
                var ray=camera.ray(x,y,n%2==0?0:Math.nextDown(1d),n%3==0?0:Math.nextDown(1d));
                Vec3 point=ray.origin().add(ray.direction().multiply(n%5==0?16:random.nextDouble()*16));
                assertTrue(frustum.mayIntersect(point.subtract(new Vec3(.001,.001,.001)),point.add(new Vec3(.001,.001,.001))),projection+" pitch="+pitch+" pixel="+x+","+y);
            }
        }
    }
    @Test void clearlyBehindOrBeyondRangeIsCulledButExtendingHostsRemainCandidates() {
        Camera camera=new Camera(new Vec3(0,0,0),0,0,Camera.Projection.LEGACY_ANGULAR,1);var frustum=new CaptureFrustum(camera,16);
        assertFalse(frustum.mayIntersect(-8,-1,-1,-7,1,1));assertFalse(frustum.mayIntersect(17,-1,-1,18,1,1));
        assertFalse(frustum.mayIntersect(3,-1,12,4,1,13));
        assertTrue(frustum.mayIntersect(-2,-1,-1,1,1,1)); // 宿主背后但几何跨到视线前面。
        assertTrue(frustum.mayIntersect(15.5,-.1,-.1,16.5,.1,.1));
    }
}
