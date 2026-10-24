package com.wenkrang.famara.render.gpu;

import java.nio.file.*;
import java.nio.channels.*;
import java.io.IOException;

/** File locks survive plugin ClassLoader replacement while an old native owner is still returning. */
final class GpuOwnerLock {
    private GpuOwnerLock() {}
    static Lease acquire(Path path) {
        FileChannel channel=null;
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            if(Files.isSymbolicLink(path))throw new IOException("Symbolic device owner lock rejected");
            channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock;
            try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){throw new BackendException(BackendException.Code.UNAVAILABLE,"GPU_OWNER_BUSY",busy);}
            if(lock==null)throw new BackendException(BackendException.Code.UNAVAILABLE,"GPU_OWNER_BUSY");return new Lease(channel,lock);
        }catch(RuntimeException | IOException failure) {
            if(channel!=null)try{channel.close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}
            if(failure instanceof BackendException backend)throw backend;
            throw new BackendException(BackendException.Code.UNAVAILABLE,"GPU owner lock unavailable",failure);
        }
    }
    static final class Lease implements AutoCloseable {
        private final FileChannel channel;private final FileLock lock;private boolean closed;
        Lease(FileChannel channel,FileLock lock){this.channel=channel;this.lock=lock;}
        @Override public synchronized void close(){if(closed)return;closed=true;try{lock.release();}catch(IOException error){throw new BackendException(BackendException.Code.DEVICE_ERROR,"GPU owner unlock failed",error);}finally{try{channel.close();}catch(IOException error){throw new BackendException(BackendException.Code.DEVICE_ERROR,"GPU owner channel close failed",error);}}}
    }
}
