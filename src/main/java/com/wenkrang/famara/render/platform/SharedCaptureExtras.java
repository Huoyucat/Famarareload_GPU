package com.wenkrang.famara.render.platform;

import org.bukkit.Chunk;
import com.wenkrang.famara.render.scene.LegacyEntity;
import java.util.*;

/** 非阻塞关闭：在途region读取保留实体/CE会话，最后使用者退出才关闭delegate。 */
final class SharedCaptureExtras implements ChunkCaptureService.Extras,AutoCloseable {
    private ChunkCaptureService.Extras delegate;
    private int references=1;
    private boolean closed;
    SharedCaptureExtras(ChunkCaptureService.Extras delegate){this.delegate=Objects.requireNonNull(delegate);}
    public List<LegacyEntity> capture(Chunk chunk) {
        ChunkCaptureService.Extras owned;
        synchronized(this){if(closed)throw new java.util.concurrent.CancellationException("实体捕获会话已关闭");references++;owned=delegate;}
        Throwable failure=null;
        try{return owned.capture(chunk);}catch(RuntimeException | Error error){failure=error;throw error;}
        finally{try{release();}catch(RuntimeException | Error cleanup){if(failure!=null && failure!=cleanup)failure.addSuppressed(cleanup);else if(failure==null)throw cleanup;}}
    }
    public java.util.concurrent.CompletionStage<List<com.wenkrang.famara.render.scene.ActorGeometry>> finishActors() {
        return finishActors(null);
    }
    public java.util.concurrent.CompletionStage<List<com.wenkrang.famara.render.scene.ActorGeometry>> finishActors(com.wenkrang.famara.render.kernel.ConservativeOcclusion visibility) {
        ChunkCaptureService.Extras owned;
        synchronized(this){if(closed)throw new java.util.concurrent.CancellationException("Actor capture closed");references++;owned=delegate;}
        try{return owned.finishActors(visibility).whenComplete((result,error)->release());}
        catch(RuntimeException | Error error){release();throw error;}
    }
    public synchronized Map<String,String> actorMetadata(){if(closed)throw new IllegalStateException("Actor capture closed");return delegate.actorMetadata();}
    public synchronized AutoCloseable transferActorResources(){if(closed)throw new IllegalStateException("Actor capture closed");return delegate.transferActorResources();}
    private void release() {
        ChunkCaptureService.Extras owned=null;
        synchronized(this){if(--references==0){owned=delegate;delegate=null;}}
        if(owned instanceof AutoCloseable closeable)try{closeable.close();}catch(RuntimeException | Error error){throw error;}catch(Exception error){throw new IllegalStateException("实体捕获会话清理失败",error);}
    }
    public void close(){ChunkCaptureService.Extras owned;synchronized(this){if(closed)return;closed=true;owned=delegate;}try{owned.cancelActors();}finally{release();}}
}
