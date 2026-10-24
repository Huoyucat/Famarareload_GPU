package com.wenkrang.famara.render.gpu;

/** Whole-scene gate: serialization support must never be mistaken for render support. */
public final class GpuMvpSupport {
    private GpuMvpSupport() {}
    public static void require(GpuScene scene) {
        if(!scene.features().isEmpty())throw new BackendException(BackendException.Code.UNSUPPORTED,"MVP unsupported scene features: "+scene.features());
        var header=scene.ints(GpuScene.BufferKind.HEADER_INT);var faces=scene.ints(GpuScene.BufferKind.FACES_INT);var values=scene.floats(GpuScene.BufferKind.FACES_FLOAT);var textures=scene.ints(GpuScene.BufferKind.TEXTURES);
        for(int face=0;face<header.get(3);face++)if(faces.get(face*16+15)!=0 || (faces.get(face*16+9)&~5)!=0 || values.get(face*40+27)!=1)throw new BackendException(BackendException.Code.UNSUPPORTED,"MVP requires ordinary opaque material faces");
        for(int texture=0;texture<header.get(4);texture++)if(textures.get(texture*12+6)!=1 || textures.get(texture*12+1)!=1 || textures.get(texture*12+4)!=1 || textures.get(texture*12+5)!=0)throw new BackendException(BackendException.Code.UNSUPPORTED,"MVP requires static opaque textures");
    }
}
