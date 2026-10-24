package com.wenkrang.famara.render.gpu;

import com.wenkrang.famara.render.asset.Texture;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class GpuAutoPolicyTest {
    GpuAutoPolicy.Input input(long faces,int aa,double distance,boolean complex,String fingerprint){return new GpuAutoPolicy.Input(faces,4096,100000,(int)(faces/6),aa,distance,Texture.Filter.MIPMAP,complex,false,fingerprint,2,1024);}
    GpuConfiguration config(){return new GpuConfiguration(GpuConfiguration.Mode.AUTO,"Quadro P620",384,1,64,128,10000,true,1.2);}
    GpuAutoPolicy.Decision select(GpuAutoPolicy p,GpuAutoPolicy.Input i){return p.select(i,config(),true,"Quadro P620",0,536870912,402653184);}
    @Test void availableGpuIsPreferredIncludingDenseFourSampleScenes(){var p=new GpuAutoPolicy();assertTrue(select(p,input(12,1,12,false,"a")).gpu());assertTrue(select(p,input(6,4,128,true,"a")).gpu());assertTrue(select(p,input(1326,4,128,false,"a")).gpu());}
    @Test void unavailableAndMemoryConstrainedDevicesUseCpuButBusyGpuQueues(){var p=new GpuAutoPolicy();var i=input(12,1,12,false,"a");assertFalse(p.select(i,config(),false,"Quadro P620",0,Long.MAX_VALUE,Long.MAX_VALUE).gpu());assertEquals("AUTO_GPU_QUEUE",p.select(i,config(),true,"Quadro P620",1,Long.MAX_VALUE,Long.MAX_VALUE).reason());assertTrue(p.select(i,config(),true,"Other GPU",0,Long.MAX_VALUE,Long.MAX_VALUE).gpu());assertEquals("AUTO_HOST_MEMORY",p.select(i,config(),true,"Quadro P620",0,1,Long.MAX_VALUE).reason());assertEquals("AUTO_VRAM_HEADROOM",p.select(i,config(),true,"Quadro P620",0,Long.MAX_VALUE,1).reason());}
    @Test void slowGpuHistoryCannotDisableGpuButFailureBackoffExpires(){var clock=new AtomicLong();var p=new GpuAutoPolicy(clock::get);var i=input(12,1,12,false,"a");p.observe(i,false,10_000_000);p.observe(i,true,100_000_000);assertTrue(select(p,i).gpu());clock.addAndGet(31_000_000_000L);assertTrue(select(p,i).gpu());p.failed(i);assertEquals("AUTO_SCENE_BACKOFF",select(p,i).reason());clock.addAndGet(31_000_000_000L);assertTrue(select(p,i).gpu());}
    @Test void legacySpeedThresholdAndUncertainComplexityDoNotVetoAvailableGpu(){
        var p=new GpuAutoPolicy();var i=new GpuAutoPolicy.Input(12,4096,100000,2,4,128,Texture.Filter.MIPMAP,true,true,"a",2,1024);
        var legacy=new GpuConfiguration(GpuConfiguration.Mode.AUTO,"Quadro P620",384,1,64,128,10000,true,100);
        assertEquals("AUTO_GPU_FIRST",p.select(i,legacy,true,"Quadro P620",0,536870912,402653184).reason());
    }
    @Test void historyIsBoundedAndResourceFingerprintPreventsOldMeasurementsBleedingIntoNewAssets(){var p=new GpuAutoPolicy();var old=input(12,1,12,false,"old");p.observe(old,true,1_000_000_000L);assertTrue(select(p,old).gpu());assertTrue(select(p,input(12,1,12,false,"new")).gpu());for(int n=0;n<300;n++)p.observe(input(12,1,12,false,"asset-"+n),false,1);assertEquals(128,p.entries());}
}
