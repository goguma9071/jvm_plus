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
        StringBuilder mapBuilder = new StringBuilder();
        mapBuilder.append("[Struct Layout: ").append(interfaceType.canonicalName()).append("]\\n\\n");

        long lastEnd = 0;
        for (FieldModel f : model.fields()) {
            if (f.isStatic()) continue;

            if (f.isStruct()) {
                classBuilder.addField(ClassName.bestGuess(f.nestedImplName()), f.name() + "_flyweight", Modifier.PRIVATE, Modifier.FINAL);
                constr.addStatement("this.$L_flyweight = ($T) com.github.goguma9071.jvmplus.memory.MemoryManager.createEmptyStruct($T.class)", f.name(), ClassName.bestGuess(f.nestedImplName()), TypeName.get(f.type()));
            }

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
        
        String layoutExpr = "java.lang.foreign.MemoryLayout.structLayout(elements.toArray(new java.lang.foreign.MemoryLayout[0]))";
        if (model.defaultAlignment() > 0) {
            staticInit.addStatement("LAYOUT = $L.withByteAlignment($L)", layoutExpr, model.defaultAlignment());
        } else {
            staticInit.addStatement("LAYOUT = $L", layoutExpr);
        }
        
        staticInit.addStatement("STATIC_SEGMENT = java.lang.foreign.Arena.global().allocate(LAYOUT)");
        staticInit.addStatement("System.out.println($S + LAYOUT.byteSize() + $S)", mapBuilder.toString() + "\nTotal Size: ", " bytes\n");

        classBuilder.addStaticBlock(staticInit.build()).addMethod(constr.build());

        classBuilder.addMethod(MethodSpec.methodBuilder("allocate")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(MemorySegment.class, "segment")
                .addParameter(MemoryPool.class, "pool")
                .returns(interfaceType)
                .addStatement("return new $T(segment, pool)", ClassName.get(model.packageName(), implName))
                .build());

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
            String layoutCode = getLayoutCode(f, true);

            constr.beginControlFlow("if (currentOffset % 64 != 0)")
                    .addStatement("long p = 64 - (currentOffset % 64)")
                    .addStatement("elements.add(java.lang.foreign.MemoryLayout.paddingLayout(p))")
                    .addStatement("currentOffset += p")
                    .endControlFlow();

            if (f.type().getKind().isPrimitive() && !f.isArray() && !f.isPointer() && (!f.isBitField() || f.name().equals(f.bitFieldBackingName()))) {
                classBuilder.addField(MemorySegment.class, segName, Modifier.PUBLIC, Modifier.FINAL);
                classBuilder.addField(VarHandle.class, f.name().toUpperCase() + "_HANDLE", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
                staticInit.addStatement("$L_HANDLE = $L.arrayElementVarHandle()", f.name().toUpperCase(), layoutCode);

                String speciesName = f.name().toUpperCase() + "_SPECIES";
                classBuilder.addField(ClassName.get("jdk.incubator.vector", "VectorSpecies"), speciesName, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
                staticInit.addStatement("$L = jdk.incubator.vector.$L.SPECIES_PREFERRED", speciesName, getVectorType(f.type().getKind()));

                constr.addStatement("sl = java.lang.foreign.MemoryLayout.sequenceLayout((long)capacity, $L).withName($S)", layoutCode, f.name());
                constr.addStatement("elements.add(sl)");
                constr.addStatement("currentOffset += sl.byteSize()");
            } else if (f.isString()) {
                classBuilder.addField(MemorySegment.class, segName, Modifier.PUBLIC, Modifier.FINAL);
                constr.addStatement("sl = java.lang.foreign.MemoryLayout.sequenceLayout((long)capacity, java.lang.foreign.MemoryLayout.sequenceLayout((long)$L, java.lang.foreign.ValueLayout.JAVA_BYTE)).withName($S)", f.length(), f.name());
                constr.addStatement("elements.add(sl)");
                constr.addStatement("currentOffset += sl.byteSize()");
            } else if (f.isArray()) {
                classBuilder.addField(MemorySegment.class, segName, Modifier.PUBLIC, Modifier.FINAL);
                constr.addStatement("sl = java.lang.foreign.MemoryLayout.sequenceLayout((long)capacity, java.lang.foreign.MemoryLayout.sequenceLayout((long)$L, $L)).withName($S)", f.length(), layoutCode, f.name());
                constr.addStatement("elements.add(sl)");
                constr.addStatement("currentOffset += sl.byteSize()");
            } else if (f.isStruct()) {
                classBuilder.addField(MemorySegment.class, segName, Modifier.PUBLIC, Modifier.FINAL);
                constr.addStatement("sl = java.lang.foreign.MemoryLayout.sequenceLayout((long)capacity, $T.LAYOUT).withName($S)", ClassName.bestGuess(f.nestedImplName()), f.name());
                constr.addStatement("elements.add(sl)");
                constr.addStatement("currentOffset += sl.byteSize()");
                classBuilder.addField(TypeName.get(f.type()), f.name() + "_flyweight", Modifier.PRIVATE, Modifier.FINAL);
                constr.addStatement("this.$L_flyweight = ($T) com.github.goguma9071.jvmplus.memory.MemoryManager.createEmptyStruct($T.class)", f.name(), TypeName.get(f.type()), TypeName.get(f.type()));
            } else if (f.isPointer()) {
                classBuilder.addField(MemorySegment.class, segName, Modifier.PUBLIC, Modifier.FINAL);
                constr.addStatement("sl = java.lang.foreign.MemoryLayout.sequenceLayout((long)capacity, java.lang.foreign.ValueLayout.JAVA_LONG).withName($S)", f.name());
                constr.addStatement("elements.add(sl)");
                constr.addStatement("currentOffset += sl.byteSize()");
            } else if (!f.isBitField()) {
                classBuilder.addField(MemorySegment.class, segName, Modifier.PUBLIC, Modifier.FINAL);
                constr.addStatement("sl = java.lang.foreign.MemoryLayout.sequenceLayout((long)capacity, java.lang.foreign.ValueLayout.JAVA_LONG).withName($S)", f.name());
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
        classBuilder.addMethod(MethodSpec.methodBuilder("address").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(long.class).addStatement("return _address").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("segment").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(MemorySegment.class).addStatement("return segment").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("getPool").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(MemoryPool.class).addStatement("return pool").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("rebase").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addParameter(MemorySegment.class, "s").addStatement("this.segment = s").addStatement("this._address = s != null ? s.address() : 0L").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("rebase").addModifiers(Modifier.PUBLIC).addParameter(long.class, "addr").addStatement("this._address = addr").addStatement("this.segment = null").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("free").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addStatement("com.github.goguma9071.jvmplus.memory.MemoryManager.free(this)").build());
        
        classBuilder.addMethod(MethodSpec.methodBuilder("auto").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addTypeVariable(TypeVariableName.get("T", Struct.class)).returns(TypeVariableName.get("T"))
                .addStatement("if (pool == null) return (T) this")
                .addStatement("java.lang.foreign.MemorySegment autoSeg = java.lang.foreign.Arena.ofAuto().allocate(LAYOUT)")
                .addStatement("java.lang.foreign.MemorySegment.copy(segment, 0, autoSeg, 0, LAYOUT.byteSize())")
                .addStatement("pool.free(segment)")
                .addStatement("com.github.goguma9071.jvmplus.memory.MemoryManager.untrack(segment)")
                .addStatement("this.segment = autoSeg")
                .addStatement("this._address = autoSeg.address()")
                .addStatement("return (T) this").build());

        MethodSpec.Builder asPtr = MethodSpec.methodBuilder("asPointer").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addTypeVariable(TypeVariableName.get("T", Struct.class))
                .returns(ParameterizedTypeName.get(ClassName.get(Pointer.class), TypeVariableName.get("T")));
        asPtr.addStatement("final long addr = this.address()");
        asPtr.addStatement("final Class<T> type = (Class<T>) $T.class", interfaceType);
        asPtr.beginControlFlow("return new Pointer<T>()")
                .addCode("  @Override public T deref() { return com.github.goguma9071.jvmplus.memory.MemoryManager.createAddressPointer(addr, type).deref(); }\n")
                .addCode("  @Override public void set(T v) { throw new UnsupportedOperationException(); }\n")
                .addCode("  @Override public long address() { return addr; }\n")
                .addCode("  @Override public <U> Pointer<U> cast(Class<U> t) { return com.github.goguma9071.jvmplus.memory.MemoryManager.createAddressPointer(addr, t); }\n")
                .addCode("  @Override public long distanceTo(Pointer<T> other) { return (this.address() - other.address()) / LAYOUT.byteSize(); }\n")
                .addCode("  @Override public Pointer<T> offset(long c) { return com.github.goguma9071.jvmplus.memory.MemoryManager.createAddressPointer(addr + c * LAYOUT.byteSize(), type); }\n")
                .addCode("  @Override public Class<T> targetType() { return type; }\n")
                .addCode("  @Override public Pointer<T> auto() { return this; }\n")
                .addCode("  @Override public Object invoke(java.lang.foreign.FunctionDescriptor d, Object... a) { return com.github.goguma9071.jvmplus.memory.MemoryManager.invoke(address(), d, a); }\n")
                .addCode("  @Override @Deprecated public void close() { }\n")
                .addCode("  @Override public void free() { }\n")
                .endControlFlow().addCode(";\n");
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
                .beginControlFlow("return new java.util.Iterator<>()")
                .addStatement("private int current = 0")
                .addCode("@Override public boolean hasNext() { return current < capacity; }\n")
                .addCode("@Override public $T next() { return get(current++); }\n", interfaceType)
                .endControlFlow().addCode(";\n").build());

        MethodSpec.Builder sd = MethodSpec.methodBuilder("sumDouble").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addParameter(String.class, "f").returns(double.class);
        sd.beginControlFlow("switch(f)");
        for (FieldModel f : model.fields()) {
            if (f.type().getKind() == TypeKind.DOUBLE && !f.isArray() && !f.isStatic()) {
                String capitalized = f.name().substring(0, 1).toUpperCase() + f.name().substring(1);
                sd.addStatement("case $S: return sum$L()", f.name(), capitalized);
            }
        }
        sd.addStatement("default: throw new UnsupportedOperationException(\"Field not found or not double: \" + f)");
        sd.endControlFlow();
        classBuilder.addMethod(sd.build());

        MethodSpec.Builder sl = MethodSpec.methodBuilder("sumLong").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addParameter(String.class, "f").returns(long.class);
        sl.beginControlFlow("switch(f)");
        for (FieldModel f : model.fields()) {
            if (f.type().getKind() == TypeKind.LONG && !f.isArray() && !f.isStatic() && (!f.isBitField() || f.name().equals(f.bitFieldBackingName()))) {
                String capitalized = f.name().substring(0, 1).toUpperCase() + f.name().substring(1);
                sl.addStatement("case $S: return sum$L()", f.name(), capitalized);
            }
        }
        sl.addStatement("default: throw new UnsupportedOperationException(\"Field not found or not long: \" + f)");
        sl.endControlFlow();
        classBuilder.addMethod(sl.build());

        MethodSpec.Builder fill = MethodSpec.methodBuilder("fill").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addParameter(String.class, "f").addParameter(long.class, "v");
        fill.beginControlFlow("switch(f)");
        for (FieldModel f : model.fields()) {
            if ((f.type().getKind() == TypeKind.LONG || f.type().getKind() == TypeKind.INT) && !f.isArray() && !f.isStatic() && (!f.isBitField() || f.name().equals(f.bitFieldBackingName()))) {
                String capitalized = f.name().substring(0, 1).toUpperCase() + f.name().substring(1);
                fill.addStatement("case $S: fill$L(($T)v); break", f.name(), capitalized, TypeName.get(f.type()));
            }
        }
        fill.addStatement("default: throw new UnsupportedOperationException(\"Field not found: \" + f)");
        fill.endControlFlow();
        classBuilder.addMethod(fill.build());

        classBuilder.addMethod(MethodSpec.methodBuilder("asPointer").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addTypeVariable(TypeVariableName.get("T", Struct.class)).returns(ParameterizedTypeName.get(ClassName.get(Pointer.class), TypeVariableName.get("T"))).addStatement("throw new UnsupportedOperationException()").build());
    }

    private void implementFieldMethods(TypeSpec.Builder classBuilder, StructModel model, boolean isSoA, ClassName interfaceType) {
        ClassName aosImplClassName = ClassName.get(model.packageName(), model.implBaseName() + "Impl");
        for (FieldModel f : model.fields()) {
            String backingName = f.isBitField() ? f.bitFieldBackingName() : f.name();
            String seg = f.isStatic() ? "STATIC_SEGMENT" : (isSoA ? "this." + backingName + "_Segment" : "this.segment");
            String offset = f.isStatic() ? aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_OFFSET" : (isSoA ? "(long)currentIndex" : "0L");

            String handle = isSoA && !f.isStatic() && !f.isBitField() && f.type().getKind().isPrimitive() && !f.isArray() && !f.isPointer()
                    ? (f.name().toUpperCase() + "_HANDLE")
                    : aosImplClassName.simpleName() + "." + backingName.toUpperCase() + "_HANDLE";

            MethodSpec.Builder getter = MethodSpec.overriding(f.getter());
            generateGetterBody(getter, f, seg, offset, handle, isSoA, aosImplClassName);
            classBuilder.addMethod(getter.build());

            if (!isSoA && !f.isStatic()) {
                MethodSpec.Builder staticGetter = MethodSpec.methodBuilder("get_" + f.name()).addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(TypeName.get(f.type())).addParameter(MemorySegment.class, "seg");
                if (f.isArray()) staticGetter.addParameter(int.class, "idx");
                generateGetterBody(staticGetter, f, "seg", aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_OFFSET", handle, false, aosImplClassName);
                classBuilder.addMethod(staticGetter.build());

                MethodSpec.Builder staticGetterAddr = MethodSpec.methodBuilder("get_" + f.name()).addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(TypeName.get(f.type())).addParameter(long.class, "addr");
                if (f.isArray()) staticGetterAddr.addParameter(int.class, "idx");
                generateGetterBody(staticGetterAddr, f, "addr", aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_OFFSET", handle, false, aosImplClassName);
                classBuilder.addMethod(staticGetterAddr.build());
            }

            if (f.setter() != null) {
                MethodSpec.Builder setter = MethodSpec.overriding(f.setter());
                String paramName = f.setter().getParameters().get(f.isArray() ? 1 : 0).getSimpleName().toString();
                generateSetterBody(setter, f, seg, offset, handle, isSoA, aosImplClassName, paramName);
                setter.addStatement("return this");
                classBuilder.addMethod(setter.build());

                if (!isSoA && !f.isStatic()) {
                    MethodSpec.Builder staticSetter = MethodSpec.methodBuilder("set_" + f.name()).addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter(MemorySegment.class, "seg");
                    if (f.isArray()) staticSetter.addParameter(int.class, "idx");
                    staticSetter.addParameter(TypeName.get(f.type()), "val");
                    generateSetterBody(staticSetter, f, "seg", aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_OFFSET", handle, false, aosImplClassName, "val");
                    classBuilder.addMethod(staticSetter.build());

                    MethodSpec.Builder staticSetterAddr = MethodSpec.methodBuilder("set_" + f.name()).addModifiers(Modifier.PUBLIC, Modifier.STATIC).addParameter(long.class, "addr");
                    if (f.isArray()) staticSetterAddr.addParameter(int.class, "idx");
                    staticSetterAddr.addParameter(TypeName.get(f.type()), "val");
                    generateSetterBody(staticSetterAddr, f, "addr", aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_OFFSET", handle, false, aosImplClassName, "val");
                    classBuilder.addMethod(staticSetterAddr.build());
                }
            }

            if (f.isAtomic()) {
                String capitalized = f.name().substring(0, 1).toUpperCase() + f.name().substring(1);
                String atomicHandle = aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_HANDLE";
                
                if (isSoA) {
                    String atomicOffset = "(long)currentIndex * " + f.size();
                    classBuilder.addMethod(MethodSpec.methodBuilder("cas" + capitalized).addModifiers(Modifier.PUBLIC).returns(interfaceType)
                            .addParameter(TypeName.get(f.type()), "expected").addParameter(TypeName.get(f.type()), "val")
                            .addStatement("$L.compareAndSet($L, $L, expected, val)", atomicHandle, seg, atomicOffset).addStatement("return this").build());
                } else {
                    String aosOffset = aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_OFFSET";
                    String base = f.isStatic() ? (aosImplClassName.simpleName() + ".STATIC_SEGMENT.address()") : "this._address";
                    classBuilder.addMethod(MethodSpec.methodBuilder("cas" + capitalized).addModifiers(Modifier.PUBLIC).returns(interfaceType)
                            .addParameter(TypeName.get(f.type()), "expected").addParameter(TypeName.get(f.type()), "val")
                            .addStatement("$L.compareAndSet(com.github.goguma9071.jvmplus.memory.MemoryManager.EVERYTHING, $L + $L, expected, val)", atomicHandle, base, aosOffset).addStatement("return this").build());
                }
                
                if (!isSoA && !f.isStatic()) {
                    classBuilder.addMethod(MethodSpec.methodBuilder("cas_" + f.name()).addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                            .addParameter(MemorySegment.class, "seg").addParameter(TypeName.get(f.type()), "expected").addParameter(TypeName.get(f.type()), "val")
                            .addStatement("$L.compareAndSet(seg, $L, expected, val)", atomicHandle, aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_OFFSET").build());
                }

                if (f.type().getKind() == TypeKind.INT || f.type().getKind() == TypeKind.LONG) {
                    if (isSoA) {
                        String atomicOffset = "(long)currentIndex * " + f.size();
                        classBuilder.addMethod(MethodSpec.methodBuilder("addAndGet" + capitalized).addModifiers(Modifier.PUBLIC).returns(interfaceType)
                                .addParameter(TypeName.get(f.type()), "d").addStatement("$L.getAndAdd($L, $L, d)", atomicHandle, seg, atomicOffset).addStatement("return this").build());
                    } else {
                        String aosOffset = aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_OFFSET";
                        String base = f.isStatic() ? (aosImplClassName.simpleName() + ".STATIC_SEGMENT.address()") : "this._address";
                        classBuilder.addMethod(MethodSpec.methodBuilder("addAndGet" + capitalized).addModifiers(Modifier.PUBLIC).returns(interfaceType)
                                .addParameter(TypeName.get(f.type()), "d")
                                .addStatement("$L.getAndAdd(com.github.goguma9071.jvmplus.memory.MemoryManager.EVERYTHING, $L + $L, d)", atomicHandle, base, aosOffset).addStatement("return this").build());
                    }
                    if (!isSoA && !f.isStatic()) {
                        classBuilder.addMethod(MethodSpec.methodBuilder("addAndGet_" + f.name()).addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                                .addParameter(MemorySegment.class, "seg").addParameter(TypeName.get(f.type()), "d")
                                .addStatement("$L.getAndAdd(seg, $L, d)", atomicHandle, aosImplClassName.simpleName() + "." + f.name().toUpperCase() + "_OFFSET").build());
                    }
                }
            }

            if (isSoA && !f.isStatic() && !f.isArray() && !f.isPointer() && !f.isStruct() && !f.isString() && !f.isEnum() && (!f.isBitField() || f.name().equals(f.bitFieldBackingName())) && (f.type().getKind() == TypeKind.INT || f.type().getKind() == TypeKind.LONG || f.type().getKind() == TypeKind.FLOAT || f.type().getKind() == TypeKind.DOUBLE)) {
                classBuilder.addMethod(generateSoASimdSum(f.name(), f.type().getKind()));
                classBuilder.addMethod(generateSoASimdFill(f.name(), f.type().getKind()));
            }
        }

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

    private void generateGetterBody(MethodSpec.Builder mb, FieldModel f, String seg, String offset, String handle, boolean isSoA, ClassName aosImplClassName) {
        String baseAddr;
        if (seg.equals("seg")) {
            baseAddr = "seg.address() + " + offset;
        } else if (seg.equals("addr")) {
            baseAddr = "addr + " + offset;
        } else {
            baseAddr = isSoA ? (seg + ".address() + " + offset) : ("this._address + " + offset);
        }

        if (f.isString()) {
            mb.addStatement("byte[] b = com.github.goguma9071.jvmplus.memory.MemoryManager.EVERYTHING.asSlice($L, $L).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)", baseAddr, f.length());
            mb.addStatement("int l=0; while(l<b.length && b[l]!=0) l++; return new String(b, 0, l, java.nio.charset.StandardCharsets.UTF_8)");
        } else if (f.isRaw()) {
            mb.addStatement("final java.lang.foreign.MemorySegment _s = com.github.goguma9071.jvmplus.memory.MemoryManager.EVERYTHING.asSlice($L, $L)", baseAddr, f.length());
            mb.beginControlFlow("return new com.github.goguma9071.jvmplus.memory.RawBuffer()")
                    .addCode("@Override public java.lang.foreign.MemorySegment segment() { return _s; }\n")
                    .addCode("@Override public void free() { }\n")
                    .endControlFlow().addCode(";\n");
        } else if (f.isBitField()) {
            String accessParam = isSoA && !f.isStatic() ? "(long)currentIndex * 8" : (f.isStatic() ? offset : "0L");
            if (seg.equals("seg") || seg.equals("addr")) accessParam = offset;
            long mask = (f.bitCount() == 64) ? -1L : (1L << f.bitCount()) - 1;
            mb.addStatement("long val = ((Number) $L.get($L, $L)).longValue()", handle, seg.equals("addr") ? "com.github.goguma9071.jvmplus.memory.MemoryManager.EVERYTHING.asSlice(addr, LAYOUT.byteSize())" : seg, accessParam);
            mb.addStatement("return ($T) ((val >>> $L) & $LL)", f.type(), f.bitOffset(), mask);
        } else if (f.type().toString().equals("java.lang.Object")) {
            if (isSoA && !f.isStatic()) mb.addStatement("return com.github.goguma9071.jvmplus.memory.MemoryManager.getHandle($L.getAtIndex(java.lang.foreign.ValueLayout.JAVA_LONG, (long)currentIndex))", seg);
            else mb.addStatement("return com.github.goguma9071.jvmplus.memory.MemoryManager.getHandle(com.github.goguma9071.jvmplus.memory.MemoryManager.EVERYTHING.get(java.lang.foreign.ValueLayout.JAVA_LONG, $L))", baseAddr);
        } else if (f.isAtomic() || (!f.isString() && !f.isRaw() && !f.isArray() && !f.isEnum() && !f.isPointer() && !f.isStruct())) {
            if (isSoA && !f.isStatic()) mb.addStatement("return ($T) $L.getAtIndex($L, (long)currentIndex)", f.type(), seg, getSimpleLayoutCode(f.type(), f.alignment()));
            else {
                mb.addStatement("return ($T) com.github.goguma9071.jvmplus.memory.MemoryManager.EVERYTHING.get($L, $L)", f.type(), getSimpleLayoutCode(f.type(), f.alignment()), baseAddr);
            }
        } else if (f.isEnum()) {
            mb.addStatement("return $T.values()[com.github.goguma9071.jvmplus.memory.MemoryManager.EVERYTHING.get($L, $L)]", TypeName.get(f.type()), f.enumSize() == 1 ? "java.lang.foreign.ValueLayout.JAVA_BYTE" : "java.lang.foreign.ValueLayout.JAVA_INT", baseAddr);
        } else if (f.isStruct()) {
            if (isSoA && !f.isStatic()) mb.addStatement("this.$L_flyweight.rebase(this.$L_Segment.asSlice((long)currentIndex * $T.LAYOUT.byteSize(), $T.LAYOUT.byteSize())); return this.$L_flyweight", f.name(), f.name(), ClassName.bestGuess(f.nestedImplName()), ClassName.bestGuess(f.nestedImplName()), f.name());
            else if (seg.equals("seg") || seg.equals("addr")) {
                 mb.addStatement("return new $T(com.github.goguma9071.jvmplus.memory.MemoryManager.EVERYTHING.asSlice($L, $T.LAYOUT.byteSize()), null)", ClassName.bestGuess(f.nestedImplName()), baseAddr, ClassName.bestGuess(f.nestedImplName()));
            } else {
                mb.addStatement("this.$L_flyweight.rebase($L)", f.name(), baseAddr);
                mb.addStatement("return this.$L_flyweight", f.name());
            }
        } else if (f.isPointer()) {
            mb.addStatement("final long _fBaseAddr = $L", baseAddr);
            mb.addStatement("long _targetAddr = com.github.goguma9071.jvmplus.memory.MemoryManager.EVERYTHING.get(java.lang.foreign.ValueLayout.JAVA_LONG, _fBaseAddr)");
            ClassName targetTypeClassName = (ClassName) TypeName.get(((DeclaredType) f.type()).getTypeArguments().get(0));
            mb.beginControlFlow("return new Pointer<$T>()", targetTypeClassName)
                    .addCode("@Override public $T deref() { $T obj = com.github.goguma9071.jvmplus.memory.MemoryManager.createEmptyStruct($T.class); obj.rebase(java.lang.foreign.MemorySegment.ofAddress(_targetAddr).reinterpret($T.LAYOUT.byteSize(), java.lang.foreign.Arena.global(), s -> {})); return obj; }\n", targetTypeClassName, targetTypeClassName, targetTypeClassName, ClassName.bestGuess(f.nestedImplName()))
                    .addCode("@Override public void set($T v) { com.github.goguma9071.jvmplus.memory.MemoryManager.EVERYTHING.set(java.lang.foreign.ValueLayout.JAVA_LONG, _fBaseAddr, v.address()); }\n", targetTypeClassName)
                    .addCode("@Override public long address() { return _targetAddr; }\n")
                    .addCode("@Override public <U> Pointer<U> cast(Class<U> t) { return (Pointer<U>) com.github.goguma9071.jvmplus.memory.MemoryManager.createAddressPointer(_targetAddr, t); }\n")
                    .addCode("@Override public long distanceTo(Pointer<$T> other) { return (this.address() - other.address()) / $T.LAYOUT.byteSize(); }\n", targetTypeClassName, ClassName.bestGuess(f.nestedImplName()))
                    .addCode("@Override public Pointer<$T> offset(long c) { throw new UnsupportedOperationException(); }\n", targetTypeClassName)
                    .addCode("@Override public Class<$T> targetType() { return $T.class; }\n", targetTypeClassName, targetTypeClassName)
                    .addCode("@Override public Pointer<$T> auto() { return this; }\n", targetTypeClassName)
                    .addCode("@Override public Object invoke(java.lang.foreign.FunctionDescriptor d, Object... a) { return com.github.goguma9071.jvmplus.memory.MemoryManager.invoke(address(), d, a); }\n")
                    .addCode("@Override @Deprecated public void close() { }\n")
                    .addCode("@Override public void free() { }\n")
                    .endControlFlow().addCode(";\n");
        } else if (f.isArray()) {
            String layout = getSimpleLayoutCode(f.type(), f.alignment());
            mb.addStatement("if (idx < 0 || idx >= $L) throw new IndexOutOfBoundsException()", f.length());
            if (isSoA) mb.addStatement("return $L.getAtIndex($L, (long)currentIndex * $L + idx)", seg, layout, f.length());
            else mb.addStatement("return com.github.goguma9071.jvmplus.memory.MemoryManager.EVERYTHING.get($L, $L + (long)idx * $L.byteSize())", layout, baseAddr, layout);
        }
    }

    private void generateSetterBody(MethodSpec.Builder mb, FieldModel f, String seg, String offset, String handle, boolean isSoA, ClassName aosImplClassName, String paramName) {
        String baseAddr;
        if (seg.equals("seg")) {
            baseAddr = "seg.address() + " + offset;
        } else if (seg.equals("addr")) {
            baseAddr = "addr + " + offset;
        } else {
            baseAddr = isSoA ? (seg + ".address() + " + offset) : ("this._address + " + offset);
        }

        if (f.isString()) {
            mb.addStatement("byte[] b = $L.getBytes(java.nio.charset.StandardCharsets.UTF_8)", paramName).addStatement("int l = Math.min(b.length, $L)", f.length())
                    .addStatement("java.lang.foreign.MemorySegment.copy(java.lang.foreign.MemorySegment.ofArray(b), 0, com.github.goguma9071.jvmplus.memory.MemoryManager.EVERYTHING, $L, l)", baseAddr)
                    .addStatement("if(l < $L) com.github.goguma9071.jvmplus.memory.MemoryManager.EVERYTHING.asSlice($L + l, $L - l).fill((byte)0)", f.length(), baseAddr, f.length());
        } else if (f.isBitField()) {
            String accessParam = isSoA && !f.isStatic() ? "(long)currentIndex * 8" : (f.isStatic() ? offset : "0L");
            if (seg.equals("seg") || seg.equals("addr")) accessParam = offset;
            long mask = (f.bitCount() == 64) ? -1L : (1L << f.bitCount()) - 1;
            mb.addStatement("long old = ((Number) $L.get($L, $L)).longValue()", handle, seg.equals("addr") ? "com.github.goguma9071.jvmplus.memory.MemoryManager.EVERYTHING.asSlice(addr, LAYOUT.byteSize())" : seg, accessParam)
                    .addStatement("long updated = (old & ~($LL << $L)) | (($L & $LL) << $L)", mask, f.bitOffset(), paramName, mask, f.bitOffset())
                    .addStatement("$L.set($L, $L, $Lupdated)", handle, seg.equals("addr") ? "com.github.goguma9071.jvmplus.memory.MemoryManager.EVERYTHING.asSlice(addr, LAYOUT.byteSize())" : seg, accessParam, f.size() == 4 ? "(int)" : "");
        } else if (f.type().toString().equals("java.lang.Object")) {
            if (isSoA && !f.isStatic()) mb.addStatement("$L.setAtIndex(java.lang.foreign.ValueLayout.JAVA_LONG, (long)currentIndex, com.github.goguma9071.jvmplus.memory.MemoryManager.registerHandle($L))", seg, paramName);
            else mb.addStatement("com.github.goguma9071.jvmplus.memory.MemoryManager.EVERYTHING.set(java.lang.foreign.ValueLayout.JAVA_LONG, $L, com.github.goguma9071.jvmplus.memory.MemoryManager.registerHandle($L))", baseAddr, paramName);
        } else if (f.isAtomic() || (!f.isString() && !f.isRaw() && !f.isArray() && !f.isEnum() && !f.isPointer() && !f.isStruct())) {
            if (isSoA && !f.isStatic()) mb.addStatement("$L.setAtIndex($L, (long)currentIndex, $L)", seg, getSimpleLayoutCode(f.type(), f.alignment()), paramName);
            else {
                if (f.isAtomic()) mb.addStatement("$L.set(com.github.goguma9071.jvmplus.memory.MemoryManager.EVERYTHING.asSlice($L, $L), 0L, $L)", handle, baseAddr, f.size(), paramName);
                else mb.addStatement("com.github.goguma9071.jvmplus.memory.MemoryManager.EVERYTHING.set($L, $L, $L)", getSimpleLayoutCode(f.type(), f.alignment()), baseAddr, paramName);
            }
        } else if (f.isPointer()) {
            mb.addStatement("com.github.goguma9071.jvmplus.memory.MemoryManager.EVERYTHING.set(java.lang.foreign.ValueLayout.JAVA_LONG, $L, $L.address())", baseAddr, paramName);
        } else if (f.isArray()) {
            String layout = getSimpleLayoutCode(f.type(), f.alignment());
            mb.addStatement("if (idx < 0 || idx >= $L) throw new IndexOutOfBoundsException()", f.length());
            if (isSoA) mb.addStatement("$L.setAtIndex($L, (long)currentIndex * $L + idx, $L)", seg, layout, f.length(), paramName);
            else mb.addStatement("com.github.goguma9071.jvmplus.memory.MemoryManager.EVERYTHING.set($L, $L + (long)idx * $L.byteSize(), $L)", layout, baseAddr, layout, paramName);
        }
    }

    private void generateToString(TypeSpec.Builder classBuilder, StructModel model) {
        MethodSpec.Builder ts = MethodSpec.methodBuilder("toString").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(String.class).addStatement("$T sb = new $T()", StringBuilder.class, StringBuilder.class).addStatement("sb.append($S).append(\" {\")", model.interfaceName());
        for (FieldModel f : model.fields()) if (!f.isStatic()) ts.addStatement("sb.append(\"\\n  \").append($S).append(\": \").append($L())", f.name(), f.name());
        ts.addStatement("sb.append(\"\\n}\")").addStatement("return sb.toString()");
        classBuilder.addMethod(ts.build());
    }

    private MethodSpec generateSoASimdSum(String fieldName, TypeKind kind) {
        String capitalized = fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
        String speciesField = fieldName.toUpperCase() + "_SPECIES";
        Class<?> primitiveClass = switch(kind) { case DOUBLE -> double.class; case FLOAT -> float.class; case LONG -> long.class; case INT -> int.class; default -> throw new IllegalArgumentException(); };
        TypeName returnType = TypeName.get(primitiveClass);
        String vectorClass = switch(kind) { case DOUBLE -> "DoubleVector"; case FLOAT -> "FloatVector"; case LONG -> "LongVector"; case INT -> "IntVector"; default -> throw new IllegalArgumentException(); };
        return MethodSpec.methodBuilder("sum" + capitalized).addModifiers(Modifier.PUBLIC).returns(returnType)
                .addStatement("var acc = jdk.incubator.vector.$L.zero($L)", vectorClass, speciesField).addStatement("int i = 0").addStatement("int upperBound = $L.loopBound(capacity)", speciesField)
                .beginControlFlow("for (; i < upperBound; i += $L.length())", speciesField)
                .addStatement("var v = jdk.incubator.vector.$L.fromMemorySegment($L, this.$L_Segment, (long)i * $L, java.nio.ByteOrder.nativeOrder())", vectorClass, speciesField, fieldName, (kind == TypeKind.DOUBLE || kind == TypeKind.LONG) ? 8 : 4)
                .addStatement("acc = acc.add(v)").endControlFlow().addStatement("$T total = ($T) acc.reduceLanes(jdk.incubator.vector.VectorOperators.ADD)", returnType, returnType)
                .beginControlFlow("for (; i < capacity; i++)").addStatement("total += this.$L_Segment.getAtIndex(java.lang.foreign.ValueLayout.JAVA_$L, (long)i)", fieldName, kind.name()).endControlFlow().addStatement("return total").build();
    }

    private MethodSpec generateSoASimdFill(String fieldName, TypeKind kind) {
        String capitalized = fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
        String speciesField = fieldName.toUpperCase() + "_SPECIES";
        String vectorClass = switch(kind) { case DOUBLE -> "DoubleVector"; case FLOAT -> "FloatVector"; case LONG -> "LongVector"; case INT -> "IntVector"; default -> throw new IllegalArgumentException(); };
        Class<?> primitiveClass = switch(kind) { case DOUBLE -> double.class; case FLOAT -> float.class; case LONG -> long.class; case INT -> int.class; default -> throw new IllegalArgumentException(); };
        return MethodSpec.methodBuilder("fill" + capitalized).addModifiers(Modifier.PUBLIC).addParameter(primitiveClass, "val")
                .addStatement("var v = jdk.incubator.vector.$L.broadcast($L, val)", vectorClass, speciesField).addStatement("int i = 0").addStatement("int upperBound = $L.loopBound(capacity)", speciesField)
                .beginControlFlow("for (; i < upperBound; i += $L.length())", speciesField)
                .addStatement("v.intoMemorySegment(this.$L_Segment, (long)i * $L, java.nio.ByteOrder.nativeOrder())", fieldName, (kind == TypeKind.DOUBLE || kind == TypeKind.LONG) ? 8 : 4)
                .endControlFlow().beginControlFlow("for (; i < capacity; i++)").addStatement("this.$L_Segment.setAtIndex(java.lang.foreign.ValueLayout.JAVA_$L, (long)i, val)", fieldName, kind.name()).endControlFlow().build();
    }

    private String getLayoutCode(FieldModel f, boolean isRawValue) {
        if (!isRawValue && (f.isString() || f.isRaw())) return "java.lang.foreign.MemoryLayout.sequenceLayout(" + f.length() + ", java.lang.foreign.ValueLayout.JAVA_BYTE)";
        if (!isRawValue && f.isArray()) return "java.lang.foreign.MemoryLayout.sequenceLayout(" + f.length() + ", " + getSimpleLayoutCode(f.type(), f.alignment()) + ")";
        if (f.isStruct()) return ClassName.bestGuess(f.nestedImplName()) + ".LAYOUT";
        return getSimpleLayoutCode(f.type(), f.alignment());
    }

    private String getSimpleLayoutCode(TypeMirror t, long alignment) {
        String base = switch (t.getKind()) { case INT -> "java.lang.foreign.ValueLayout.JAVA_INT"; case LONG -> "java.lang.foreign.ValueLayout.JAVA_LONG"; case DOUBLE -> "java.lang.foreign.ValueLayout.JAVA_DOUBLE"; case FLOAT -> "java.lang.foreign.ValueLayout.JAVA_FLOAT"; case BYTE -> "java.lang.foreign.ValueLayout.JAVA_BYTE"; default -> "java.lang.foreign.ValueLayout.ADDRESS"; };
        if (alignment > 0 && (alignment & (alignment - 1)) == 0) return base + ".withByteAlignment(" + alignment + ")";
        return base;
    }

    private String getDescriptorCode(ExecutableElement nc) {
        String ret = nc.getReturnType().getKind() == TypeKind.VOID ? "ofVoid" : "of";
        String retLayout = (nc.getReturnType().getKind() == TypeKind.VOID) ? "" : getLayoutForType(nc.getReturnType());
        String args = nc.getParameters().stream().map(p -> getLayoutForType(p.asType())).collect(Collectors.joining(", "));
        return "java.lang.foreign.FunctionDescriptor." + ret + "(" + (retLayout.isEmpty() ? "" : retLayout + (args.isEmpty() ? "" : ", ")) + args + ")";
    }

    private String getLayoutForType(TypeMirror t) {
        return switch (t.getKind()) { case INT -> "java.lang.foreign.ValueLayout.JAVA_INT"; case LONG -> "java.lang.foreign.ValueLayout.JAVA_LONG"; case DOUBLE -> "java.lang.foreign.ValueLayout.JAVA_DOUBLE"; case FLOAT -> "java.lang.foreign.ValueLayout.JAVA_FLOAT"; default -> "java.lang.foreign.ValueLayout.ADDRESS"; };
    }

    private String getTypeName(FieldModel f) {
        if (f.isString()) return "char[" + f.length() + "]";
        if (f.isRaw()) return "byte[" + f.length() + "]";
        if (f.isArray()) return f.type().toString() + "[" + f.length() + "]";
        return f.type().toString();
    }

    private long getAlignment(FieldModel f) { return f.alignment() > 0 ? f.alignment() : 1; }
}
