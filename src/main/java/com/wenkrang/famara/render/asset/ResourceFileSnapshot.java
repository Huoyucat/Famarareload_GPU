package com.wenkrang.famara.render.asset;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.LongSupplier;

/** 有界分段复制并二次校验外部文件。原文件句柄每个片段立即关闭，ZipFile只打开私有副本。 */
public final class ResourceFileSnapshot implements AutoCloseable {
    private enum Stage { INIT, COPY, VERIFY, DONE }
    private record Stamp(long size,Object key,java.nio.file.attribute.FileTime modified) {}
    private final Path source;
    private final long maximumBytes;
    private final String expectedSha256;
    private Path privateFile;
    private Stamp stamp;
    private byte[] buffer,firstHash;
    private MessageDigest digest;
    private long copied,verified;
    private Stage stage=Stage.INIT;
    private boolean closed,failed;
    public ResourceFileSnapshot(Path source,long maximumBytes) {
        this(source,maximumBytes,null);
    }
    public ResourceFileSnapshot(Path source,long maximumBytes,String expectedSha256) {
        if(expectedSha256!=null && !expectedSha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("来源SHA256无效");this.expectedSha256=expectedSha256;
        this.source=Objects.requireNonNull(source);if(maximumBytes<=0)throw new IllegalArgumentException("资源文件副本限额必须为正");this.maximumBytes=maximumBytes;
    }
    public boolean step(int operations,long deadline,LongSupplier clock)throws IOException {
        if(closed || failed || operations<=0)throw new IllegalStateException("资源副本游标已关闭、失败或步长无效");
        try {
            for(int work=0;work<operations && stage!=Stage.DONE && clock.getAsLong()<deadline;work++)advance();
            return stage==Stage.DONE;
        } catch(IOException | RuntimeException | Error error){failed=true;throw error;}
    }
    private Stamp current()throws IOException {
        BasicFileAttributes value=Files.readAttributes(source,BasicFileAttributes.class);
        if(!value.isRegularFile())throw new IOException("资源不是普通文件："+source);
        return new Stamp(value.size(),value.fileKey(),value.lastModifiedTime());
    }
    private void checkSource()throws IOException {if(!stamp.equals(current()))throw changed();}
    private void advance()throws IOException {
        if(stage==Stage.INIT) {
            stamp=current();if(stamp.size>maximumBytes)throw new AssetException(AssetException.Code.LIMIT,source.toString(),"资源副本超过来源读取限额");
            buffer=new byte[(int)Math.min(256*1024,maximumBytes)];digest=ResourcePack.sha256();
            privateFile=Files.createTempFile("famara-render-source-",".zip");stage=Stage.COPY;return;
        }
        boolean copying=stage==Stage.COPY;long offset=copying?copied:verified;
        checkSource();
        if(offset==stamp.size) {
            byte[] actual=digest.digest();
            if(copying){firstHash=actual;digest=ResourcePack.sha256();stage=Stage.VERIFY;}
            else {if(!Arrays.equals(firstHash,actual))throw changed();if(expectedSha256!=null && !HexFormat.of().formatHex(actual).equals(expectedSha256))throw new IOException("内置基础资源SHA256校验不符："+source);stage=Stage.DONE;buffer=null;digest=null;firstHash=null;}
            return;
        }
        int length=(int)Math.min(buffer.length,stamp.size-offset);ByteBuffer bytes=ByteBuffer.wrap(buffer,0,length);
        // 使用普通FileChannel，避免ZipFile的Windows句柄在让出/限流等待期间占用CE原ZIP。
        try(FileChannel input=FileChannel.open(source,StandardOpenOption.READ)) {
            input.position(offset);
            while(bytes.hasRemaining()){int read=input.read(bytes);if(read<0)throw changed();if(read==0)throw new IOException("读取资源副本没有进展："+source);}
        }
        checkSource();digest.update(buffer,0,length);
        if(copying) {
            bytes.flip();try(FileChannel output=FileChannel.open(privateFile,StandardOpenOption.WRITE)) {
                output.position(offset);while(bytes.hasRemaining())if(output.write(bytes)==0)throw new IOException("写入资源副本没有进展");
            }
            copied+=length;
        } else verified+=length;
    }
    public Path path(){if(closed || failed || stage!=Stage.DONE)throw new IllegalStateException("资源副本尚未完成");return privateFile;}
    public long copiedBytes(){return copied;}
    public long verifiedBytes(){return verified;}
    private AssetException changed(){return new AssetException(AssetException.Code.SOURCE_CHANGED,source.toString(),"复制期间外部资源变化，未使用不一致副本");}
    @Override public void close()throws IOException {
        if(closed)return;closed=true;buffer=null;digest=null;firstHash=null;
        if(privateFile!=null)Files.deleteIfExists(privateFile);privateFile=null;
    }
}
