package com.wenkrang.famara.render.gpu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class GpuOwnerLockTest {
    @TempDir Path directory;
    @Test void retiringOwnerPreventsAnotherLoaderOrSessionUntilActualRelease() {
        var path=directory.resolve("gpu-device.lock");
        try(var first=GpuOwnerLock.acquire(path)) {
            var failure=assertThrows(BackendException.class,()->GpuOwnerLock.acquire(path));assertEquals(BackendException.Code.UNAVAILABLE,failure.code());assertEquals("GPU_OWNER_BUSY",failure.getMessage());
        }
        try(var next=GpuOwnerLock.acquire(path)){assertNotNull(next);}
    }
    @Test void offDoesNotCreateAnOwnerFileOrInvokeNativeCode()throws Exception {
        var file=directory.resolve("unused-device.lock");
        var probe=new GpuDeviceProbe(GpuConfiguration.disabled(),new com.wenkrang.famara.render.schedule.CpuBudget(5000,100,System::nanoTime),new com.wenkrang.famara.render.schedule.RenderMemoryBudget(536870912),file);
        assertFalse(probe.initialization().toCompletableFuture().get().available());probe.close();probe.whenClosed().toCompletableFuture().get();assertFalse(java.nio.file.Files.exists(file));
    }
}
