package com.github.goguma9071.jvmplus.processor;

import com.github.goguma9071.jvmplus.memory.MemoryPool;
import com.github.goguma9071.jvmplus.memory.Pointer;
import com.github.goguma9071.jvmplus.memory.Struct;
import com.github.goguma9071.jvmplus.processor.model.FieldModel;
import com.github.goguma9071.jvmplus.processor.model.StructModel;
import com.squareup.javapoet.*;

import javax.annotation.processing.Filer;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.io.IOException;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class JPCGenerator {
    private final Filer filer;

    public JPCGenerator(Filer filer, javax.lang.model.util.Types types) {
        this.filer = filer;
    }

    public void generateMegaClass(String packageName, List<StructModel> models) throws IOException {
        TypeSpec.Builder megaClassBuilder = TypeSpec.classBuilder("JPINTERNAL")
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

        CodeBlock.Builder staticInit = CodeBlock.builder();

        for (StructModel model : models) {
            megaClassBuilder.addType(generateAoS(model));
            megaClassBuilder.addType(generateSoA(model));

            staticInit.addStatement("com.github.goguma9071.jvmplus.memory.MemoryManager.registerFactory($S, (seg, pool) -> new $L(seg, pool))", 
                model.packageName() + "." + model.interfaceName(), 
                model.implBaseName() + "Impl");
        }

        megaClassBuilder.addStaticBlock(staticInit.build());

        JavaFile.builder(packageName, megaClassBuilder.build())
                .addStaticImport(ClassName.get("com.github.goguma9071.jvmplus.memory", "MemoryManager"), "EVERYTHING")
                .build()
                .writeTo(filer);
    }

    private ClassName getInterfaceType(StructModel model) {
        return ClassName.get(model.packageName(), model.interfaceName());
    }

    private TypeSpec generateAoS(StructModel model) {
        String implName = model.implBaseName() + "Impl";
        ClassName interfaceType = getInterfaceType(model);

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(implName)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addSuperinterface(interfaceType);

        classBuilder.addField(MemorySegment.class, "segment", Modifier.PRIVATE);
        classBuilder.addField(long.class, "_address", Modifier.PRIVATE);
        classBuilder.addField(MemoryPool.class, "pool", Modifier.PRIVATE, Modifier.FINAL);

        MethodSpec.Builder constr = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(MemorySegment.class, "segment")
                .addParameter(MemoryPool.class, "pool")
                .addStatement("this.segment = segment")
                .addStatement("this._address = segment != null ? segment.address() : 0L")
                .addStatement("this.pool = pool");

        CodeBlock.Builder staticInit = CodeBlock.builder();
        staticInit.addStatement("java.util.List<java.lang.foreign.MemoryLayout> elements = new java.util.ArrayList<>()");
        
        long lastEnd = 0;
        for (FieldModel f : model.fields()) {
            if (f.isStatic()) continue;

            if (f.isStruct()) {
                String n = f.nestedImplName();
                String simpleN = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : n;
                classBuilder.addField(ClassName.get("", simpleN), f.name() + "_flyweight", Modifier.PRIVATE, Modifier.FINAL);
                constr.addStatement("this.$L_flyweight = ($T) com.github.goguma9071.jvmplus.memory.MemoryManager.createEmptyStruct($T.class)", f.name(), ClassName.get("", simpleN), TypeName.get(f.type()));
            }

            String offsetName = f.name().toUpperCase() + "_OFFSET";
            classBuilder.addField(long.class, offsetName, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
            staticInit.addStatement("$L = $L", offsetName, f.calculatedOffset());

            if (!f.isBitField() || f.name().equals(f.bitFieldBackingName())) {
                long currentOffset = f.calculatedOffset();
                if (currentOffset > lastEnd) staticInit.addStatement("elements.add(java.lang.foreign.MemoryLayout.paddingLayout($L))", currentOffset - lastEnd);
                staticInit.addStatement("elements.add($L.withName($S))", getLayoutCode(f), f.isBitField() ? f.bitFieldBackingName() : f.name());
                lastEnd = currentOffset + f.size();

                if (!f.isStruct() && !f.isString() && !f.isRaw()) {
                    String vhName = (f.isBitField() ? f.bitFieldBackingName() : f.name()).toUpperCase() + "_VH";
                    classBuilder.addField(VarHandle.class, vhName, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
                    // 레이아웃 경로를 쓰지 않고, 해당 필드의 ValueLayout에서 직접 VarHandle을 생성하여 안전성 확보
                    String vhLayout = f.isArray() ? getSimpleLayoutCode(f.type(), f.alignment()) : getLayoutCode(f);
                    staticInit.addStatement("$L = ($T) $L.varHandle()", vhName, VarHandle.class, vhLayout);
                }
            }
        }

        for (ExecutableElement nc : model.nativeCalls()) {
            String funcName = nc.getSimpleName().toString();
            String handleName = "NC_" + funcName.toUpperCase() + "_HANDLE";
            classBuilder.addField(java.lang.invoke.MethodHandle.class, handleName, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
            staticInit.addStatement("java.lang.foreign.MemorySegment _symbol_$L = java.lang.foreign.Linker.nativeLinker().defaultLookup().find($S).orElseGet(() -> java.lang.foreign.SymbolLookup.loaderLookup().find($S).orElse(null))", funcName, funcName, funcName);
            staticInit.addStatement("$L = (_symbol_$L == null) ? null : java.lang.foreign.Linker.nativeLinker().downcallHandle(_symbol_$L, $L)", handleName, funcName, funcName, getDescriptorCode(nc));
            
            MethodSpec.Builder mb = MethodSpec.overriding(nc);
            mb.beginControlFlow("try");
            mb.addStatement("if ($L == null) throw new UnsupportedOperationException($S)", handleName, "Native function not found: " + funcName);
            List<String> args = nc.getParameters().stream().map(p -> p.getSimpleName().toString()).collect(Collectors.toList());
            if (nc.getReturnType().getKind() == TypeKind.VOID) mb.addStatement("$L.invoke($L)", handleName, String.join(", ", args));
            else mb.addStatement("return ($T) $L.invoke($L)", TypeName.get(nc.getReturnType()), handleName, String.join(", ", args));
            mb.nextControlFlow("catch (Throwable _t)").addStatement("throw new RuntimeException(_t)").endControlFlow();
            classBuilder.addMethod(mb.build());
        }

        classBuilder.addField(GroupLayout.class, "LAYOUT", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
        classBuilder.addField(MemorySegment.class, "STATIC_SEGMENT", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
        staticInit.addStatement("LAYOUT = java.lang.foreign.MemoryLayout.structLayout(elements.toArray(new java.lang.foreign.MemoryLayout[0]))");
        staticInit.addStatement("STATIC_SEGMENT = java.lang.foreign.Arena.global().allocate(LAYOUT)");
        classBuilder.addStaticBlock(staticInit.build()).addMethod(constr.build());

        implementCommonAoSMethods(classBuilder, model, interfaceType);
        implementFieldMethods(classBuilder, model, false, interfaceType);

        return classBuilder.build();
    }

    private TypeSpec generateSoA(StructModel model) {
        ClassName interfaceType = getInterfaceType(model);
        String soaName = model.implBaseName() + "SoAImpl";
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(soaName)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(ClassName.get("com.github.goguma9071.jvmplus.memory", "StructArray"), interfaceType))
                .addSuperinterface(interfaceType);

        classBuilder.addField(int.class, "currentIndex", Modifier.PRIVATE)
                .addField(int.class, "capacity", Modifier.PRIVATE, Modifier.FINAL)
                .addField(java.lang.foreign.Arena.class, "arena", Modifier.PRIVATE, Modifier.FINAL)
                .addField(MemorySegment.class, "totalSegment", Modifier.PRIVATE, Modifier.FINAL);

        MethodSpec.Builder constr = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(int.class, "capacity")
                .addStatement("this.capacity = capacity").addStatement("this.arena = java.lang.foreign.Arena.ofShared()");

        CodeBlock.Builder staticInit = CodeBlock.builder();
        classBuilder.addField(MemorySegment.class, "STATIC_SEGMENT", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
        staticInit.addStatement("STATIC_SEGMENT = $LImpl.STATIC_SEGMENT", model.implBaseName());

        constr.addStatement("java.util.List<java.lang.foreign.MemoryLayout> elements = new java.util.ArrayList<>()");

        for (FieldModel f : model.fields()) {
            if (f.isStatic()) continue;
            String segName = f.name() + "_Segment";
            String layoutCode = getLayoutCode(f);

            if (f.isStruct()) {
                String n = f.nestedImplName();
                String simpleN = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : n;
                classBuilder.addField(MemorySegment.class, segName, Modifier.PUBLIC, Modifier.FINAL);
                constr.addStatement("elements.add(java.lang.foreign.MemoryLayout.sequenceLayout((long)capacity, $L.LAYOUT).withName($S))", simpleN, f.name());
                classBuilder.addField(TypeName.get(f.type()), f.name() + "_flyweight", Modifier.PRIVATE, Modifier.FINAL);
                constr.addStatement("this.$L_flyweight = ($T) com.github.goguma9071.jvmplus.memory.MemoryManager.createEmptyStruct($T.class)", f.name(), TypeName.get(f.type()), TypeName.get(f.type()));
            } else {
                classBuilder.addField(MemorySegment.class, segName, Modifier.PUBLIC, Modifier.FINAL);
                constr.addStatement("elements.add(java.lang.foreign.MemoryLayout.sequenceLayout((long)capacity, $L).withName($S))", layoutCode, f.name());
                if (f.type().getKind().isPrimitive() && !f.isArray() && !f.isPointer()) {
                    String vhName = f.name().toUpperCase() + "_VH";
                    classBuilder.addField(VarHandle.class, vhName, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
                    staticInit.addStatement("$L = $L.arrayElementVarHandle()", vhName, layoutCode);
                    classBuilder.addField(ClassName.get("jdk.incubator.vector", "VectorSpecies"), f.name().toUpperCase() + "_SPECIES", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
                    staticInit.addStatement("$L_SPECIES = jdk.incubator.vector.$L.SPECIES_PREFERRED", f.name().toUpperCase(), getVectorType(f.type().getKind()));
                }
            }
        }

        constr.addStatement("java.lang.foreign.GroupLayout totalLayout = java.lang.foreign.MemoryLayout.structLayout(elements.toArray(new java.lang.foreign.MemoryLayout[0]))");
        constr.addStatement("this.totalSegment = this.arena.allocate(totalLayout)");
        for (FieldModel f : model.fields()) if (!f.isStatic()) constr.addStatement("this.$L_Segment = this.totalSegment.asSlice(totalLayout.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement($S)))", f.name(), f.name());

        classBuilder.addStaticBlock(staticInit.build()).addMethod(constr.build());
        implementCommonSoAMethods(classBuilder, model, interfaceType);
        implementFieldMethods(classBuilder, model, true, interfaceType);

        for (ExecutableElement nc : model.nativeCalls()) {
            MethodSpec.Builder mb = MethodSpec.overriding(nc);
            mb.addStatement("throw new UnsupportedOperationException(\"NativeCall not supported in SoA mode\")");
            classBuilder.addMethod(mb.build());
        }

        return classBuilder.build();
    }

    private String getVectorType(TypeKind kind) {
        return switch(kind) { case DOUBLE -> "DoubleVector"; case LONG -> "LongVector"; case INT -> "IntVector"; case FLOAT -> "FloatVector"; default -> "ByteVector"; };
    }

    private void implementCommonAoSMethods(TypeSpec.Builder classBuilder, StructModel model, ClassName interfaceType) {
        classBuilder.addMethod(MethodSpec.methodBuilder("address").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(long.class).addStatement("return _address").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("segment").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(MemorySegment.class).addStatement("return segment").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("getPool").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(MemoryPool.class).addStatement("return pool").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("rebase").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addParameter(MemorySegment.class, "s").addStatement("this.segment = s").addStatement("this._address = s != null ? s.address() : 0L").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("rebase").addModifiers(Modifier.PUBLIC).addParameter(long.class, "addr").addStatement("this._address = addr").addStatement("this.segment = null").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("free").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addStatement("com.github.goguma9071.jvmplus.memory.MemoryManager.free(this)").build());
        
        MethodSpec.Builder asPtr = MethodSpec.methodBuilder("asPointer").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addTypeVariable(TypeVariableName.get("T", Struct.class))
                .returns(ParameterizedTypeName.get(ClassName.get(Pointer.class), TypeVariableName.get("T")));
        asPtr.addStatement("final long addr = this.address()").addStatement("final Class<T> type = (Class<T>) $T.class", interfaceType);
        asPtr.beginControlFlow("return new Pointer<T>()")
                .addCode("@Override public T deref() { return com.github.goguma9071.jvmplus.memory.MemoryManager.createAddressPointer(addr, type).deref(); }\n")
                .addCode("@Override public void set(T v) { throw new UnsupportedOperationException(); }\n")
                .addCode("@Override public long address() { return addr; }\n")
                .addCode("@Override public <U> Pointer<U> cast(Class<U> t) { return com.github.goguma9071.jvmplus.memory.MemoryManager.createAddressPointer(addr, t); }\n")
                .addCode("@Override public long distanceTo(Pointer<T> other) { return (this.address() - other.address()) / LAYOUT.byteSize(); }\n")
                .addCode("@Override public Pointer<T> offset(long c) { return com.github.goguma9071.jvmplus.memory.MemoryManager.createAddressPointer(addr + c * LAYOUT.byteSize(), type); }\n")
                .addCode("@Override public Class<T> targetType() { return type; }\n")
                .addCode("@Override public Pointer<T> auto() { return this; }\n")
                .addCode("@Override public Object invoke(java.lang.foreign.FunctionDescriptor d, Object... a) { return com.github.goguma9071.jvmplus.memory.MemoryManager.invoke(address(), d, a); }\n")
                .addCode("@Override public void free() { }\n")
                .endControlFlow().addCode(";\n");
        classBuilder.addMethod(asPtr.build());
        
        MethodSpec.Builder ts = MethodSpec.methodBuilder("toString").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(String.class).addStatement("$T sb = new $T()", StringBuilder.class, StringBuilder.class).addStatement("sb.append($S).append(\" {\")", model.interfaceName());
        for (FieldModel f : model.fields()) if (!f.isStatic()) ts.addStatement("sb.append(\"\\n  \").append($S).append(\": \").append($L())", f.name(), f.name());
        ts.addStatement("sb.append(\"\\n}\")").addStatement("return sb.toString()");
        classBuilder.addMethod(ts.build());
    }

    private void implementCommonSoAMethods(TypeSpec.Builder classBuilder, StructModel model, ClassName interfaceType) {
        classBuilder.addMethod(MethodSpec.methodBuilder("address").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(long.class).addStatement("return 0").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("segment").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(MemorySegment.class).addStatement("return null").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("getPool").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(MemoryPool.class).addStatement("return null").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("rebase").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addParameter(MemorySegment.class, "s").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("free").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addStatement("arena.close()").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("close").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addStatement("this.free()").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("get").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addParameter(int.class, "index").returns(interfaceType).addStatement("this.currentIndex = index").addStatement("return this").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("size").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(int.class).addStatement("return capacity").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("iterator").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(ParameterizedTypeName.get(ClassName.get(java.util.Iterator.class), interfaceType))
                .beginControlFlow("return new java.util.Iterator<>()").addStatement("private int current = 0")
                .addCode("@Override public boolean hasNext() { return current < capacity; }\n")
                .addCode("@Override public $T next() { return get(current++); }\n", interfaceType)
                .endControlFlow().addCode(";\n").build());

        classBuilder.addMethod(MethodSpec.methodBuilder("asPointer").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addTypeVariable(TypeVariableName.get("T", Struct.class))
                .returns(ParameterizedTypeName.get(ClassName.get(Pointer.class), TypeVariableName.get("T"))).addStatement("throw new UnsupportedOperationException()").build());

        String[] types = {"Double", "Long"};
        for(String t : types) {
            MethodSpec.Builder m = MethodSpec.methodBuilder("sum" + t).addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addParameter(String.class, "f").returns(t.equals("Double") ? double.class : long.class).beginControlFlow("switch(f)");
            for (FieldModel f : model.fields()) if (f.type().toString().toLowerCase().contains(t.toLowerCase()) && !f.isArray() && !f.isStatic()) {
                String cap = f.name().substring(0,1).toUpperCase()+f.name().substring(1);
                m.addStatement("case $S: return sum$L()", f.name(), cap);
            }
            m.addStatement("default: throw new UnsupportedOperationException()").endControlFlow();
            classBuilder.addMethod(m.build());
        }

        MethodSpec.Builder fill = MethodSpec.methodBuilder("fill").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addParameter(String.class, "f").addParameter(long.class, "v").beginControlFlow("switch(f)");
        for (FieldModel f : model.fields()) if ((f.type().getKind().isPrimitive()) && !f.isArray() && !f.isStatic()) {
            String cap = f.name().substring(0,1).toUpperCase()+f.name().substring(1);
            fill.addStatement("case $S: fill$L(($T)v); break", f.name(), cap, TypeName.get(f.type()));
        }
        fill.addStatement("default: throw new UnsupportedOperationException()").endControlFlow();
        classBuilder.addMethod(fill.build());
    }

    private void implementFieldMethods(TypeSpec.Builder classBuilder, StructModel model, boolean isSoA, ClassName interfaceType) {
        String aosName = model.implBaseName() + "Impl";
        for (FieldModel f : model.fields()) {
            String backing = f.isBitField() ? f.bitFieldBackingName() : f.name();
            String off = (isSoA && !f.isStatic()) ? "(long)currentIndex" : aosName + "." + f.name().toUpperCase() + "_OFFSET";
            String vhName = (isSoA && !f.isStatic() && !f.isBitField() && f.type().getKind().isPrimitive() && !f.isArray() && !f.isPointer()) ? (f.name().toUpperCase() + "_VH") : aosName + "." + backing.toUpperCase() + "_VH";

            MethodSpec.Builder get = MethodSpec.overriding(f.getter());
            generateGetterBody(get, f, off, vhName, isSoA, aosName);
            classBuilder.addMethod(get.build());

            if (!isSoA && !f.isStatic()) {
                MethodSpec.Builder g = MethodSpec.methodBuilder("get_" + f.name()).addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(TypeName.get(f.type())).addParameter(long.class, "addr");
                if (f.isArray()) g.addParameter(int.class, "idx");
                generateGetterBody(g, f, aosName + "." + f.name().toUpperCase() + "_OFFSET", vhName, false, aosName);
                classBuilder.addMethod(g.build());
            }

            if (f.setter() != null) {
                MethodSpec.Builder set = MethodSpec.overriding(f.setter());
                String p = f.setter().getParameters().get(f.isArray() ? 1 : 0).getSimpleName().toString();
                generateSetterBody(set, f, off, vhName, isSoA, aosName, p);
                classBuilder.addMethod(set.addStatement("return this").build());

                if (!isSoA && !f.isStatic()) {
                    MethodSpec.Builder s = MethodSpec.methodBuilder("set_" + f.name()).addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter(long.class, "addr");
                    if (f.isArray()) s.addParameter(int.class, "idx");
                    s.addParameter(TypeName.get(f.type()), "v");
                    generateSetterBody(s, f, aosName + "." + f.name().toUpperCase() + "_OFFSET", vhName, false, aosName, "v");
                    classBuilder.addMethod(s.build());
                }
            }

            if (f.isAtomic()) {
                String cap = f.name().substring(0, 1).toUpperCase() + f.name().substring(1);
                String ah = aosName + "." + f.name().toUpperCase() + "_VH";
                if (isSoA) {
                    classBuilder.addMethod(MethodSpec.methodBuilder("cas" + cap).addModifiers(Modifier.PUBLIC).returns(interfaceType).addParameter(TypeName.get(f.type()), "e").addParameter(TypeName.get(f.type()), "v").addStatement("$L.compareAndSet(this.$L_Segment, 0L, (long)currentIndex, e, v)", ah, backing).addStatement("return this").build());
                    if (f.type().getKind() == TypeKind.INT || f.type().getKind() == TypeKind.LONG) classBuilder.addMethod(MethodSpec.methodBuilder("addAndGet" + cap).addModifiers(Modifier.PUBLIC).returns(interfaceType).addParameter(TypeName.get(f.type()), "d").addStatement("$L.getAndAdd(this.$L_Segment, 0L, (long)currentIndex, d)", ah, backing).addStatement("return this").build());
                } else {
                    String base = f.isStatic() ? "STATIC_SEGMENT" : "EVERYTHING";
                    String ao = f.isStatic() ? (aosName + "." + f.name().toUpperCase() + "_OFFSET") : "this._address + " + (aosName + "." + f.name().toUpperCase() + "_OFFSET");
                    classBuilder.addMethod(MethodSpec.methodBuilder("cas" + cap).addModifiers(Modifier.PUBLIC).returns(interfaceType).addParameter(TypeName.get(f.type()), "e").addParameter(TypeName.get(f.type()), "v").addStatement("$L.compareAndSet($L, $L, e, v)", ah, base, ao).addStatement("return this").build());
                    if (f.type().getKind() == TypeKind.INT || f.type().getKind() == TypeKind.LONG) classBuilder.addMethod(MethodSpec.methodBuilder("addAndGet" + cap).addModifiers(Modifier.PUBLIC).returns(interfaceType).addParameter(TypeName.get(f.type()), "d").addStatement("$L.getAndAdd($L, $L, d)", ah, base, ao).addStatement("return this").build());
                }
            }

            if (isSoA && !f.isStatic() && f.type().getKind().isPrimitive() && !f.isArray() && !f.isPointer() && !f.isStruct() && !f.isString() && !f.isEnum()) {
                classBuilder.addMethod(generateSimd(f.name(), f.type().getKind(), true));
                classBuilder.addMethod(generateSimd(f.name(), f.type().getKind(), false));
            }
        }
    }

    private void generateGetterBody(MethodSpec.Builder mb, FieldModel f, String off, String vh, boolean isSoA, String aos) {
        String backing = f.isBitField() ? f.bitFieldBackingName() : f.name();
        if (f.isString()) {
             String base = isSoA ? "this."+backing+"_Segment.address() + (long)currentIndex * " + f.length() : "this._address + " + off;
             if (mb.parameters.size() > 0 && mb.parameters.get(0).name.equals("addr")) base = "addr + " + off;
             mb.addStatement("byte[] b = EVERYTHING.asSlice($L, $L).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)", base, f.length()).addStatement("int l=0; while(l<b.length && b[l]!=0) l++; return new String(b, 0, l, java.nio.charset.StandardCharsets.UTF_8)");
        } else if (f.isRaw()) {
             String base = isSoA ? "this."+backing+"_Segment.address() + (long)currentIndex * " + f.length() : "this._address + " + off;
             if (mb.parameters.size() > 0 && mb.parameters.get(0).name.equals("addr")) base = "addr + " + off;
             mb.addStatement("java.lang.foreign.MemorySegment s = EVERYTHING.asSlice($L, $L)", base, f.length());
             mb.beginControlFlow("return new com.github.goguma9071.jvmplus.memory.RawBuffer()")
                 .addCode("@Override public java.lang.foreign.MemorySegment segment() { return s; }\n")
                 .addCode("@Override public void free() { }\n")
                 .endControlFlow().addCode(";\n");
        } else if (f.isBitField()) {
            String target = isSoA ? "this."+backing+"_Segment.asSlice((long)currentIndex * 8L, 8L)" : "EVERYTHING.asSlice(this._address + " + off + ", 8L)";
            if (mb.parameters.size() > 0 && mb.parameters.get(0).name.equals("addr")) target = "EVERYTHING.asSlice(addr + " + off + ", 8L)";
            mb.addStatement("long v = ((Number) $L.get($L, 0L)).longValue()", vh, target);
            long m = (f.bitCount() == 64) ? -1L : (1L << f.bitCount()) - 1;
            mb.addStatement("return ($T) ((v >>> $L) & $LL)", f.type(), f.bitOffset(), m);
        } else if (f.isStruct()) {
            String sn = f.nestedImplName().contains(".") ? f.nestedImplName().substring(f.nestedImplName().lastIndexOf('.') + 1) : f.nestedImplName();
            if (isSoA && !f.isStatic()) mb.addStatement("this.$L_flyweight.rebase(this.$L_Segment.asSlice((long)currentIndex * $L.LAYOUT.byteSize(), $L.LAYOUT.byteSize())); return this.$L_flyweight", f.name(), f.name(), sn, sn, f.name());
            else {
                String base = "this._address + " + off;
                if (mb.parameters.size() > 0 && mb.parameters.get(0).name.equals("addr")) mb.addStatement("return new $L(EVERYTHING.asSlice(addr + $L, $L.LAYOUT.byteSize()), null)", sn, off, sn);
                else { mb.addStatement("this.$L_flyweight.rebase($L)", f.name(), base); mb.addStatement("return this.$L_flyweight", f.name()); }
            }
        } else if (f.isPointer() || f.type().toString().startsWith("com.github.goguma9071.jvmplus.memory.Pointer")) {
            String seg = isSoA ? "this."+backing+"_Segment" : "EVERYTHING";
            String p_off = isSoA ? "0L" : ("this._address + " + off);
            String p_idx = isSoA ? "(long)currentIndex" : "";
            if (mb.parameters.size() > 0 && mb.parameters.get(0).name.equals("addr")) { seg = "EVERYTHING"; p_off = "addr + " + off; p_idx = ""; }
            
            mb.addStatement("final java.lang.foreign.MemorySegment _ptrSeg = $L", seg);
            mb.addStatement("final long _ba = $L", p_off);
            if (isSoA && !seg.equals("EVERYTHING")) mb.addStatement("long _ta = (long) $L.get(_ptrSeg, _ba, $L)", vh, p_idx);
            else mb.addStatement("long _ta = (long) $L.get(_ptrSeg, _ba)", vh);

            String sn = f.nestedImplName().contains(".") ? f.nestedImplName().substring(f.nestedImplName().lastIndexOf('.') + 1) : f.nestedImplName();
            mb.beginControlFlow("return new Pointer<$T>()", TypeName.get(((DeclaredType) f.type()).getTypeArguments().get(0)))
                .addCode("@Override public $T deref() { $T o = com.github.goguma9071.jvmplus.memory.MemoryManager.createEmptyStruct($T.class); o.rebase(java.lang.foreign.MemorySegment.ofAddress(_ta).reinterpret($L.LAYOUT.byteSize(), java.lang.foreign.Arena.global(), null)); return o; }\n", TypeName.get(((DeclaredType) f.type()).getTypeArguments().get(0)), TypeName.get(((DeclaredType) f.type()).getTypeArguments().get(0)), TypeName.get(((DeclaredType) f.type()).getTypeArguments().get(0)), sn);

            if (isSoA && !seg.equals("EVERYTHING")) {
                mb.addCode("@Override public void set($T v) { $L.set(_ptrSeg, _ba, $L, v.address()); }\n", TypeName.get(((DeclaredType) f.type()).getTypeArguments().get(0)), vh, p_idx);
            } else {
                mb.addCode("@Override public void set($T v) { $L.set(_ptrSeg, _ba, v.address()); }\n", TypeName.get(((DeclaredType) f.type()).getTypeArguments().get(0)), vh);
            }

            mb.addCode("@Override public long address() { return _ta; }\n")
                .addCode("@Override public <U> Pointer<U> cast(Class<U> t) { return com.github.goguma9071.jvmplus.memory.MemoryManager.createAddressPointer(_ta, t); }\n")
                .addCode("@Override public long distanceTo(Pointer other) { return (this.address() - other.address()) / $L.LAYOUT.byteSize(); }\n", sn)
                .addCode("@Override public Pointer offset(long c) { throw new UnsupportedOperationException(); }\n")
                .addCode("@Override public Class targetType() { return null; }\n")
                .addCode("@Override public Pointer auto() { return this; }\n")
                .addCode("@Override public Object invoke(java.lang.foreign.FunctionDescriptor d, Object... a) { return com.github.goguma9071.jvmplus.memory.MemoryManager.invoke(address(), d, a); }\n")
                .addCode("@Override public void free() { }\n").endControlFlow().addCode(";\n");
        } else if (f.isArray()) {
            mb.addStatement("if (idx < 0 || idx >= $L) throw new IndexOutOfBoundsException()", f.length());
            String seg = isSoA ? "this."+backing+"_Segment" : "EVERYTHING";
            if (isSoA) mb.addStatement("return ($T) $L.get($L, 0L, (long)currentIndex * $L + idx)", f.type(), vh, seg, f.length());
            else {
                String aoff = "this._address + " + off + " + (long)idx * " + (f.size() / f.length());
                if (mb.parameters.size() > 0 && mb.parameters.get(0).name.equals("addr")) aoff = "addr + " + off + " + (long)idx * " + (f.size() / f.length());
                mb.addStatement("return ($T) $L.get($L, $L)", f.type(), vh, seg, aoff);
            }
        } else if (f.type().toString().equals("java.lang.Object")) {
            String seg = isSoA ? "this."+backing+"_Segment" : "EVERYTHING";
            if (isSoA) mb.addStatement("return com.github.goguma9071.jvmplus.memory.MemoryManager.getHandle((long)$L.get($L, 0L, (long)currentIndex))", vh, seg);
            else {
                String aoff = "this._address + " + off;
                if (mb.parameters.size() > 0 && mb.parameters.get(0).name.equals("addr")) aoff = "addr + " + off;
                mb.addStatement("return com.github.goguma9071.jvmplus.memory.MemoryManager.getHandle((long)$L.get($L, $L))", vh, seg, aoff);
            }
        } else {
            String seg = isSoA ? "this."+backing+"_Segment" : "EVERYTHING";
            if (isSoA) mb.addStatement("return ($T) $L.get($L, 0L, (long)currentIndex)", f.type(), vh, seg);
            else {
                String aoff = "this._address + " + off;
                if (mb.parameters.size() > 0 && mb.parameters.get(0).name.equals("addr")) aoff = "addr + " + off;
                mb.addStatement("return ($T) $L.get($L, $L)", f.type(), vh, seg, aoff);
            }
        }
    }

    private void generateSetterBody(MethodSpec.Builder mb, FieldModel f, String off, String vh, boolean isSoA, String aos, String p) {
        String backing = f.isBitField() ? f.bitFieldBackingName() : f.name();
        if (f.isString()) {
             String base = isSoA ? "this."+backing+"_Segment.address() + (long)currentIndex * " + f.length() : "this._address + " + off;
             if (mb.parameters.size() > 0 && mb.parameters.get(0).name.equals("addr")) base = "addr + " + off;
             mb.addStatement("byte[] b = $L.getBytes(java.nio.charset.StandardCharsets.UTF_8)", p).addStatement("int l = Math.min(b.length, $L)", f.length()).addStatement("java.lang.foreign.MemorySegment.copy(java.lang.foreign.MemorySegment.ofArray(b), 0, EVERYTHING, $L, l)", base).addStatement("if(l < $L) EVERYTHING.asSlice($L + l, $L - l).fill((byte)0)", f.length(), base, f.length());
        } else if (f.isRaw()) {
             String base = isSoA ? "this."+backing+"_Segment.address() + (long)currentIndex * " + f.length() : "this._address + " + off;
             if (mb.parameters.size() > 0 && mb.parameters.get(0).name.equals("addr")) base = "addr + " + off;
             mb.beginControlFlow("if ($L != null)", p)
                 .addStatement("java.lang.foreign.MemorySegment.copy($L.segment(), 0, EVERYTHING, $L, $L)", p, base, f.length())
                 .endControlFlow();
        } else if (f.isBitField()) {
            String target = isSoA ? "this."+backing+"_Segment.asSlice((long)currentIndex * 8L, 8L)" : "EVERYTHING.asSlice(this._address + " + off + ", 8L)";
            if (mb.parameters.size() > 0 && mb.parameters.get(0).name.equals("addr")) target = "EVERYTHING.asSlice(addr + " + off + ", 8L)";
            mb.addStatement("long old = ((Number) $L.get($L, 0L)).longValue()", vh, target);
            long m = (f.bitCount() == 64) ? -1L : (1L << f.bitCount()) - 1;
            mb.addStatement("long up = (old & ~($LL << $L)) | (($L & $LL) << $L)", m, f.bitOffset(), p, m, f.bitOffset()).addStatement("$L.set($L, 0L, $Lup)", vh, target, f.size() == 4 ? "(int)" : "");
        } else if (f.type().toString().equals("java.lang.Object")) {
            String seg = isSoA ? "this."+backing+"_Segment" : "EVERYTHING";
            if (isSoA) mb.addStatement("$L.set($L, 0L, (long)currentIndex, com.github.goguma9071.jvmplus.memory.MemoryManager.registerHandle($L))", vh, seg, p);
            else {
                String aoff = "this._address + " + off;
                if (mb.parameters.size() > 0 && mb.parameters.get(0).name.equals("addr")) aoff = "addr + " + off;
                mb.addStatement("$L.set($L, $L, com.github.goguma9071.jvmplus.memory.MemoryManager.registerHandle($L))", vh, seg, aoff, p);
            }
        } else if (f.isPointer() || f.type().toString().startsWith("com.github.goguma9071.jvmplus.memory.Pointer")) {
            String seg = isSoA ? "this."+backing+"_Segment" : "EVERYTHING";
            if (isSoA) mb.addStatement("$L.set($L, 0L, (long)currentIndex, $L.address())", vh, seg, p);
            else {
                String aoff = "this._address + " + off;
                if (mb.parameters.size() > 0 && mb.parameters.get(0).name.equals("addr")) aoff = "addr + " + off;
                mb.addStatement("$L.set($L, $L, $L.address())", vh, seg, aoff, p);
            }
        } else {
            String seg = isSoA ? "this."+backing+"_Segment" : "EVERYTHING";
            if (isSoA) mb.addStatement("$L.set($L, 0L, (long)currentIndex, $L)", vh, seg, p);
            else {
                String aoff = "this._address + " + off;
                if (mb.parameters.size() > 0 && mb.parameters.get(0).name.equals("addr")) aoff = "addr + " + off;
                mb.addStatement("$L.set($L, $L, $L)", vh, seg, aoff, p);
            }
        }
    }

    private MethodSpec generateSimd(String fn, TypeKind k, boolean isSum) {
        String cap = fn.substring(0, 1).toUpperCase() + fn.substring(1);
        String sp = fn.toUpperCase() + "_SPECIES";
        String vc = switch(k) { case DOUBLE -> "DoubleVector"; case FLOAT -> "FloatVector"; case LONG -> "LongVector"; case INT -> "IntVector"; default -> "ByteVector"; };
        Class<?> pc = switch(k) { case DOUBLE -> double.class; case FLOAT -> float.class; case LONG -> long.class; case INT -> int.class; default -> byte.class; };
        boolean canSimd = (k == TypeKind.INT || k == TypeKind.LONG || k == TypeKind.FLOAT || k == TypeKind.DOUBLE);
        if (isSum) {
            MethodSpec.Builder mb = MethodSpec.methodBuilder("sum" + cap).addModifiers(Modifier.PUBLIC).returns(pc);
            if (canSimd) mb.addStatement("var acc = jdk.incubator.vector.$L.zero($L)", vc, sp).addStatement("int i = 0; int ub = $L.loopBound(capacity)", sp).beginControlFlow("for (; i < ub; i += $L.length())", sp).addStatement("var v = jdk.incubator.vector.$L.fromMemorySegment($L, this.$L_Segment, (long)i * $L, java.nio.ByteOrder.nativeOrder())", vc, sp, fn, (k == TypeKind.DOUBLE || k == TypeKind.LONG) ? 8 : 4).addStatement("acc = acc.add(v)").endControlFlow().addStatement("$T tot = ($T) acc.reduceLanes(jdk.incubator.vector.VectorOperators.ADD)", pc, pc).beginControlFlow("for (; i < capacity; i++)").addStatement("tot += this.$L_Segment.getAtIndex(java.lang.foreign.ValueLayout.JAVA_$L, (long)i)", fn, k.name()).endControlFlow().addStatement("return tot");
            else mb.addStatement("$T tot = 0; for (int i = 0; i < capacity; i++) tot += this.$L_Segment.getAtIndex(java.lang.foreign.ValueLayout.JAVA_$L, (long)i); return tot", pc, fn, k.name());
            return mb.build();
        } else {
            MethodSpec.Builder mb = MethodSpec.methodBuilder("fill" + cap).addModifiers(Modifier.PUBLIC).addParameter(pc, "v");
            if (canSimd) mb.addStatement("var vec = jdk.incubator.vector.$L.broadcast($L, v)", vc, sp).addStatement("int i = 0; int ub = $L.loopBound(capacity)", sp).beginControlFlow("for (; i < ub; i += $L.length())", sp).addStatement("vec.intoMemorySegment(this.$L_Segment, (long)i * $L, java.nio.ByteOrder.nativeOrder())", fn, (k == TypeKind.DOUBLE || k == TypeKind.LONG) ? 8 : 4).endControlFlow().beginControlFlow("for (; i < capacity; i++)").addStatement("this.$L_Segment.setAtIndex(java.lang.foreign.ValueLayout.JAVA_$L, (long)i, v)", fn, k.name()).endControlFlow();
            else mb.addStatement("for (int i = 0; i < capacity; i++) this.$L_Segment.setAtIndex(java.lang.foreign.ValueLayout.JAVA_$L, (long)i, v)", fn, k.name());
            return mb.build();
        }
    }

    private String getLayoutCode(FieldModel f) {
        if (f.isString() || f.isRaw()) return "java.lang.foreign.MemoryLayout.sequenceLayout(" + f.length() + ", java.lang.foreign.ValueLayout.JAVA_BYTE)";
        if (f.isArray()) return "java.lang.foreign.MemoryLayout.sequenceLayout(" + f.length() + ", " + getSimpleLayoutCode(f.type(), f.alignment()) + ")";
        if (f.isStruct()) { String n = f.nestedImplName(); return (n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : n) + ".LAYOUT"; }
        return getSimpleLayoutCode(f.type(), f.alignment());
    }

    private String getSimpleLayoutCode(TypeMirror t, long a) {
        String b = switch (t.getKind()) { case INT -> "java.lang.foreign.ValueLayout.JAVA_INT"; case LONG -> "java.lang.foreign.ValueLayout.JAVA_LONG"; case DOUBLE -> "java.lang.foreign.ValueLayout.JAVA_DOUBLE"; case FLOAT -> "java.lang.foreign.ValueLayout.JAVA_FLOAT"; case BYTE -> "java.lang.foreign.ValueLayout.JAVA_BYTE"; default -> "java.lang.foreign.ValueLayout.ADDRESS"; };
        return a > 0 && (a & (a - 1)) == 0 ? b + ".withByteAlignment(" + a + ")" : b;
    }

    private String getDescriptorCode(ExecutableElement nc) {
        String r = nc.getReturnType().getKind() == TypeKind.VOID ? "ofVoid" : "of";
        String rl = (nc.getReturnType().getKind() == TypeKind.VOID) ? "" : getLayoutForType(nc.getReturnType());
        String as = nc.getParameters().stream().map(p -> getLayoutForType(p.asType())).collect(Collectors.joining(", "));
        return "java.lang.foreign.FunctionDescriptor." + r + "(" + (rl.isEmpty() ? "" : rl + (as.isEmpty() ? "" : ", ")) + as + ")";
    }

    private String getLayoutForType(TypeMirror t) {
        return switch (t.getKind()) { case INT -> "java.lang.foreign.ValueLayout.JAVA_INT"; case LONG -> "java.lang.foreign.ValueLayout.JAVA_LONG"; case DOUBLE -> "java.lang.foreign.ValueLayout.JAVA_DOUBLE"; case FLOAT -> "java.lang.foreign.ValueLayout.JAVA_FLOAT"; default -> "java.lang.foreign.ValueLayout.ADDRESS"; };
    }

    private long getAlignment(FieldModel f) { return f.alignment() > 0 ? f.alignment() : 1; }
}
