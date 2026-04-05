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
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.stream.Collectors;

public class JPCGenerator {
    private final Filer filer;

    public JPCGenerator(Filer filer, javax.lang.model.util.Types types) {
        this.filer = filer;
    }

    public void generate(StructModel model) throws IOException {
        generateAoS(model);
        generateSoA(model);
    }

    private ClassName getInterfaceType(StructModel model) {
        return ClassName.get(model.packageName(), model.interfaceName());
    }

    private void generateAoS(StructModel model) throws IOException {
        String implName = model.implBaseName() + "Impl";
        ClassName interfaceType = getInterfaceType(model);

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(implName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(interfaceType);

        classBuilder.addField(MemorySegment.class, "segment", Modifier.PRIVATE);
        classBuilder.addField(MemoryPool.class, "pool", Modifier.PRIVATE, Modifier.FINAL);

        MethodSpec.Builder constr = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(MemorySegment.class, "segment")
                .addParameter(MemoryPool.class, "pool")
                .addStatement("this.segment = segment")
                .addStatement("this.pool = pool");

        CodeBlock.Builder staticInit = CodeBlock.builder();
        staticInit.addStatement("java.util.List<java.lang.foreign.MemoryLayout> elements = new java.util.ArrayList<>()");
        StringBuilder mapBuilder = new StringBuilder();
        mapBuilder.append("[Struct Layout: ").append(interfaceType.canonicalName()).append("]\\n\\n");

        long lastEnd = 0;
        for (FieldModel f : model.fields()) {
            if (f.isStatic()) continue;

            String offsetName = f.name().toUpperCase() + "_OFFSET";
            classBuilder.addField(long.class, offsetName, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);

            if (!f.isBitField() || f.name().equals(f.bitFieldBackingName())) {
                long currentOffset = f.calculatedOffset();
                if (currentOffset > lastEnd) {
                    staticInit.addStatement("elements.add(java.lang.foreign.MemoryLayout.paddingLayout($L))", currentOffset - lastEnd);
                    mapBuilder.append(String.format("Offset %-4d: [padding]     (%d bytes)\\n", lastEnd, currentOffset - lastEnd));
                }
                staticInit.addStatement("elements.add($L.withName($S))", getLayoutCode(f, false), f.isBitField() ? f.bitFieldBackingName() : f.name());
                lastEnd = currentOffset + f.size();
            }
            staticInit.addStatement("$L = $L", offsetName, f.calculatedOffset());

            String displayType = getTypeName(f);
            if (f.isBitField()) displayType += " :" + f.bitCount();

            mapBuilder.append(String.format("Offset %-4d: %-13s %-10s (%d bytes)%s\\n",
                    f.calculatedOffset(), displayType, f.name(), f.size(), f.isAtomic() ? " [@Atomic]" : ""));

            if (!f.isBitField() || f.name().equals(f.bitFieldBackingName())) {
                if (f.isAtomic() || (!f.isString() && !f.isRaw() && !f.isArray() && !f.isEnum() && !f.isPointer() && !f.isStruct())) {
                    String handleName = (f.isBitField() ? f.bitFieldBackingName() : f.name()).toUpperCase() + "_HANDLE";
                    if (classBuilder.fieldSpecs.stream().noneMatch(fs -> fs.name.equals(handleName))) {
                        classBuilder.addField(VarHandle.class, handleName, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
                        if (f.isAtomic()) {
                            staticInit.addStatement("$L = java.lang.foreign.ValueLayout.JAVA_$L.withByteAlignment($L).varHandle()", handleName, f.type().getKind().name(), getAlignment(f));
                        } else {
                            staticInit.addStatement("$L = java.lang.foreign.MemoryLayout.structLayout(elements.toArray(new java.lang.foreign.MemoryLayout[0])).varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement($S))", handleName, f.isBitField() ? f.bitFieldBackingName() : f.name());
                        }
                    }
                }
            }
        }

        // Native Call Handles
        for (ExecutableElement nc : model.nativeCalls()) {
            String funcName = "";
            String libName = "";
            for (javax.lang.model.element.AnnotationMirror am : nc.getAnnotationMirrors()) {
                if (am.getAnnotationType().toString().contains("NativeCall")) {
                    for (java.util.Map.Entry<? extends ExecutableElement, ? extends javax.lang.model.element.AnnotationValue> entry : am.getElementValues().entrySet()) {
                        String key = entry.getKey().getSimpleName().toString();
                        if (key.equals("name")) funcName = (String) entry.getValue().getValue();
                        if (key.equals("lib")) libName = (String) entry.getValue().getValue();
                    }
                }
            }
            if (funcName.isEmpty()) funcName = nc.getSimpleName().toString();
            String handleName = "NC_" + nc.getSimpleName().toString().toUpperCase() + "_HANDLE";
            classBuilder.addField(java.lang.invoke.MethodHandle.class, handleName, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
            if (libName != null && !libName.isEmpty()) {
                staticInit.addStatement("$L = java.lang.foreign.Linker.nativeLinker().downcallHandle(java.lang.foreign.Linker.nativeLinker().defaultLookup().find($S).orElse(java.lang.foreign.SymbolLookup.libraryLookup($S, java.lang.foreign.Arena.global()).find($S).get()), $L)",
                        handleName, funcName, libName, funcName, getDescriptorCode(nc));
            } else {
                staticInit.addStatement("$L = java.lang.foreign.Linker.nativeLinker().downcallHandle(java.lang.foreign.Linker.nativeLinker().defaultLookup().find($S).get(), $L)",
                        handleName, funcName, getDescriptorCode(nc));
            }
        }

        classBuilder.addField(GroupLayout.class, "LAYOUT", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
        classBuilder.addField(MemorySegment.class, "STATIC_SEGMENT", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
        staticInit.addStatement("LAYOUT = java.lang.foreign.MemoryLayout.structLayout(elements.toArray(new java.lang.foreign.MemoryLayout[0]))");
        staticInit.addStatement("STATIC_SEGMENT = java.lang.foreign.Arena.global().allocate(LAYOUT)");
        staticInit.addStatement("System.out.println($S + LAYOUT.byteSize() + $S)", mapBuilder.toString() + "\nTotal Size: ", " bytes\n");

        classBuilder.addStaticBlock(staticInit.build()).addMethod(constr.build());
        implementCommonAoSMethods(classBuilder, model, interfaceType);
        implementFieldMethods(classBuilder, model, false, interfaceType);

        JavaFile.builder(model.packageName(), classBuilder.build()).build().writeTo(filer);
    }

    private void generateSoA(StructModel model) throws IOException {
        ClassName interfaceType = getInterfaceType(model);
        String soaName = model.implBaseName() + "SoAImpl";
        String aosName = model.implBaseName() + "Impl";
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(soaName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(ClassName.get("com.github.goguma9071.jvmplus.memory", "StructArray"), interfaceType))
                .addSuperinterface(interfaceType);

        classBuilder.addField(int.class, "currentIndex", Modifier.PRIVATE)
                .addField(int.class, "capacity", Modifier.PRIVATE, Modifier.FINAL)
                .addField(java.lang.foreign.Arena.class, "arena", Modifier.PRIVATE, Modifier.FINAL)
                .addField(MemorySegment.class, "totalSegment", Modifier.PRIVATE, Modifier.FINAL);

        MethodSpec.Builder constr = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(int.class, "capacity")
                .addStatement("this.capacity = capacity")
                .addStatement("this.arena = java.lang.foreign.Arena.ofShared()");

        CodeBlock.Builder staticInit = CodeBlock.builder();
        classBuilder.addField(MemorySegment.class, "STATIC_SEGMENT", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
        staticInit.addStatement("STATIC_SEGMENT = $T.STATIC_SEGMENT", ClassName.get(model.packageName(), aosName));

        constr.addStatement("java.util.List<java.lang.foreign.MemoryLayout> elements = new java.util.ArrayList<>()");
        constr.addStatement("long currentOffset = 0");
        constr.addStatement("java.lang.foreign.MemoryLayout sl");

        for (FieldModel f : model.fields()) {
            if (f.isStatic()) continue;
            if (f.isBitField() && !f.name().equals(f.bitFieldBackingName())) continue;

            String segName = f.name() + "_Segment";
            classBuilder.addField(MemorySegment.class, segName, Modifier.PUBLIC, Modifier.FINAL);
            String layoutCode = getLayoutCode(f, true);

            constr.beginControlFlow("if (currentOffset % 64 != 0)")
                    .addStatement("long p = 64 - (currentOffset % 64)")
                    .addStatement("elements.add(java.lang.foreign.MemoryLayout.paddingLayout(p))")
                    .addStatement("currentOffset += p")
                    .endControlFlow();

            if (f.type().getKind().isPrimitive() && !f.isArray() && !f.isPointer()) {
                classBuilder.addField(VarHandle.class, f.name().toUpperCase() + "_HANDLE", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
                staticInit.addStatement("$L_HANDLE = $L.arrayElementVarHandle()", f.name().toUpperCase(), layoutCode);

                String speciesName = f.name().toUpperCase() + "_SPECIES";
                classBuilder.addField(ClassName.get("jdk.incubator.vector", "VectorSpecies"), speciesName, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
                staticInit.addStatement("$L = jdk.incubator.vector.$L.SPECIES_PREFERRED", speciesName, getVectorType(f.type().getKind()));

                constr.addStatement("sl = java.lang.foreign.MemoryLayout.sequenceLayout((long)capacity, $L).withName($S)", layoutCode, f.name());
                constr.addStatement("elements.add(sl)");
                constr.addStatement("currentOffset += sl.byteSize()");

                if (f.type().getKind() == TypeKind.DOUBLE) classBuilder.addMethod(generateSoASimdSum(f.name()));
            } else if (f.isString()) {
                constr.addStatement("sl = java.lang.foreign.MemoryLayout.sequenceLayout((long)capacity, java.lang.foreign.MemoryLayout.sequenceLayout((long)$L, java.lang.foreign.ValueLayout.JAVA_BYTE)).withName($S)", f.length(), f.name());
                constr.addStatement("elements.add(sl)");
                constr.addStatement("currentOffset += sl.byteSize()");
            } else if (f.isArray()) {
                constr.addStatement("sl = java.lang.foreign.MemoryLayout.sequenceLayout((long)capacity, java.lang.foreign.MemoryLayout.sequenceLayout((long)$L, $L)).withName($S)", f.length(), layoutCode, f.name());
                constr.addStatement("elements.add(sl)");
                constr.addStatement("currentOffset += sl.byteSize()");
            } else if (f.isStruct()) {
                constr.addStatement("sl = java.lang.foreign.MemoryLayout.sequenceLayout((long)capacity, $T.LAYOUT).withName($S)", ClassName.bestGuess(f.nestedImplName()), f.name());
                constr.addStatement("elements.add(sl)");
                constr.addStatement("currentOffset += sl.byteSize()");
                classBuilder.addField(TypeName.get(f.type()), f.name() + "_flyweight", Modifier.PRIVATE, Modifier.FINAL);
                constr.addStatement("this.$L_flyweight = ($T) com.github.goguma9071.jvmplus.memory.MemoryManager.createEmptyStruct($T.class)", f.name(), TypeName.get(f.type()), TypeName.get(f.type()));
            } else {
                constr.addStatement("sl = java.lang.foreign.MemoryLayout.sequenceLayout((long)capacity, java.lang.foreign.ValueLayout.JAVA_LONG.withByteAlignment(1)).withName($S)", f.name());
                constr.addStatement("elements.add(sl)");
                constr.addStatement("currentOffset += sl.byteSize()");
            }
        }

        constr.addStatement("java.lang.foreign.GroupLayout totalLayout = java.lang.foreign.MemoryLayout.structLayout(elements.toArray(new java.lang.foreign.MemoryLayout[0])).withByteAlignment(64)");
        constr.addStatement("this.totalSegment = this.arena.allocate(totalLayout)");
        constr.addStatement("com.github.goguma9071.jvmplus.memory.MemoryManager.track(this.totalSegment)");

        for (FieldModel f : model.fields()) {
            if (f.isStatic()) continue;
            if (f.isBitField() && !f.name().equals(f.bitFieldBackingName())) continue;
            constr.addStatement("this.$L_Segment = this.totalSegment.asSlice(totalLayout.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement($S)))", f.name(), f.name());
        }

        classBuilder.addStaticBlock(staticInit.build()).addMethod(constr.build());
        implementCommonSoAMethods(classBuilder, model, interfaceType);
        implementFieldMethods(classBuilder, model, true, interfaceType);

        JavaFile.builder(model.packageName(), classBuilder.build()).build().writeTo(filer);
    }

    private String getVectorType(TypeKind kind) {
        return switch(kind) {
            case DOUBLE -> "DoubleVector";
            case LONG -> "LongVector";
            case INT -> "IntVector";
            case FLOAT -> "FloatVector";
            default -> "ByteVector";
        };
    }

    private void implementCommonAoSMethods(TypeSpec.Builder classBuilder, StructModel model, ClassName interfaceType) {
        classBuilder.addMethod(MethodSpec.methodBuilder("address").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(long.class).addStatement("return segment.address()").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("segment").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(MemorySegment.class).addStatement("return segment").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("getPool").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(MemoryPool.class).addStatement("return pool").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("rebase").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addParameter(MemorySegment.class, "s").addStatement("this.segment = s").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("free").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addStatement("com.github.goguma9071.jvmplus.memory.MemoryManager.free(this)").build());

        classBuilder.addMethod(MethodSpec.methodBuilder("auto").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addTypeVariable(TypeVariableName.get("T", Struct.class)).returns(TypeVariableName.get("T"))
                .addStatement("java.lang.foreign.MemorySegment autoSeg = java.lang.foreign.Arena.ofAuto().allocate(LAYOUT)")
                .addStatement("java.lang.foreign.MemorySegment.copy(this.segment, 0, autoSeg, 0, LAYOUT.byteSize())")
                .addStatement("this.free()")
                .addStatement("$T obj = com.github.goguma9071.jvmplus.memory.MemoryManager.createEmptyStruct($T.class)", interfaceType, interfaceType)
                .addStatement("obj.rebase(autoSeg)")
                .addStatement("return (T) obj").build());

        MethodSpec.Builder asPtr = MethodSpec.methodBuilder("asPointer").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addTypeVariable(TypeVariableName.get("T", Struct.class))
                .returns(ParameterizedTypeName.get(ClassName.get(Pointer.class), TypeVariableName.get("T")))
                .addStatement("final java.lang.foreign.MemorySegment _s = this.segment")
                .addCode("return (Pointer<T>) new Pointer<$T>() {\n", interfaceType)
                .addCode("  private com.github.goguma9071.jvmplus.memory.MemoryPool _p = pool;\n")
                .addCode("  @Override public $T deref() { $T obj = com.github.goguma9071.jvmplus.memory.MemoryManager.createEmptyStruct($T.class); obj.rebase(_s.reinterpret(LAYOUT.byteSize(), java.lang.foreign.Arena.global(), s -> {})); return obj; }\n", interfaceType, interfaceType, interfaceType)
                .addCode("  @Override public void set($T v) { throw new UnsupportedOperationException(); }\n", interfaceType)
                .addCode("  @Override public long address() { return _s.address(); }\n")
                .addCode("  @Override public <U> Pointer<U> cast(Class<U> targetType) { return (Pointer<U>) com.github.goguma9071.jvmplus.memory.MemoryManager.createAddressPointer(_s.address(), targetType); }\n")
                .addCode("  @Override public long distanceTo(Pointer<$T> other) { return (this.address() - other.address()) / LAYOUT.byteSize(); }\n", interfaceType)
                .addCode("  @Override public Pointer<$T> offset(long count) {\n", interfaceType)
                .addCode("    long newAddr = _s.address() + count * LAYOUT.byteSize();\n")
                .addCode("    return new Pointer<$T>() {\n", interfaceType)
                .addCode("      @Override public $T deref() { $T obj = com.github.goguma9071.jvmplus.memory.MemoryManager.createEmptyStruct($T.class); obj.rebase(java.lang.foreign.MemorySegment.ofAddress(newAddr).reinterpret(LAYOUT.byteSize(), java.lang.foreign.Arena.global(), s -> {})); return obj; }\n", interfaceType, interfaceType, interfaceType)
                .addCode("      @Override public void set($T v) { throw new UnsupportedOperationException(); }\n", interfaceType)
                .addCode("      @Override public long address() { return newAddr; }\n")
                .addCode("      @Override public <U> Pointer<U> cast(Class<U> t) { return (Pointer<U>) com.github.goguma9071.jvmplus.memory.MemoryManager.createAddressPointer(newAddr, t); }\n")
                .addCode("      @Override public long distanceTo(Pointer<$T> o) { return (this.address() - o.address()) / LAYOUT.byteSize(); }\n", interfaceType)
                .addCode("      @Override public Pointer<$T> offset(long c) { throw new UnsupportedOperationException(); }\n", interfaceType)
                .addCode("      @Override public Class<$T> targetType() { return $T.class; }\n", interfaceType, interfaceType)
                .addCode("      @Override public Pointer<$T> auto() { return this; }\n", interfaceType)
                .addCode("      @Override public Object invoke(java.lang.foreign.FunctionDescriptor d, Object... a) { return com.github.goguma9071.jvmplus.memory.MemoryManager.invoke(address(), d, a); }\n")
                .addCode("      @Override @Deprecated public void close() { }\n")
                .addCode("      @Override public void free() { }\n")
                .addCode("    };\n")
                .addCode("  }\n")
                .addCode("  @Override public Class<$T> targetType() { return $T.class; }\n", interfaceType, interfaceType)
                .addCode("  @Override public Pointer<$T> auto() { return this; }\n", interfaceType)
                .addCode("  @Override public Object invoke(java.lang.foreign.FunctionDescriptor d, Object... a) { return com.github.goguma9071.jvmplus.memory.MemoryManager.invoke(address(), d, a); }\n")
                .addCode("  @Override @Deprecated public void close() { this.free(); }\n")
                .addCode("  @Override public void free() { if (_p != null) _p.free(_s); com.github.goguma9071.jvmplus.memory.MemoryManager.untrack(_s); }\n")
                .addCode("};\n");
        classBuilder.addMethod(asPtr.build());
        generateToString(classBuilder, model);
    }

    private void implementCommonSoAMethods(TypeSpec.Builder classBuilder, StructModel model, ClassName interfaceType) {
        classBuilder.addMethod(MethodSpec.methodBuilder("address").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(long.class).addStatement("return 0").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("segment").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(MemorySegment.class).addStatement("return null").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("getPool").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(MemoryPool.class).addStatement("return null").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("rebase").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addParameter(MemorySegment.class, "s").build());

        classBuilder.addMethod(MethodSpec.methodBuilder("free").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addStatement("arena.close()").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("close").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addAnnotation(Deprecated.class).addStatement("this.free()").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("get").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addParameter(int.class, "index").returns(interfaceType).addStatement("this.currentIndex = index").addStatement("return this").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("size").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(int.class).addStatement("return capacity").build());

        classBuilder.addMethod(MethodSpec.methodBuilder("iterator").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(ParameterizedTypeName.get(ClassName.get(java.util.Iterator.class), interfaceType))
                .addCode("return new java.util.Iterator<$T>() {\n", interfaceType)
                .addCode("  private int i = 0;\n")
                .addCode("  @Override public boolean hasNext() { return i < capacity; }\n")
                .addCode("  @Override public $T next() { currentIndex = i++; return $T.this; }\n", interfaceType, ClassName.bestGuess(model.implBaseName() + "SoAImpl"))
                .addCode("};\n").build());

        // sumDouble 구현
        MethodSpec.Builder sd = MethodSpec.methodBuilder("sumDouble").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(double.class).addParameter(String.class, "f");
        sd.beginControlFlow("switch(f)");
        for (FieldModel f : model.fields()) {
            if (f.type().getKind() == TypeKind.DOUBLE && !f.isArray() && !f.isStatic()) {
                String capitalized = f.name().substring(0, 1).toUpperCase() + f.name().substring(1);
                sd.addStatement("case $S: return sum$L()", f.name(), capitalized);
            }
        }
        sd.addStatement("default: throw new UnsupportedOperationException(\"Field not found or not a double: \" + f)");
        sd.endControlFlow();
        classBuilder.addMethod(sd.build());

        // sumLong 구현
        MethodSpec.Builder sl = MethodSpec.methodBuilder("sumLong").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(long.class).addParameter(String.class, "f");
        sl.beginControlFlow("switch(f)");
        for (FieldModel f : model.fields()) {
            if ((f.type().getKind() == TypeKind.LONG || f.type().getKind() == TypeKind.INT) && !f.isArray() && !f.isStatic()) {
                String capitalized = f.name().substring(0, 1).toUpperCase() + f.name().substring(1);
                sl.addStatement("case $S: return sum$L()", f.name(), capitalized);
                classBuilder.addMethod(generateSoASimdLongSum(f.name(), f.type().getKind()));
            }
        }
        sl.addStatement("default: throw new UnsupportedOperationException(\"Field not found or not a long/int: \" + f)");
        sl.endControlFlow();
        classBuilder.addMethod(sl.build());

        // [신규] 벌크 초기화(fill) 구현
        MethodSpec.Builder fill = MethodSpec.methodBuilder("fill").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addParameter(String.class, "f").addParameter(long.class, "v");
        fill.beginControlFlow("switch(f)");
        for (FieldModel f : model.fields()) {
            if ((f.type().getKind() == TypeKind.LONG || f.type().getKind() == TypeKind.INT) && !f.isArray() && !f.isStatic()) {
                String capitalized = f.name().substring(0, 1).toUpperCase() + f.name().substring(1);
                fill.addStatement("case $S: fill$L(v); break", f.name(), capitalized);
                classBuilder.addMethod(generateSoASimdFill(f.name(), f.type().getKind()));
            }
        }
        fill.addStatement("default: throw new UnsupportedOperationException(\"Field not found: \" + f)");
        fill.endControlFlow();
        classBuilder.addMethod(fill.build());

        classBuilder.addMethod(MethodSpec.methodBuilder("asPointer").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addTypeVariable(TypeVariableName.get("T", Struct.class)).returns(ParameterizedTypeName.get(ClassName.get(Pointer.class), TypeVariableName.get("T"))).addStatement("throw new UnsupportedOperationException()").build());
    }

    private MethodSpec generateSoASimdFill(String fieldName, TypeKind kind) {
        String capitalized = fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
        String speciesField = fieldName.toUpperCase() + "_SPECIES";
        boolean isLong = (kind == TypeKind.LONG);
        String vectorType = isLong ? "LongVector" : "IntVector";
        int byteSize = isLong ? 8 : 4;
        String layoutName = isLong ? "JAVA_LONG" : "JAVA_INT";

        return MethodSpec.methodBuilder("fill" + capitalized).addModifiers(Modifier.PRIVATE).addParameter(long.class, "val")
                .addStatement("var v = jdk.incubator.vector.$L.broadcast($L, ($T)val)", vectorType, speciesField, isLong ? long.class : int.class)
                .addStatement("int i = 0").addStatement("int upperBound = $L.loopBound(capacity)", speciesField)
                .beginControlFlow("for (; i < upperBound; i += $L.length())", speciesField)
                .addStatement("v.intoMemorySegment(this." + fieldName + "_Segment, (long)i * $L, java.nio.ByteOrder.nativeOrder())", byteSize)
                .endControlFlow()
                .beginControlFlow("for (; i < capacity; i++)")
                .addStatement("this." + fieldName + "_Segment.setAtIndex(java.lang.foreign.ValueLayout.$L.withByteAlignment(1), (long)i, ($T)val)", layoutName, isLong ? long.class : int.class)
                .endControlFlow().build();
    }

    private MethodSpec generateSoASimdLongSum(String fieldName, TypeKind kind) {
        String capitalized = fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
        String speciesField = fieldName.toUpperCase() + "_SPECIES";
        boolean isLong = (kind == TypeKind.LONG);
        String vectorType = isLong ? "LongVector" : "IntVector";
        int byteSize = isLong ? 8 : 4;
        String layoutName = isLong ? "JAVA_LONG" : "JAVA_INT";

        return MethodSpec.methodBuilder("sum" + capitalized).addModifiers(Modifier.PRIVATE).returns(long.class)
                .addStatement("var acc = jdk.incubator.vector.$L.zero($L)", vectorType, speciesField)
                .addStatement("int i = 0").addStatement("int upperBound = $L.loopBound(capacity)", speciesField)
                .beginControlFlow("for (; i < upperBound; i += $L.length())", speciesField)
                .addStatement("var v = jdk.incubator.vector.$L.fromMemorySegment($L, this." + fieldName + "_Segment, (long)i * $L, java.nio.ByteOrder.nativeOrder())", vectorType, speciesField, byteSize)
                .addStatement("acc = acc.add(v)")
                .endControlFlow()
                .addStatement("long total = (long) acc.reduceLanes(jdk.incubator.vector.VectorOperators.ADD)")
                .beginControlFlow("for (; i < capacity; i++)")
                .addStatement("total += (long)this." + fieldName + "_Segment.getAtIndex(java.lang.foreign.ValueLayout.$L.withByteAlignment(1), (long)i)", layoutName)
                .endControlFlow()
                .addStatement("return total").build();
    }

    private void implementFieldMethods(TypeSpec.Builder classBuilder, StructModel model, boolean isSoA, ClassName interfaceType) {
        ClassName aosImplClassName = ClassName.get(model.packageName(), model.implBaseName() + "Impl");
        for (FieldModel f : model.fields()) {
            String seg = f.isStatic() ? "STATIC_SEGMENT" : (isSoA ? "this." + f.name() + "_Segment" : "this.segment");
            String offset = f.isStatic() ? aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_OFFSET" : (isSoA ? "(long)currentIndex" : "0L");

            String handle = isSoA && !f.isStatic() && !f.isBitField() && f.type().getKind().isPrimitive() && !f.isArray() && !f.isPointer()
                    ? (f.name().toUpperCase() + "_HANDLE")
                    : aosImplClassName.simpleName() + "." + (f.isBitField() ? f.bitFieldBackingName() : f.name()).toUpperCase() + "_HANDLE";

            // Getter
            MethodSpec.Builder getter = MethodSpec.overriding(f.getter());
            if (f.isString()) {
                getter.addStatement("byte[] b = $L.asSlice($L * $L, $L).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)", seg, offset, f.length(), f.length());
                getter.addStatement("int l=0; while(l<b.length && b[l]!=0) l++; return new String(b, 0, l, java.nio.charset.StandardCharsets.UTF_8)");
            } else if (f.isRaw()) {
                getter.addStatement("final java.lang.foreign.MemorySegment _s = $L.asSlice($L * $L, $L)", seg, offset, f.length(), f.length());
                getter.beginControlFlow("return new com.github.goguma9071.jvmplus.memory.RawBuffer()")
                        .addCode("  @Override public java.lang.foreign.MemorySegment segment() { return _s; }\n")
                        .addCode("  @Override public void free() { }\n")
                        .endControlFlow().addCode(";\n");
            } else if (f.isBitField()) {
                String accessParam = isSoA && !f.isStatic() ? "(long)currentIndex * 8" : (f.isStatic() ? offset : aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_OFFSET");
                long mask = (f.bitCount() == 64) ? -1L : (1L << f.bitCount()) - 1;
                getter.addStatement("long val = ((Number) $L.get($L, $L)).longValue()", handle, seg, accessParam);
                getter.addStatement("return ($T) ((val >>> $L) & $LL)", f.type(), f.bitOffset(), mask);
            } else if (f.type().toString().equals("java.lang.Object")) {
                if (isSoA && !f.isStatic()) {
                    getter.addStatement("return com.github.goguma9071.jvmplus.memory.MemoryManager.getHandle($L.getAtIndex(java.lang.foreign.ValueLayout.JAVA_LONG.withByteAlignment(1), (long)currentIndex))", seg);
                } else {
                    String aosOffset = f.isStatic() ? offset : aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_OFFSET";
                    getter.addStatement("return com.github.goguma9071.jvmplus.memory.MemoryManager.getHandle($L.get(java.lang.foreign.ValueLayout.JAVA_LONG.withByteAlignment(1), $L))", seg, aosOffset);
                }
            } else if (f.isAtomic() || (!f.isString() && !f.isRaw() && !f.isArray() && !f.isEnum() && !f.isPointer() && !f.isStruct())) {
                if (isSoA && !f.isStatic()) {
                    getter.addStatement("return ($T) $L.getAtIndex($L, (long)currentIndex)", f.type(), seg, getSimpleLayoutCode(f.type()));
                } else {
                    String aosOffset = f.isStatic() ? offset : aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_OFFSET";
                    if (f.isAtomic()) getter.addStatement("return ($T) $L.get($L, $L)", f.type(), handle, seg, aosOffset);
                    else getter.addStatement("return ($T) $L.get($L, 0L)", f.type(), handle, seg);
                }
            } else if (f.isEnum()) {
                String layout = f.enumSize() == 1 ? "java.lang.foreign.ValueLayout.JAVA_BYTE" : "java.lang.foreign.ValueLayout.JAVA_INT.withByteAlignment(1)";
                String enumOffset = isSoA ? "(long)currentIndex * " + f.size() : aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_OFFSET";
                getter.addStatement("return $T.values()[$L.get($L, $L)]", TypeName.get(f.type()), seg, layout, enumOffset);
            } else if (f.isStruct()) {
                if (isSoA && !f.isStatic()) getter.addStatement("this.$L_flyweight.rebase(this.$L_Segment.asSlice((long)currentIndex * $T.LAYOUT.byteSize(), $T.LAYOUT.byteSize())); return this.$L_flyweight", f.name(), f.name(), ClassName.bestGuess(f.nestedImplName()), ClassName.bestGuess(f.nestedImplName()), f.name());
                else getter.addStatement("return new $T($L.asSlice($L, $T.LAYOUT.byteSize()), null)", ClassName.bestGuess(f.nestedImplName()), seg, isSoA ? offset : aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_OFFSET", ClassName.bestGuess(f.nestedImplName()));
            } else if (f.isPointer()) {
                TypeMirror targetType = ((DeclaredType) f.type()).getTypeArguments().get(0);
                String targetImpl = f.nestedImplName();
                ClassName targetTypeClassName = (ClassName) TypeName.get(targetType);
                ClassName targetImplClassName = ClassName.bestGuess(targetImpl);
                String ptrOffset = isSoA ? "(long)currentIndex * 8" : aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_OFFSET";
                getter.addStatement("long addr = $L.get(java.lang.foreign.ValueLayout.JAVA_LONG.withByteAlignment(1), $L)", seg, ptrOffset);
                getter.addCode("return new Pointer<$T>() {\n", targetTypeClassName)
                        .addCode("  @Override public $T deref() { $T obj = com.github.goguma9071.jvmplus.memory.MemoryManager.createEmptyStruct($T.class); obj.rebase(java.lang.foreign.MemorySegment.ofAddress(addr).reinterpret($T.LAYOUT.byteSize(), java.lang.foreign.Arena.global(), s -> {})); return obj; }\n", targetTypeClassName, targetTypeClassName, targetTypeClassName, targetImplClassName)
                        .addCode("  @Override public void set($T v) { $L.set(java.lang.foreign.ValueLayout.JAVA_LONG.withByteAlignment(1), $L, v.address()); }\n", targetTypeClassName, seg, ptrOffset)
                        .addCode("  @Override public long address() { return addr; }\n")
                        .addCode("  @Override public <U> Pointer<U> cast(Class<U> t) { return (Pointer<U>) com.github.goguma9071.jvmplus.memory.MemoryManager.createAddressPointer(addr, t); }\n")
                        .addCode("  @Override public long distanceTo(Pointer<$T> other) { return (this.address() - other.address()) / $T.LAYOUT.byteSize(); }\n", targetTypeClassName, targetImplClassName)
                        .addCode("  @Override public Pointer<$T> offset(long c) { throw new UnsupportedOperationException(); }\n", targetTypeClassName)
                        .addCode("  @Override public Class<$T> targetType() { return $T.class; }\n", targetTypeClassName, targetTypeClassName)
                        .addCode("  @Override public Pointer<$T> auto() { return this; }\n", targetTypeClassName)
                        .addCode("  @Override public Object invoke(java.lang.foreign.FunctionDescriptor d, Object... a) { return com.github.goguma9071.jvmplus.memory.MemoryManager.invoke(address(), d, a); }\n")
                        .addCode("  @Override @Deprecated public void close() { }\n")
                        .addCode("  @Override public void free() { }\n")
                        .addCode("};\n");
            } else if (f.isArray()) {
                String layout = getSimpleLayoutCode(f.type());
                String baseOffset = isSoA ? "0" : aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_OFFSET";
                getter.addParameter(int.class, "idx").addStatement("if (idx < 0 || idx >= $L) throw new IndexOutOfBoundsException()", f.length());
                if (isSoA) getter.addStatement("return $L.getAtIndex($L, (long)currentIndex * $L + idx)", seg, layout, f.length());
                else getter.addStatement("return $L.get($L, $L + (long)idx * $L.byteSize())", seg, layout, baseOffset, layout);
            }
            classBuilder.addMethod(getter.build());

            // Setter
            MethodSpec.Builder setter = MethodSpec.overriding(f.setter());
            String paramName = f.setter().getParameters().get(f.isArray() ? 1 : 0).getSimpleName().toString();
            if (f.isString()) {
                setter.addStatement("byte[] b = $L.getBytes(java.nio.charset.StandardCharsets.UTF_8)", paramName);
                setter.addStatement("int l = Math.min(b.length, $L)", f.length());
                setter.addStatement("java.lang.foreign.MemorySegment.copy(java.lang.foreign.MemorySegment.ofArray(b), 0, $L, $L * $L, l)", seg, offset, f.length());
                setter.addStatement("if(l < $L) $L.asSlice($L * $L + l, $L - l).fill((byte)0)", f.length(), seg, offset, f.length(), f.length());
            } else if (f.isBitField()) {
                String accessParam = isSoA && !f.isStatic() ? "(long)currentIndex * 8" : (f.isStatic() ? offset : aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_OFFSET");
                long mask = (f.bitCount() == 64) ? -1L : (1L << f.bitCount()) - 1;
                setter.addStatement("long old = ((Number) $L.get($L, $L)).longValue()", handle, seg, accessParam);
                setter.addStatement("long updated = (old & ~($LL << $L)) | (($L & $LL) << $L)", mask, f.bitOffset(), paramName, mask, f.bitOffset());
                setter.addStatement("$L.set($L, $L, updated)", handle, seg, accessParam);
            } else if (f.type().toString().equals("java.lang.Object")) {
                if (isSoA && !f.isStatic()) {
                    setter.addStatement("$L.setAtIndex(java.lang.foreign.ValueLayout.JAVA_LONG.withByteAlignment(1), (long)currentIndex, com.github.goguma9071.jvmplus.memory.MemoryManager.registerHandle($L))", seg, paramName);
                } else {
                    String aosOffset = f.isStatic() ? offset : aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_OFFSET";
                    setter.addStatement("$L.set(java.lang.foreign.ValueLayout.JAVA_LONG.withByteAlignment(1), $L, com.github.goguma9071.jvmplus.memory.MemoryManager.registerHandle($L))", seg, aosOffset, paramName);
                }
            } else if (f.isAtomic() || (!f.isString() && !f.isRaw() && !f.isArray() && !f.isEnum() && !f.isPointer() && !f.isStruct())) {
                if (isSoA && !f.isStatic()) {
                    setter.addStatement("$L.setAtIndex($L, (long)currentIndex, $L)", seg, getSimpleLayoutCode(f.type()), paramName);
                } else {
                    String aosOffset = f.isStatic() ? offset : aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_OFFSET";
                    if (f.isAtomic()) setter.addStatement("$L.set($L, $L, $L)", handle, seg, aosOffset, paramName);
                    else setter.addStatement("$L.set($L, 0L, $L)", handle, seg, paramName);
                }
            } else if (f.isPointer()) {
                String ptrOffset = isSoA ? "(long)currentIndex * 8" : aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_OFFSET";
                setter.addStatement("$L.set(java.lang.foreign.ValueLayout.JAVA_LONG.withByteAlignment(1), $L, $L.address())", seg, ptrOffset, paramName);
            } else if (f.isArray()) {
                String layout = getSimpleLayoutCode(f.type());
                String baseOffset = isSoA ? "0" : aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_OFFSET";
                setter.addStatement("if (idx < 0 || idx >= $L) throw new IndexOutOfBoundsException()", f.length());
                if (isSoA) setter.addStatement("$L.setAtIndex($L, (long)currentIndex * $L + idx, $L)", seg, layout, f.length(), paramName);
                else setter.addStatement("$L.set($L, $L + (long)idx * $L.byteSize(), $L)", seg, layout, baseOffset, layout, paramName);
            }
            setter.addStatement("return this");
            classBuilder.addMethod(setter.build());

            // Atomic methods (CAS, ADD)
            if (f.isAtomic()) {
                String capitalized = f.name().substring(0, 1).toUpperCase() + f.name().substring(1);
                String atomicHandle = aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_HANDLE";
                String atomicOffset = isSoA ? "(long)currentIndex * " + f.size() : (f.isStatic() ? offset : aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_OFFSET");

                classBuilder.addMethod(MethodSpec.methodBuilder("cas" + capitalized).addModifiers(Modifier.PUBLIC).returns(interfaceType)
                        .addParameter(TypeName.get(f.type()), "expected").addParameter(TypeName.get(f.type()), "val")
                        .addStatement("$L.compareAndSet($L, $L, expected, val)", atomicHandle, seg, atomicOffset)
                        .addStatement("return this").build());
                if (f.type().getKind() == TypeKind.INT || f.type().getKind() == TypeKind.LONG) {
                    classBuilder.addMethod(MethodSpec.methodBuilder("addAndGet" + capitalized).addModifiers(Modifier.PUBLIC).returns(interfaceType)
                            .addParameter(TypeName.get(f.type()), "d")
                            .addStatement("$L.getAndAdd($L, $L, d)", atomicHandle, seg, atomicOffset)
                            .addStatement("return this").build());
                }
            }
        }

        // Native Call methods
        for (ExecutableElement nc : model.nativeCalls()) {
            MethodSpec.Builder mb = MethodSpec.overriding(nc);
            String handleName = aosImplClassName.simpleName() + ".NC_" + nc.getSimpleName().toString().toUpperCase() + "_HANDLE";
            mb.beginControlFlow("try (java.lang.foreign.Arena _callArena = java.lang.foreign.Arena.ofConfined())");
            List<String> args = nc.getParameters().stream().map(p -> {
                if (p.asType().toString().equals("java.lang.String")) {
                    mb.addStatement("java.lang.foreign.MemorySegment _arg_$L = _callArena.allocateFrom($L)", p.getSimpleName(), p.getSimpleName());
                    return "_arg_" + p.getSimpleName();
                }
                return p.getSimpleName().toString();
            }).collect(Collectors.toList());
            if (nc.getReturnType().getKind() == TypeKind.VOID) mb.addStatement("$L.invoke($L)", handleName, String.join(", ", args));
            else mb.addStatement("return ($T) $L.invoke($L)", TypeName.get(nc.getReturnType()), handleName, String.join(", ", args));
            mb.nextControlFlow("catch (Throwable _t)").addStatement("throw new RuntimeException(_t)").endControlFlow();
            classBuilder.addMethod(mb.build());
        }
    }

    private void generateToString(TypeSpec.Builder classBuilder, StructModel model) {
        MethodSpec.Builder ts = MethodSpec.methodBuilder("toString").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(String.class)
                .addStatement("$T sb = new $T()", StringBuilder.class, StringBuilder.class)
                .addStatement("sb.append($S).append(\" {\")", model.interfaceName());
        for (FieldModel f : model.fields()) if (!f.isStatic()) ts.addStatement("sb.append(\"\\n  \").append($S).append(\": \").append($L())", f.name(), f.name());
        ts.addStatement("sb.append(\"\\n}\")").addStatement("return sb.toString()");
        classBuilder.addMethod(ts.build());
    }

    private MethodSpec generateSoASimdSum(String fieldName) {
        String capitalized = fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
        String speciesField = fieldName.toUpperCase() + "_SPECIES";
        return MethodSpec.methodBuilder("sum" + capitalized).addModifiers(Modifier.PRIVATE).returns(double.class)
                .addStatement("jdk.incubator.vector.DoubleVector acc = jdk.incubator.vector.DoubleVector.zero($L)", speciesField)
                .addStatement("int i = 0").addStatement("int upperBound = $L.loopBound(capacity)", speciesField)
                .beginControlFlow("for (; i < upperBound; i += $L.length())", speciesField)
                .addStatement("jdk.incubator.vector.DoubleVector v = jdk.incubator.vector.DoubleVector.fromMemorySegment($L, this.$L_Segment, (long)i * 8, java.nio.ByteOrder.nativeOrder())", speciesField, fieldName)
                .addStatement("acc = acc.add(v)")
                .endControlFlow()
                .addStatement("double total = acc.reduceLanes(jdk.incubator.vector.VectorOperators.ADD)")
                .beginControlFlow("for (; i < capacity; i++)")
                .addStatement("total += this.$L_Segment.getAtIndex(java.lang.foreign.ValueLayout.JAVA_DOUBLE.withByteAlignment(1), (long)i)", fieldName)
                .endControlFlow()
                .addStatement("return total").build();
    }

    private String getLayoutCode(FieldModel f, boolean isRawValue) {
        if (!isRawValue && (f.isString() || f.isRaw())) return "java.lang.foreign.MemoryLayout.sequenceLayout(" + f.length() + ", java.lang.foreign.ValueLayout.JAVA_BYTE)";
        if (!isRawValue && f.isArray()) return "java.lang.foreign.MemoryLayout.sequenceLayout(" + f.length() + ", " + getSimpleLayoutCode(f.type()) + ")";
        if (f.isStruct()) return ClassName.bestGuess(f.nestedImplName()) + ".LAYOUT";
        return getSimpleLayoutCode(f.type());
    }

    private String getSimpleLayoutCode(TypeMirror t) {
        return switch (t.getKind()) {
            case INT -> "java.lang.foreign.ValueLayout.JAVA_INT.withByteAlignment(1)";
            case LONG -> "java.lang.foreign.ValueLayout.JAVA_LONG.withByteAlignment(1)";
            case DOUBLE -> "java.lang.foreign.ValueLayout.JAVA_DOUBLE.withByteAlignment(1)";
            case FLOAT -> "java.lang.foreign.ValueLayout.JAVA_FLOAT.withByteAlignment(1)";
            case BYTE -> "java.lang.foreign.ValueLayout.JAVA_BYTE";
            default -> "java.lang.foreign.ValueLayout.ADDRESS.withByteAlignment(1)";
        };
    }

    private String getDescriptorCode(ExecutableElement nc) {
        String ret = nc.getReturnType().getKind() == TypeKind.VOID ? "ofVoid" : "of";
        String retLayout = (nc.getReturnType().getKind() == TypeKind.VOID) ? "" : getLayoutForType(nc.getReturnType());
        String args = nc.getParameters().stream().map(p -> getLayoutForType(p.asType())).collect(Collectors.joining(", "));
        return "java.lang.foreign.FunctionDescriptor." + ret + "(" + retLayout + (retLayout.isEmpty() || args.isEmpty() ? "" : ", ") + args + ")";
    }

    private String getLayoutForType(TypeMirror t) {
        return switch (t.getKind()) {
            case INT -> "java.lang.foreign.ValueLayout.JAVA_INT.withByteAlignment(1)";
            case LONG -> "java.lang.foreign.ValueLayout.JAVA_LONG.withByteAlignment(1)";
            case DOUBLE -> "java.lang.foreign.ValueLayout.JAVA_DOUBLE.withByteAlignment(1)";
            case FLOAT -> "java.lang.foreign.ValueLayout.JAVA_FLOAT.withByteAlignment(1)";
            default -> "java.lang.foreign.ValueLayout.ADDRESS.withByteAlignment(1)";
        };
    }

    private String getTypeName(FieldModel f) {
        if (f.isString()) return "char[" + f.length() + "]";
        if (f.isRaw()) return "byte[" + f.length() + "]";
        if (f.isArray()) return f.type().toString() + "[" + f.length() + "]";
        return f.type().toString();
    }

    private long getAlignment(FieldModel f) {
        return 1;
    }
}
