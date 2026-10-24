package com.wenkrang.famara.render.gpu;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Admission guard; scene selection and once-only fallback belong to the pipeline owner. */
public final class RenderBackendPolicy {
    private final GpuConfiguration config;
    private final BooleanSupplier gpuAvailable;
    public RenderBackendPolicy(GpuConfiguration config){this(config,()->false);}
    public RenderBackendPolicy(GpuConfiguration config,BooleanSupplier gpuAvailable){this.config=Objects.requireNonNull(config);this.gpuAvailable=Objects.requireNonNull(gpuAvailable);}
    public String fallbackReason(){return config.mode()==GpuConfiguration.Mode.OFF?"":config.mode()==GpuConfiguration.Mode.AUTO?"AUTO_UNSELECTED":"OPENCL_UNAVAILABLE";}
    public void requireCpuAllowed() {
        if(config.mode()==GpuConfiguration.Mode.OPENCL && !config.fallbackToCpu() && !gpuAvailable.getAsBoolean())
            throw new IllegalStateException("OpenCL拍照后端不可用，且已禁止CPU回退；未扣胶卷");
    }
    public GpuConfiguration.Mode requested(){return config.mode();}
}
