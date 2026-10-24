package com.wenkrang.famara.render.gpu;

import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.scene.SceneSnapshot;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/** Only frozen data crosses this boundary. Caller retains the asset lease until completion. */
public interface PhotoRenderBackend extends AutoCloseable {
    record Capabilities(String name,boolean available,Set<String> features,String unavailableReason) {
        public Capabilities {Objects.requireNonNull(name);features=Set.copyOf(features);Objects.requireNonNull(unavailableReason);}
    }
    record Prepared(UUID id,SceneSnapshot scene,Camera camera,CpuPhotoRenderer.Settings settings) {
        public Prepared {Objects.requireNonNull(id);Objects.requireNonNull(scene);Objects.requireNonNull(camera);Objects.requireNonNull(settings);}
    }
    record Result(int[] rgb,Map<String,String> metadata) {
        public Result {
            if(rgb.length!=16384)throw new IllegalArgumentException("Expected 128x128 pixels");
            for(int pixel:rgb)if((pixel&0xff000000)!=0)throw new IllegalArgumentException("Expected RGB24");
            rgb=rgb.clone();metadata=Map.copyOf(metadata);
        }
        @Override public int[] rgb(){return rgb.clone();}
    }
    Capabilities capabilities();
    Prepared prepare(SceneSnapshot scene,Camera camera,CpuPhotoRenderer.Settings settings);
    CompletionStage<Result> submitAsync(Prepared prepared,BooleanSupplier cancelled);
    default int activeJobs(){return 0;}
    default String deviceName(){return "UNKNOWN";}
    default long availableHostBytes(){return Long.MAX_VALUE;}
    default long availableVramBytes(){return Long.MAX_VALUE;}
    default Map<String,String> diagnostics(UUID id){return Map.of();}
    void cancel(UUID id);
    @Override void close();
}
