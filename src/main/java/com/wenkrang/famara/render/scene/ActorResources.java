package com.wenkrang.famara.render.scene;
import com.wenkrang.famara.render.asset.*;
/** Batch-owned resources, accessed only on the budgeted preparation worker. */
public interface ActorResources {
    byte[] read(String path);
    boolean has(String path);
    Texture texture(ResourceKey key);
    Texture generated(ResourceKey key,byte[] png);
    ActorModelCatalog catalog();
    Texture cached(ResourceKey key);
    ItemModelRenderer items();
}
