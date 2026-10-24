package com.wenkrang.famara.render.gpu;

/** Capabilities of the frozen built-in geometry/material shader, independent of resource identity. */
public final class GpuPhotoSupport {
    private GpuPhotoSupport() {}
    public static void require(GpuScene scene) {
        var header=scene.ints(GpuScene.BufferKind.HEADER_INT);
        if(header.get(0)!=GpuScene.VERSION || (header.get(30)&~511)!=0)throw new BackendException(BackendException.Code.UNSUPPORTED,"Unknown GPU scene layout/features");
        if(header.get(19)!=1 && header.get(19)!=4 || header.get(20)<0 || header.get(20)>2 || header.get(21)<0 || header.get(21)>1)throw new BackendException(BackendException.Code.UNSUPPORTED,"Unknown sampling policy");
        if(scene.features().isEmpty())GpuMvpSupport.require(scene);
        // Dynamic hit/mailbox/layer overflow returns a whole-photo failure, never a partial image.
    }
}
