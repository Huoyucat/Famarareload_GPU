package com.wenkrang.famara.render.asset;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** 打开ZipFile前读取有界EOCD/ZIP64元数据，预付中央目录及JDK索引空间。 */
public final class ZipDirectoryBudget {
    private ZipDirectoryBudget() {}
    public static long estimatedBytes(Path path)throws IOException {
        try(FileChannel file=FileChannel.open(path,StandardOpenOption.READ)) {
            long size=file.size();int length=(int)Math.min(65557,size);ByteBuffer tail=ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);file.position(size-length);read(file,tail);tail.flip();
            for(int i=length-22;i>=0;i--)if(tail.getInt(i)==0x06054b50 && i+22+(tail.getShort(i+20)&65535)==length) {
                if(tail.getShort(i+4)!=0 || tail.getShort(i+6)!=0)throw new IOException("不支持分卷ZIP来源");long bytes=Integer.toUnsignedLong(tail.getInt(i+12));
                if(bytes==0xffffffffL || (tail.getShort(i+10)&65535)==65535) {
                    long at=size-length+i-20;if(at<0)throw new IOException("ZIP64定位缺失");ByteBuffer locator=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);file.position(at);read(file,locator);locator.flip();
                    if(locator.getInt(0)!=0x07064b50 || locator.getInt(4)!=0 || locator.getInt(16)!=1)throw new IOException("ZIP64定位无效");at=locator.getLong(8);if(at<0 || at>size-56)throw new IOException("ZIP64偏移无效");ByteBuffer header=ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);file.position(at);read(file,header);header.flip();if(header.getInt(0)!=0x06064b50)throw new IOException("ZIP64结束标记无效");bytes=header.getLong(40);
                }
                if(bytes<0 || bytes>size)throw new IOException("ZIP中央目录大小无效");return Math.addExact(65536,Math.multiplyExact(2,bytes));
            }
            throw new IOException("ZIP结束目录不存在");
        }catch(ArithmeticException error){throw new IOException("ZIP目录大小溢出",error);}
    }
    private static void read(FileChannel file,ByteBuffer bytes)throws IOException {while(bytes.hasRemaining())if(file.read(bytes)<=0)throw new EOFException("ZIP目录读取不完整");}
}
