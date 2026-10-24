package com.wenkrang.famara.render.gpu;

import java.util.Objects;

/** Independent of TEXTURED/LEGACY; OFF never initializes a native backend. */
public record GpuConfiguration(Mode mode,String preferredDevice,int vramBudgetMiB,int maxConcurrentJobs,
                               int stagingMemoryMiB,int textureCacheMiB,long kernelTimeoutMillis,
                               boolean fallbackToCpu,double minEstimatedSpeedup) {
    public enum Mode { OFF, AUTO, OPENCL }
    public GpuConfiguration {
        Objects.requireNonNull(mode);Objects.requireNonNull(preferredDevice);
        if(preferredDevice.isBlank() || preferredDevice.length()>256 || vramBudgetMiB<1 || vramBudgetMiB>2048
                || maxConcurrentJobs!=1 || stagingMemoryMiB<1 || stagingMemoryMiB>512
                || textureCacheMiB<1 || textureCacheMiB>vramBudgetMiB || kernelTimeoutMillis<1 || kernelTimeoutMillis>60000
                || !Double.isFinite(minEstimatedSpeedup) || minEstimatedSpeedup<1)
            throw new IllegalArgumentException("GPU configuration must have bounded memory, one job, and a finite speedup >= 1");
    }
    public static GpuConfiguration disabled() {
        return new GpuConfiguration(Mode.OFF,"Quadro P620",384,1,64,128,10000,true,1.2);
    }
}
