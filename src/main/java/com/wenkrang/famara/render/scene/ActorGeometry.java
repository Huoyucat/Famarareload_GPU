package com.wenkrang.famara.render.scene;

import com.wenkrang.famara.render.asset.*;
import java.util.*;

/** Already baked world-relative actor meshes and immutable per-scene textures. */
public record ActorGeometry(FrozenActor actor,int x,int y,int z,BakedModel model,Map<ResourceKey,Texture> textures) {
    public ActorGeometry{Objects.requireNonNull(actor);Objects.requireNonNull(model);textures=Map.copyOf(textures);}
    public long estimatedBytes(){return 2048L+model.quads().size()*768L+textures.values().stream().distinct().mapToLong(Texture::retainedBytes).sum();}
}
