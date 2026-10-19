package com.wenkrang.famara.render.asset;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeModelDataReaderTest {
    private static final String MESH="net/minecraft/client/model/geom/builders/MeshDefinition";
    private static final String LAYER="net/minecraft/client/model/geom/builders/LayerDefinition";
    private byte[] definition(boolean unsafe,boolean loop) {
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);writer.visit(Opcodes.V25,Opcodes.ACC_PUBLIC,NativeModelDataReader.OWNER,null,"java/lang/Object",null);
        MethodVisitor clinit=writer.visitMethod(Opcodes.ACC_STATIC,"<clinit>","()V",null,null);clinit.visitCode();
        clinit.visitLdcInsn("famara.native.code.executed");clinit.visitLdcInsn("yes");clinit.visitMethodInsn(Opcodes.INVOKESTATIC,"java/lang/System","setProperty","(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",false);clinit.visitInsn(Opcodes.POP);clinit.visitInsn(Opcodes.RETURN);clinit.visitMaxs(0,0);clinit.visitEnd();
        MethodVisitor code=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"createBodyLayer","()L"+LAYER+";",null,null);code.visitCode();
        if(unsafe){code.visitLdcInsn("famara.native.code.executed");code.visitLdcInsn("bad");code.visitMethodInsn(Opcodes.INVOKESTATIC,"java/lang/System","setProperty","(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",false);code.visitInsn(Opcodes.POP);}
        if(loop){Label label=new Label();code.visitLabel(label);code.visitJumpInsn(Opcodes.GOTO,label);}
        code.visitTypeInsn(Opcodes.NEW,MESH);code.visitInsn(Opcodes.DUP);code.visitMethodInsn(Opcodes.INVOKESPECIAL,MESH,"<init>","()V",false);
        code.visitIntInsn(Opcodes.BIPUSH,64);code.visitIntInsn(Opcodes.BIPUSH,64);code.visitMethodInsn(Opcodes.INVOKESTATIC,LAYER,"create","(L"+MESH+";II)L"+LAYER+";",false);
        code.visitInsn(Opcodes.ARETURN);code.visitMaxs(0,0);code.visitEnd();writer.visitEnd();return writer.toByteArray();
    }
    private NativeModelDataReader reader(byte[] code) {
        ResourcePack pack=ResourcePack.memory("untrusted-shape-data",Map.of(NativeModelDataReader.OWNER+".class",code),RenderFixtures.LIMITS);
        return new NativeModelDataReader(pack,RenderFixtures.LIMITS,new AssetMemoryBudget(RenderFixtures.LIMITS.maxTotalBytes()));
    }
    @Test void staticInitializerIsNeverLoadedOrExecuted() {
        String before=System.getProperty("famara.native.code.executed");
        var shape=reader(definition(false,false)).read("createBodyLayer");
        assertEquals(64,shape.width());assertTrue(shape.root().children().isEmpty());
        assertEquals(before,System.getProperty("famara.native.code.executed"));
    }
    @Test void externalCallsAndJumpsAreRejectedWithoutSideEffects() {
        String before=System.getProperty("famara.native.code.executed");
        assertEquals(AssetException.Code.UNSUPPORTED,assertThrows(AssetException.class,()->reader(definition(true,false)).read("createBodyLayer")).code());
        assertEquals(AssetException.Code.UNSUPPORTED,assertThrows(AssetException.class,()->reader(definition(false,true)).read("createBodyLayer")).code());
        assertEquals(before,System.getProperty("famara.native.code.executed"));
    }
    @Test void arbitraryMethodsCannotBeSelectedAsShapeFactories() {
        var reader=reader(definition(false,false));assertThrows(AssetException.class,()->reader.read("<clinit>"));
        assertThrows(AssetException.class,()->reader.read("setupAnim"));
    }
}
