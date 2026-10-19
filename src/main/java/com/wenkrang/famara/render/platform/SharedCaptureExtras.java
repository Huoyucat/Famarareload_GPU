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
    private void release() {
        ChunkCaptureService.Extras owned=null;
        synchronized(this){if(--references==0){owned=delegate;delegate=null;}}
        if(owned instanceof AutoCloseable closeable)try{closeable.close();}catch(RuntimeException | Error error){throw error;}catch(Exception error){throw new IllegalStateException("实体捕获会话清理失败",error);}
    }
    public void close(){synchronized(this){if(closed)return;closed=true;}release();}
}
