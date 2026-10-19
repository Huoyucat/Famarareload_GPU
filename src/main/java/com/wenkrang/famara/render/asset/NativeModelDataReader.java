package com.wenkrang.famara.render.asset;

import com.wenkrang.famara.render.math.Vec3;
import com.google.gson.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;

/** 只提取白名单内置模型的构建数据。没有类加载、反射调用、任意方法执行或跳转指令。 */
final class NativeModelDataReader {
    static final String DATA="assets/famara/native_models/copper_golem.json";
    static final String OWNER="net/minecraft/client/model/animal/golem/CopperGolemModel";
    private static final String BUILDERS="net/minecraft/client/model/geom/builders/";
    private static final String POSE="net/minecraft/client/model/geom/PartPose";
    private static final Set<String> ENTRY=Set.of("createBodyLayer","createRunningPoseBodyLayer","createSittingPoseBodyLayer","createStarPoseBodyLayer");
    record Pose(Vec3 offset,Vec3 rotation,Vec3 scale) {
        static final Pose ZERO=new Pose(new Vec3(0,0,0),new Vec3(0,0,0),new Vec3(1,1,1));
    }
    record Cube(double x,double y,double z,double w,double h,double d,double u,double v,Vec3 inflation,boolean mirror) {}
    record Part(String name,Pose pose,List<Cube> cubes,List<Part> children) {}
    record Shape(Part root,int width,int height) {}
    private static final class PartData {
        final String name;Pose pose=Pose.ZERO;List<Cube> cubes=List.of();
        final Map<String,PartData> children=new LinkedHashMap<>();
        PartData(String name){this.name=name;}
        Part freeze(){return freeze(0);}
        Part freeze(int depth){if(depth>64)throw unsupported("形状部件层级过深");return new Part(name,pose,List.copyOf(cubes),children.values().stream().map(c->c.freeze(depth+1)).toList());}
    }
    private static final class Mesh { final PartData root=new PartData("root"); }
    private static final class Cubes { double u,v;boolean mirror;final List<Cube> cubes=new ArrayList<>(); }
    private static final class Allocation { final String type;Object value;Allocation(String type){this.type=type;} }
    private record Lambda(String name,String descriptor) {}
    private record Layer(Mesh mesh,int width,int height) {}
    private final ClassNode node=new ClassNode();
    private final AssetLimits limits;
    private final AssetMemoryBudget memory;
    private final Map<String,Shape> shapes=new HashMap<>();
    private int operations,boxes;
    private final Map<String,Shape> dataShapes=new HashMap<>();
    NativeModelDataReader(ResourcePack pack,AssetLimits limits,AssetMemoryBudget memory) {
        this.limits=limits;this.memory=memory;
        ResourcePack.Content data=pack.find(DATA);
        if(data!=null) {
            if(data.role()==ResourcePack.Role.CUSTOM)throw unsupported("原版基础形状不能由CUSTOM源替换");
            memory.reserve(Math.multiplyExact(data.size(),128L),DATA);
            JsonObject json=BoundedJson.read(data,DATA,limits);Texture.checkFields(json,Set.of("format","clientVersion","methods"),DATA);
            if(JsonValues.integer(json.get("format"))!=1)throw unsupported("内置形状数据版本未知");
            JsonObject methods=json.getAsJsonObject("methods");Texture.checkFields(methods,ENTRY,DATA);
            if(!methods.keySet().equals(ENTRY))throw unsupported("内置形状姿态不完整");
            for(String method:ENTRY) {
                JsonObject object=methods.getAsJsonObject(method);Texture.checkFields(object,Set.of("root","width","height"),DATA);validateFields(object.getAsJsonObject("root"),0);
                Shape shape=new Gson().fromJson(object,Shape.class);int[] counts={0,0};
                if(shape==null || shape.root()==null || shape.width()<=0 || shape.height()<=0 || (long)shape.width()*shape.height()>limits.maxTexturePixels())throw unsupported("形状尺寸无效");
                validate(shape.root(),0,counts);dataShapes.put(method,shape);
            }
            return;
        }
        ResourcePack.Content content=pack.require(OWNER+".class");
        memory.reserve(Math.multiplyExact(content.size(),128L),OWNER);
        try { new ClassReader(content.copyBytes()).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES); }
        catch(RuntimeException e){throw new AssetException(AssetException.Code.INVALID,OWNER,"内置形状数据损坏",e);}
        if(!OWNER.equals(node.name))throw unsupported("形状类身份不符");
    }
    Shape read(String method) {
        if(!ENTRY.contains(method))throw unsupported("非白名单形状入口");
        if(!dataShapes.isEmpty())return dataShapes.get(method);
        Shape cached=shapes.get(method);if(cached!=null)return cached;
        operations=0;boxes=0;
        Object output=evaluate(method,"()L"+BUILDERS+"LayerDefinition;",new Object[0],0);
        if(!(output instanceof Layer layer))throw unsupported("形状入口没有返回模型数据");
        Shape result=new Shape(layer.mesh.root.freeze(),layer.width,layer.height);shapes.put(method,result);return result;
    }
    private void validateFields(JsonObject part,int depth) {
        if(depth>Math.min(64,limits.maxJsonDepth()))throw unsupported("形状数据层级过深");
        Texture.checkFields(part,Set.of("name","pose","cubes","children"),DATA);JsonObject pose=part.getAsJsonObject("pose");Texture.checkFields(pose,Set.of("offset","rotation","scale"),DATA);
        for(String field:List.of("offset","rotation","scale"))Texture.checkFields(pose.getAsJsonObject(field),Set.of("x","y","z"),DATA);
        for(JsonElement cube:part.getAsJsonArray("cubes")){JsonObject item=cube.getAsJsonObject();Texture.checkFields(item,Set.of("x","y","z","w","h","d","u","v","inflation","mirror"),DATA);Texture.checkFields(item.getAsJsonObject("inflation"),Set.of("x","y","z"),DATA);}
        for(JsonElement child:part.getAsJsonArray("children"))validateFields(child.getAsJsonObject(),depth+1);
    }
    private void validate(Part part,int depth,int[] counts) {
        if(depth>Math.min(64,limits.maxJsonDepth()) || ++counts[0]>limits.maxJsonNodes() || part==null || part.name()==null || part.name().length()>128 || part.pose()==null || part.cubes()==null || part.children()==null)throw unsupported("形状部件结构超限");
        Pose pose=part.pose();if(pose.offset()==null || pose.rotation()==null || pose.scale()==null)throw unsupported("形状姿态缺失");
        for(Vec3 vector:List.of(pose.offset(),pose.rotation(),pose.scale()))for(int axis=0;axis<3;axis++)if(!Double.isFinite(vector.component(axis)) || Math.abs(vector.component(axis))>10000)throw unsupported("形状姿态数值无效");
        for(Cube cube:part.cubes()) {
            if(cube==null || cube.inflation()==null || ++counts[1]>limits.maxModelFaces()/6)throw unsupported("形状方盒超限");
            for(double value:new double[]{cube.x(),cube.y(),cube.z(),cube.w(),cube.h(),cube.d(),cube.u(),cube.v(),cube.inflation().x(),cube.inflation().y(),cube.inflation().z()})if(!Double.isFinite(value) || Math.abs(value)>10000)throw unsupported("形状方盒数值无效");
            if(cube.w()<0 || cube.h()<0 || cube.d()<0)throw unsupported("形状方盒尺寸为负");
        }
        for(Part child:part.children())validate(child,depth+1,counts);
    }
    private Object evaluate(String name,String descriptor,Object[] arguments,int depth) {
        if(depth>8)throw unsupported("形状定义引用过深");
        MethodNode method=node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(descriptor)).findFirst().orElseThrow(()->unsupported("缺少形状方法 "+name));
        if(method.maxLocals>256 || method.instructions.size()>20000)throw unsupported("形状方法超过结构限额");
        Object[] locals=new Object[Math.max(method.maxLocals,arguments.length)];System.arraycopy(arguments,0,locals,0,arguments.length);
        ArrayList<Object> stack=new ArrayList<>();
        for(AbstractInsnNode instruction:method.instructions) {
            if(++operations>100000)throw unsupported("形状解析操作过多");
            int opcode=instruction.getOpcode();if(opcode<0)continue;
            if(instruction instanceof LdcInsnNode literal) {
                if(!(literal.cst instanceof Number) && !(literal.cst instanceof String))throw unsupported("不支持的常量类型");
                stack.add(literal.cst);continue;
            }
            if(instruction instanceof IntInsnNode integer) {
                if(opcode!=Opcodes.BIPUSH && opcode!=Opcodes.SIPUSH)throw unsupported("不支持数组分配");
                stack.add(integer.operand);continue;
            }
            if(instruction instanceof VarInsnNode variable) {
                if(opcode==Opcodes.ALOAD || opcode==Opcodes.FLOAD || opcode==Opcodes.ILOAD)stack.add(locals[variable.var]);
                else if(opcode==Opcodes.ASTORE || opcode==Opcodes.FSTORE || opcode==Opcodes.ISTORE)locals[variable.var]=pop(stack);
                else throw unsupported("未知局部变量指令");continue;
            }
            if(instruction instanceof TypeInsnNode type) {
                if(opcode!=Opcodes.NEW || !Set.of(BUILDERS+"MeshDefinition",BUILDERS+"CubeDeformation").contains(type.desc))throw unsupported("非白名单对象分配");
                stack.add(new Allocation(type.desc));continue;
            }
            if(instruction instanceof FieldInsnNode field) {
                if(opcode!=Opcodes.GETSTATIC)throw unsupported("禁止读取或写入运行时字段");
                if(field.owner.equals(POSE) && field.name.equals("ZERO"))stack.add(Pose.ZERO);
                else if(field.owner.equals(BUILDERS+"CubeDeformation") && field.name.equals("NONE"))stack.add(new Vec3(0,0,0));
                else throw unsupported("非白名单静态字段 "+field.owner);continue;
            }
            if(instruction instanceof InvokeDynamicInsnNode dynamic) {
                if(!dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") || Type.getArgumentTypes(dynamic.desc).length!=0
                        || dynamic.bsmArgs.length<2 || !(dynamic.bsmArgs[1] instanceof Handle handle)
                        || !handle.getOwner().equals(OWNER) || !handle.getName().startsWith("lambda$create"))throw unsupported("非形状常量变换");
                stack.add(new Lambda(handle.getName(),handle.getDesc()));continue;
            }
            if(instruction instanceof MethodInsnNode call) {
                Type[] parameters=Type.getArgumentTypes(call.desc);Object[] args=new Object[parameters.length];
                for(int i=args.length-1;i>=0;i--)args[i]=unwrap(pop(stack));
                Object receiver=opcode==Opcodes.INVOKESTATIC?null:pop(stack);
                Object value=invoke(call.owner,call.name,call.desc,receiver,args,depth);
                if(Type.getReturnType(call.desc).getSort()!=Type.VOID)stack.add(value);continue;
            }
            switch(opcode) {
                case Opcodes.ACONST_NULL->stack.add(null);
                case Opcodes.ICONST_M1,Opcodes.ICONST_0,Opcodes.ICONST_1,Opcodes.ICONST_2,Opcodes.ICONST_3,Opcodes.ICONST_4,Opcodes.ICONST_5->stack.add(opcode-Opcodes.ICONST_0);
                case Opcodes.FCONST_0,Opcodes.FCONST_1,Opcodes.FCONST_2->stack.add((float)(opcode-Opcodes.FCONST_0));
                case Opcodes.DUP->stack.add(stack.getLast());
                case Opcodes.POP->pop(stack);
                case Opcodes.FNEG->stack.add(-number(pop(stack)));
                case Opcodes.FADD,Opcodes.FSUB,Opcodes.FMUL,Opcodes.FDIV->{double b=number(pop(stack)),a=number(pop(stack));stack.add(switch(opcode){case Opcodes.FADD->a+b;case Opcodes.FSUB->a-b;case Opcodes.FMUL->a*b;default->a/b;});}
                case Opcodes.I2F,Opcodes.F2D,Opcodes.D2F->{}
                case Opcodes.ARETURN-> {return unwrap(pop(stack));}
                case Opcodes.RETURN-> {return null;}
                default->throw unsupported("禁止或未知的字节码指令 "+opcode);
            }
        }
        throw unsupported("形状定义没有结束");
    }
    private Object invoke(String owner,String name,String descriptor,Object receiver,Object[] a,int depth) {
        if(name.equals("<init>")) {
            if(!(receiver instanceof Allocation allocation))throw unsupported("非法构造目标");
            if(allocation.type.equals(BUILDERS+"MeshDefinition") && a.length==0)allocation.value=new Mesh();
            else if(allocation.type.equals(BUILDERS+"CubeDeformation") && (a.length==1 || a.length==3))
                allocation.value=a.length==1?new Vec3(n(a,0),n(a,0),n(a,0)):vec(a,0);
            else throw unsupported("未知构造数据");return null;
        }
        Object r=unwrap(receiver);
        if(owner.equals(OWNER) && ENTRY.contains(name))return evaluate(name,descriptor,a,depth+1);
        if(owner.equals(POSE)) {
            if(name.equals("offset"))return new Pose(vec(a,0),Pose.ZERO.rotation,Pose.ZERO.scale);
            if(name.equals("rotation"))return new Pose(Pose.ZERO.offset,vec(a,0),Pose.ZERO.scale);
            if(name.equals("offsetAndRotation"))return new Pose(vec(a,0),vec(a,3),Pose.ZERO.scale);
            if(r instanceof Pose pose && name.equals("translated"))return new Pose(pose.offset.add(vec(a,0)),pose.rotation,pose.scale);
            if(r instanceof Pose pose && name.equals("scaled")) {
                Vec3 scale=a.length==1?new Vec3(n(a,0),n(a,0),n(a,0)):vec(a,0);
                return new Pose(times(pose.offset,scale),pose.rotation,times(pose.scale,scale));
            }
        }
        if(owner.equals(BUILDERS+"MeshDefinition") && r instanceof Mesh mesh) {
            if(name.equals("getRoot"))return mesh.root;
            if(name.equals("transformed") && a[0] instanceof Lambda lambda) {
                Object changed=evaluate(lambda.name,lambda.descriptor,new Object[]{mesh.root.pose},depth+1);
                if(!(changed instanceof Pose))throw unsupported("无效根变换");mesh.root.pose=(Pose)changed;return mesh;
            }
        }
        if(owner.equals(BUILDERS+"PartDefinition") && r instanceof PartData part) {
            if(name.equals("getChild"))return Objects.requireNonNull(part.children.get((String)a[0]));
            if(name.equals("addOrReplaceChild") && a.length==3 && a[1] instanceof Cubes cubes && a[2] instanceof Pose pose) {
                memory.reserve(256+cubes.cubes.size()*128L,OWNER);
                PartData child=new PartData((String)a[0]);child.pose=pose;child.cubes=List.copyOf(cubes.cubes);
                PartData old=part.children.put(child.name,child);if(old!=null)child.children.putAll(old.children);return child;
            }
        }
        if(owner.equals(BUILDERS+"CubeListBuilder")) {
            if(name.equals("create") && r==null)return new Cubes();
            if(!(r instanceof Cubes cubes))throw unsupported("无效几何构建数据");
            if(name.equals("texOffs")) {cubes.u=n(a,0);cubes.v=n(a,1);return cubes;}
            if(name.equals("mirror")) {cubes.mirror=a.length==0 || n(a,0)!=0;return cubes;}
            if(name.equals("addBox")) {
                int start=a.length>0 && a[0] instanceof String?1:0;
                double u=cubes.u,v=cubes.v;Vec3 inflation=new Vec3(0,0,0);
                if(start==1 && a.length==9) {u=n(a,7);v=n(a,8);}
                else if(a.length==start+7 && a[start+6] instanceof Vec3 expand)inflation=expand;
                else if(a.length!=start+6)throw unsupported("不支持的几何参数签名");
                for(int i=start;i<start+6;i++)if(!Double.isFinite(n(a,i)))throw unsupported("几何数值非有限");
                if(n(a,start+3)<0 || n(a,start+4)<0 || n(a,start+5)<0 || cubes.cubes.size()>=limits.maxModelFaces()/6)throw unsupported("几何尺寸或数量超限");
                if(++boxes>limits.maxModelFaces()/6)throw unsupported("模型部件总数超限");
                memory.reserve(3072,OWNER);
                cubes.cubes.add(new Cube(n(a,start),n(a,start+1),n(a,start+2),n(a,start+3),n(a,start+4),n(a,start+5),u,v,inflation,cubes.mirror));return cubes;
            }
        }
        if(owner.equals(BUILDERS+"LayerDefinition") && name.equals("create") && a[0] instanceof Mesh mesh)
            return new Layer(mesh,(int)n(a,1),(int)n(a,2));
        throw unsupported("不支持的形状数据操作 "+owner+"."+name);
    }
    private static Object unwrap(Object v){return v instanceof Allocation a?Objects.requireNonNull(a.value):v;}
    private static Object pop(ArrayList<Object> stack){if(stack.isEmpty())throw new IllegalArgumentException("形状数据栈为空");return stack.removeLast();}
    private static double number(Object value){if(!(value instanceof Number n))throw new IllegalArgumentException("形状参数不是数值");return n.doubleValue();}
    private static double n(Object[] a,int i){return number(a[i]);}
    private static Vec3 vec(Object[] a,int i){return new Vec3(n(a,i),n(a,i+1),n(a,i+2));}
    private static Vec3 times(Vec3 a,Vec3 b){return new Vec3(a.x()*b.x(),a.y()*b.y(),a.z()*b.z());}
    private static AssetException unsupported(String reason){return new AssetException(AssetException.Code.UNSUPPORTED,OWNER,reason);}
}
