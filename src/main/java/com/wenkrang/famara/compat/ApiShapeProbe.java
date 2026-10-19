package com.wenkrang.famara.compat;

import org.objectweb.asm.*;
import java.io.*;
import java.util.*;

/** 验证真正使用的字段ABI，不因解析无关方法的异常类型而初始化整个可选后端。 */
final class ApiShapeProbe {
    private ApiShapeProbe() {}
    static void publicStaticMaps(Class<?> owner,String... names) throws ReflectiveOperationException {
        Set<String> required=new HashSet<>(Arrays.asList(names));
        try(InputStream in=owner.getResourceAsStream("/"+owner.getName().replace('.','/')+".class")) {
            if(in==null)throw new NoSuchFieldException("无法读取API结构："+owner.getName());
            byte[] bytes=in.readNBytes(1024*1024+1);
            if(bytes.length>1024*1024)throw new NoSuchFieldException("API结构过大："+owner.getName());
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public FieldVisitor visitField(int access,String name,String descriptor,String signature,Object value) {
                    if((access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC))==(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC)
                            && descriptor.equals("Ljava/util/Map;"))required.remove(name);
                    return null;
                }
            },ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        } catch(IOException | IllegalArgumentException e) {throw new ReflectiveOperationException("API结构检测失败："+owner.getName(),e);}
        if(!required.isEmpty())throw new NoSuchFieldException(owner.getName()+" 缺少 public static Map 字段："+required);
    }
}
