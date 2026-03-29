package com.github.goguma9071.jvmplus.processor;

import com.github.goguma9071.jvmplus.memory.Struct;
import com.github.goguma9071.jvmplus.memory.MemoryPool;
import com.github.goguma9071.jvmplus.memory.Pointer;
import com.github.goguma9071.jvmplus.processor.model.*;
import com.squareup.javapoet.*;
import javax.annotation.processing.Filer;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

public class JPCGenerator {
    private final Filer filer;
    private final Types typeUtils;

    public JPCGenerator(Filer filer, Types typeUtils) {
        this.filer = filer;
        this.typeUtils = typeUtils;
    }

    public void generate(StructModel model) throws IOException {
        generateAoS(model);
        generateSoA(model);
    }

    private ClassName getInterfaceType(StructModel model) {
        String[] parts = model.interfaceName().split("\\.");
        if (parts.length > 1) {
            return ClassName.get(model.packageName(), parts[0], java.util.Arrays.copyOfRange(parts, 1, parts.length));
        }
        return ClassName.get(model.packageName(), model.interfaceName());
    }

    private void generateAoS(StructModel model) throws IOException {
        ClassName interfaceType = getInterfaceType(model);
        String implName = model.implBaseName() + "Impl";
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(implName)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(interfaceType);

        classBuilder.addField(MemorySegment.class, "segment", Modifier.PRIVATE)
                    .addField(MemoryPool.class, "pool", Modifier.PRIVATE, Modifier.FINAL);

        classBuilder.addMethod(MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(MemorySegment.class, "segment")
            .addParameter(MemoryPool.class, "pool")
            .addStatement("this.segment = segment")
            .addStatement("this.pool = pool")
            .build());

        classBuilder.addField(java.lang.foreign.GroupLayout.class, "LAYOUT", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
        classBuilder.addField(String.class, "LAYOUT_MAP", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
        classBuilder.addField(MemorySegment.class, "STATIC_SEGMENT", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);

        CodeBlock.Builder staticInit = CodeBlock.builder()
            .addStatement("java.util.List<java.lang.foreign.MemoryLayout> elements = new java.util.ArrayList<>()");

        TypeSpec.Builder fieldsBuilder = TypeSpec.classBuilder("Fields").addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);

        StringBuilder mapBuilder = new StringBuilder();
        mapBuilder.append(String.format("[Struct Layout: %s]\\n\\n", model.interfaceName()));
        long lastEnd = 0;

        for (FieldModel f : model.fields()) {
            if (f.isStatic()) continue;
            String offsetName = f.name().toUpperCase() + "_OFFSET";
            classBuilder.addField(long.class, offsetName, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
            fieldsBuilder.addField(FieldSpec.builder(long.class, offsetName, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).initializer("$T.$L", ClassName.get(model.packageName(), implName), offsetName).build());

            if (!f.isBitField() || f.name().equals(f.bitFieldBackingName())) {
                staticInit.addStatement("elements.add($L.withName($S))", getLayoutCode(f, false), f.isBitField() ? f.bitFieldBackingName() : f.name());
            }
            staticInit.addStatement("$L = $L", offsetName, f.calculatedOffset());

            if (f.calculatedOffset() > lastEnd) {
                mapBuilder.append(String.format("Offset %-4d: [padding]     (%d bytes)\\n", lastEnd, f.calculatedOffset() - lastEnd));
            }
            String displayType = f.isString() ? "char[" + f.length() + "]" : f.isRaw() ? "byte[" + f.length() + "]" : f.isArray() ? f.type().toString() + "[" + f.length() + "]" : f.type().toString();
            if (f.isBitField()) displayType += " :" + f.bitCount();
            
            mapBuilder.append(String.format("Offset %-4d: %-13s %-10s (%d bytes)%s\\n", 
                f.calculatedOffset(), displayType, f.name(), f.size(), f.isAtomic() ? " [@Atomic]" : ""));
            lastEnd = f.calculatedOffset() + f.size();
            
            if (!f.isBitField() || f.name().equals(f.bitFieldBackingName())) {
                if (f.isAtomic() || (!f.isString() && !f.isRaw() && !f.isArray() && !f.isEnum() && !f.isPointer() && !f.isStruct())) {
                    String handleName = (f.isBitField() ? f.bitFieldBackingName() : f.name()).toUpperCase() + "_HANDLE";
                    if (classBuilder.fieldSpecs.stream().noneMatch(fs -> fs.name.equals(handleName))) {
                        classBuilder.addField(VarHandle.class, handleName, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
                        if (f.isAtomic()) {
                            long align = f.type().getKind() == TypeKind.LONG || f.type().getKind() == TypeKind.DOUBLE ? 8 : 4;
                            staticInit.addStatement("$L = java.lang.foreign.ValueLayout.JAVA_$L.withByteAlignment($L).varHandle()", handleName, f.type().getKind().name(), align);
                        } else {
                            staticInit.addStatement("$L = java.lang.foreign.MemoryLayout.structLayout(elements.toArray(new java.lang.foreign.MemoryLayout[0])).varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement($S))", handleName, f.isBitField() ? f.bitFieldBackingName() : f.name());
                        }
                    }
                }
            }
        }

        staticInit.addStatement("LAYOUT = java.lang.foreign.MemoryLayout.structLayout(elements.toArray(new java.lang.foreign.MemoryLayout[0]))");
        staticInit.addStatement("LAYOUT_MAP = $S + LAYOUT.byteSize() + \" bytes\\n\"", mapBuilder.toString() + "\\nTotal Size: ");

        if (model.hasStatic()) {
            staticInit.addStatement("java.util.List<java.lang.foreign.MemoryLayout> staticElements = new java.util.ArrayList<>()");
            for(FieldModel f : model.fields()) if(f.isStatic()) staticInit.addStatement("staticElements.add($L.withName($S))", getLayoutCode(f, false), f.name());
            staticInit.addStatement("java.lang.foreign.GroupLayout staticLayout = java.lang.foreign.MemoryLayout.structLayout(staticElements.toArray(new java.lang.foreign.MemoryLayout[0]))");
            staticInit.addStatement("STATIC_SEGMENT = java.lang.foreign.Arena.ofShared().allocate(staticLayout.byteSize(), staticLayout.byteAlignment())");
        } else {
            staticInit.addStatement("STATIC_SEGMENT = java.lang.foreign.MemorySegment.NULL");
        }

        for (ExecutableElement nc : model.nativeCalls()) {
            String fieldName = "NC_" + nc.getSimpleName().toString().toUpperCase() + "_HANDLE";
            classBuilder.addField(MethodHandle.class, fieldName, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
            Struct.NativeCall ann = nc.getAnnotation(Struct.NativeCall.class);
            String libToUse = ann.lib().isEmpty() ? model.defaultLib() : ann.lib();
            
            staticInit.beginControlFlow("");
            staticInit.addStatement("java.lang.foreign.Linker linker = java.lang.foreign.Linker.nativeLinker()");
            staticInit.addStatement("java.lang.foreign.SymbolLookup lookup = java.lang.foreign.SymbolLookup.libraryLookup($S, java.lang.foreign.Arena.global())", libToUse);
            staticInit.addStatement("java.lang.foreign.MemorySegment addr = lookup.find($S).orElseThrow()", ann.name().isEmpty() ? nc.getSimpleName() : ann.name());
            staticInit.addStatement("$L = linker.downcallHandle(addr, $L)", fieldName, getDescriptorCode(nc));
            staticInit.endControlFlow();
        }

        classBuilder.addStaticBlock(staticInit.build());
        classBuilder.addType(fieldsBuilder.build());

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
                    .addField(java.lang.foreign.Arena.class, "arena", Modifier.PRIVATE, Modifier.FINAL);

        MethodSpec.Builder constr = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(int.class, "capacity")
            .addStatement("this.capacity = capacity")
            .addStatement("this.arena = java.lang.foreign.Arena.ofShared()");

        CodeBlock.Builder staticInit = CodeBlock.builder();
        classBuilder.addField(MemorySegment.class, "STATIC_SEGMENT", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
        staticInit.addStatement("STATIC_SEGMENT = $T.STATIC_SEGMENT", ClassName.get(model.packageName(), aosName));

        for (FieldModel f : model.fields()) {
            if (f.isStatic()) continue;
            if (f.isBitField() && !f.name().equals(f.bitFieldBackingName())) continue;

            String segName = f.name() + "_Segment";
            classBuilder.addField(MemorySegment.class, segName, Modifier.PUBLIC, Modifier.FINAL);
            if (f.type().getKind().isPrimitive() && !f.isArray() && !f.isPointer()) {
                String layout = getLayoutCode(f, true);
                classBuilder.addField(VarHandle.class, f.name().toUpperCase() + "_HANDLE", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
                long align = f.isAtomic() ? f.type().getKind().toString().contains("LONG") || f.type().getKind().toString().contains("DOUBLE") ? 8 : 4 : 1;
                constr.addStatement("this.$L = arena.allocate($L.byteSize() * (long)capacity, $L)", segName, layout, align == 1 ? layout + ".byteAlignment()" : align);
                staticInit.addStatement("$L_HANDLE = $L.arrayElementVarHandle()", f.name().toUpperCase(), layout);
                if (f.type().getKind() == TypeKind.DOUBLE) classBuilder.addMethod(generateSoASimdSum(f.name()));
            } else if (f.isString()) {
                constr.addStatement("this.$L = arena.allocate((long)$L * capacity, 1)", segName, f.length());
            } else if (f.isArray()) {
                String layout = getLayoutCode(f, true);
                constr.addStatement("this.$L = arena.allocate($L.byteSize() * $L * (long)capacity, $L.byteAlignment())", segName, layout, f.length(), layout);
            } else if (f.isStruct()) {
                constr.addStatement("this.$L = arena.allocate($T.LAYOUT.byteSize() * (long)capacity, $T.LAYOUT.byteAlignment())", segName, ClassName.bestGuess(f.nestedImplName()), ClassName.bestGuess(f.nestedImplName()));
                classBuilder.addField(TypeName.get(f.type()), f.name() + "_flyweight", Modifier.PRIVATE, Modifier.FINAL);
                constr.addStatement("this.$L_flyweight = ($T) com.github.goguma9071.jvmplus.memory.MemoryManager.createEmptyStruct($T.class)", f.name(), TypeName.get(f.type()), TypeName.get(f.type()));
            } else {
                constr.addStatement("this.$L = arena.allocate(8 * (long)capacity, 8)", segName);
            }
        }

        classBuilder.addStaticBlock(staticInit.build()).addMethod(constr.build());
        implementCommonSoAMethods(classBuilder, model, interfaceType);
        implementFieldMethods(classBuilder, model, true, interfaceType);

        JavaFile.builder(model.packageName(), classBuilder.build()).build().writeTo(filer);
    }

    private void implementCommonAoSMethods(TypeSpec.Builder classBuilder, StructModel model, ClassName interfaceType) {
        classBuilder.addMethod(MethodSpec.methodBuilder("address").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(long.class).addStatement("return segment.address()").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("segment").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(MemorySegment.class).addStatement("return segment").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("rebase").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addParameter(MemorySegment.class, "s").addStatement("this.segment = s").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("close").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addStatement("com.github.goguma9071.jvmplus.memory.MemoryManager.free(this)").build());
        
        MethodSpec.Builder asPtr = MethodSpec.methodBuilder("asPointer").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addTypeVariable(TypeVariableName.get("T", Struct.class))
            .returns(ParameterizedTypeName.get(ClassName.get(Pointer.class), TypeVariableName.get("T")))
            .addStatement("final java.lang.foreign.MemorySegment _s = this.segment")
            .addCode("return (Pointer<T>) new Pointer<$T>() {\n", interfaceType)
            .addCode("  @Override public $T deref() { $T obj = com.github.goguma9071.jvmplus.memory.MemoryManager.createEmptyStruct($T.class); obj.rebase(_s); return obj; }\n", interfaceType, interfaceType, interfaceType)
            .addCode("  @Override public void set($T v) { throw new UnsupportedOperationException(); }\n", interfaceType)
            .addCode("  @Override public long address() { return _s.address(); }\n")
            .addCode("  @Override public <U> Pointer<U> cast(Class<U> targetType) { return (Pointer<U>) com.github.goguma9071.jvmplus.memory.MemoryManager.createAddressPointer(_s.address(), targetType); }\n")
            .addCode("  @Override public long distanceTo(Pointer<$T> other) { return (this.address() - other.address()) / LAYOUT.byteSize(); }\n", interfaceType)
            .addCode("  @Override public Pointer<$T> offset(long count) {\n", interfaceType)
            .addCode("    long newAddr = _s.address() + count * LAYOUT.byteSize();\n")
            .addCode("    return new Pointer<$T>() {\n", interfaceType)
            .addCode("      @Override public $T deref() { $T obj = com.github.goguma9071.jvmplus.memory.MemoryManager.createEmptyStruct($T.class); obj.rebase(java.lang.foreign.MemorySegment.ofAddress(newAddr).reinterpret(LAYOUT.byteSize())); return obj; }\n", interfaceType, interfaceType, interfaceType)
            .addCode("      @Override public void set($T v) { throw new UnsupportedOperationException(); }\n", interfaceType)
            .addCode("      @Override public long address() { return newAddr; }\n")
            .addCode("      @Override public <U> Pointer<U> cast(Class<U> t) { return (Pointer<U>) com.github.goguma9071.jvmplus.memory.MemoryManager.createAddressPointer(newAddr, t); }\n")
            .addCode("      @Override public long distanceTo(Pointer<$T> o) { return (this.address() - o.address()) / LAYOUT.byteSize(); }\n", interfaceType)
            .addCode("      @Override public Pointer<$T> offset(long c) { throw new UnsupportedOperationException(); }\n", interfaceType)
            .addCode("      @Override public Class<$T> targetType() { return $T.class; }\n", interfaceType, interfaceType)
            .addCode("      @Override public Pointer<$T> auto() { return this; }\n", interfaceType)
            .addCode("      @Override public Object invoke(java.lang.foreign.FunctionDescriptor d, Object... a) { return com.github.goguma9071.jvmplus.memory.MemoryManager.invoke(address(), d, a); }\n")
            .addCode("      @Override public void close() { }\n")
            .addCode("    };\n")
            .addCode("  }\n")
            .addCode("  @Override public Class<$T> targetType() { return $T.class; }\n", interfaceType, interfaceType)
            .addCode("  @Override public Pointer<$T> auto() { return this; }\n", interfaceType)
            .addCode("  @Override public Object invoke(java.lang.foreign.FunctionDescriptor d, Object... a) { return com.github.goguma9071.jvmplus.memory.MemoryManager.invoke(address(), d, a); }\n")
            .addCode("  @Override public void close() { com.github.goguma9071.jvmplus.memory.MemoryManager.free(this.deref()); }\n")
            .addCode("};\n");
        classBuilder.addMethod(asPtr.build());
        generateToString(classBuilder, model);
    }

    private void implementCommonSoAMethods(TypeSpec.Builder classBuilder, StructModel model, ClassName interfaceType) {
        classBuilder.addMethod(MethodSpec.methodBuilder("address").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(long.class).addStatement("return 0").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("segment").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(MemorySegment.class).addStatement("return null").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("rebase").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addParameter(MemorySegment.class, "s").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("close").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addStatement("arena.close()").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("get").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addParameter(int.class, "index").returns(interfaceType).addStatement("this.currentIndex = index").addStatement("return this").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("size").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(int.class).addStatement("return capacity").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("iterator").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(ParameterizedTypeName.get(ClassName.get(Iterator.class), interfaceType))
            .addCode("return new java.util.Iterator<>() { private int current = 0; @Override public boolean hasNext() { return current < capacity; } @Override public $T next() { return get(current++); } }; ", interfaceType).build());
        
        MethodSpec.Builder sumDouble = MethodSpec.methodBuilder("sumDouble").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addParameter(String.class, "f").returns(double.class).beginControlFlow("switch(f)");
        for (FieldModel f : model.fields()) if (f.type().getKind() == TypeKind.DOUBLE && !f.isArray() && !f.isStatic()) sumDouble.addCode("case $S: return sum$L(); ", f.name(), f.name().substring(0, 1).toUpperCase() + f.name().substring(1));
        sumDouble.addStatement("default: throw new UnsupportedOperationException()");
        sumDouble.endControlFlow();
        classBuilder.addMethod(sumDouble.build());
        
        classBuilder.addMethod(MethodSpec.methodBuilder("asPointer").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addTypeVariable(TypeVariableName.get("T", Struct.class)).returns(ParameterizedTypeName.get(ClassName.get(Pointer.class), TypeVariableName.get("T"))).addStatement("throw new UnsupportedOperationException()").build());
    }

    private void implementFieldMethods(TypeSpec.Builder classBuilder, StructModel model, boolean isSoA, ClassName interfaceType) {
        String aosImpl = model.packageName() + "." + model.implBaseName() + "Impl";
        for (FieldModel f : model.fields()) {
            String seg = f.isStatic() ? "STATIC_SEGMENT" : (isSoA ? "this." + (f.isBitField() ? f.bitFieldBackingName() : f.name()) + "_Segment" : "this.segment");
            String offset = f.isStatic() ? aosImpl + "." + f.name().toUpperCase() + "_OFFSET" : (isSoA ? "(long)currentIndex" : "0L");
            String handle = aosImpl + "." + (f.isBitField() ? f.bitFieldBackingName() : f.name()).toUpperCase() + "_HANDLE";

            // Getter
            MethodSpec.Builder getter = MethodSpec.overriding(f.getter());
            if (f.isString()) {
                getter.addStatement("byte[] b = $L.asSlice($L * $L, $L).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)", seg, offset, f.length(), f.length());
                getter.addStatement("int l=0; while(l<b.length && b[l]!=0) l++; return new String(b, 0, l, java.nio.charset.StandardCharsets.UTF_8)");
            } else if (f.isRaw()) {
                getter.addStatement("final java.lang.foreign.MemorySegment _s = $L.asSlice($L * $L, $L)", seg, offset, f.length(), f.length());
                getter.beginControlFlow("return new com.github.goguma9071.jvmplus.memory.RawBuffer()")
                      .addCode("  @Override public java.lang.foreign.MemorySegment segment() { return _s; }\n")
                      .endControlFlow().addCode(";\n");
            } else if (f.isBitField()) {
                String accessParam = isSoA && !f.isStatic() ? "(long)currentIndex" : "0L";
                long mask = (f.bitCount() == 64) ? -1L : (1L << f.bitCount()) - 1;
                getter.addStatement("long val = ((Number) $L.get($L, $L)).longValue()", handle, seg, accessParam);
                getter.addStatement("return ($T) ((val >>> $L) & $LL)", f.type(), f.bitOffset(), mask);
            } else if (f.isAtomic() || (!f.isString() && !f.isRaw() && !f.isArray() && !f.isEnum() && !f.isPointer() && !f.isStruct())) {
                String accessParam = isSoA && !f.isStatic() ? "(long)currentIndex" : "0L";
                getter.addStatement("return ($T) $L.get($L, $L)", f.type(), handle, seg, accessParam);
            } else if (f.isEnum()) {
                String layout = f.enumSize() == 1 ? "java.lang.foreign.ValueLayout.JAVA_BYTE" : "java.lang.foreign.ValueLayout.JAVA_INT";
                getter.addStatement("return $T.values()[$L.get($L, $L)]", TypeName.get(f.type()), seg, layout, isSoA ? offset : aosImpl + "." + f.name().toUpperCase() + "_OFFSET");
            } else if (f.isStruct()) {
                if (isSoA && !f.isStatic()) getter.addStatement("this.$L_flyweight.rebase(this.$L_Segment.asSlice((long)currentIndex * $T.LAYOUT.byteSize(), $T.LAYOUT.byteSize())); return this.$L_flyweight", f.name(), f.name(), ClassName.bestGuess(f.nestedImplName()), ClassName.bestGuess(f.nestedImplName()), f.name());
                else getter.addStatement("return new $T($L.asSlice($L, $T.LAYOUT.byteSize()), null)", ClassName.bestGuess(f.nestedImplName()), seg, isSoA ? offset : aosImpl + "." + f.name().toUpperCase() + "_OFFSET", ClassName.bestGuess(f.nestedImplName()));
            } else if (f.isPointer()) {
                TypeMirror targetType = ((DeclaredType) f.type()).getTypeArguments().get(0);
                String targetImpl = f.nestedImplName();
                getter.addStatement("long addr = $L.get(java.lang.foreign.ValueLayout.JAVA_LONG, $L)", seg, isSoA ? offset + "* 8" : aosImpl + "." + f.name().toUpperCase() + "_OFFSET");
                getter.addCode("return new Pointer<$T>() {\n", TypeName.get(targetType))
                      .addCode("  @Override public $T deref() { $T obj = com.github.goguma9071.jvmplus.memory.MemoryManager.createEmptyStruct($T.class); obj.rebase(java.lang.foreign.MemorySegment.ofAddress(addr).reinterpret($T.LAYOUT.byteSize())); return obj; }\n", TypeName.get(targetType), TypeName.get(targetType), TypeName.get(targetType), ClassName.bestGuess(targetImpl))
                      .addCode("  @Override public void set($T v) { $L.set(java.lang.foreign.ValueLayout.JAVA_LONG, $L, v.address()); }\n", TypeName.get(targetType), seg, isSoA ? offset + "* 8" : aosImpl + "." + f.name().toUpperCase() + "_OFFSET")
                      .addCode("  @Override public long address() { return addr; }\n")
                      .addCode("  @Override public <U> Pointer<U> cast(Class<U> t) { return (Pointer<U>) com.github.goguma9071.jvmplus.memory.MemoryManager.createAddressPointer(addr, t); }\n")
                      .addCode("  @Override public long distanceTo(Pointer<$T> other) { return (this.address() - other.address()) / $T.LAYOUT.byteSize(); }\n", TypeName.get(targetType), ClassName.bestGuess(targetImpl))
                      .addCode("  @Override public Pointer<$T> offset(long c) { throw new UnsupportedOperationException(); }\n", TypeName.get(targetType))
                      .addCode("  @Override public Class<$T> targetType() { return $T.class; }\n", TypeName.get(targetType), TypeName.get(targetType))
                      .addCode("  @Override public Pointer<$T> auto() { return this; }\n", TypeName.get(targetType))
                      .addCode("  @Override public Object invoke(java.lang.foreign.FunctionDescriptor d, Object... a) { return com.github.goguma9071.jvmplus.memory.MemoryManager.invoke(address(), d, a); }\n")
                      .addCode("  @Override public void close() { }\n")
                      .addCode("};\n");
            } else if (f.isArray()) {
                String layout = getSimpleLayoutCode(f.type());
                String baseOffset = isSoA ? "0" : aosImpl + "." + f.name().toUpperCase() + "_OFFSET";
                getter.addParameter(int.class, "idx").addStatement("if (idx < 0 || idx >= $L) throw new IndexOutOfBoundsException()", f.length());
                if (isSoA) getter.addStatement("return $L.getAtIndex($L, (long)currentIndex * $L + idx)", seg, layout, f.length());
                else getter.addStatement("return $L.get($L, $L + (long)idx * $L.byteSize())", seg, layout, baseOffset, layout);
            }
            classBuilder.addMethod(getter.build());

            // Setter (Fluent API: returns interfaceType)
            if (f.setter() != null) {
                MethodSpec.Builder setter = MethodSpec.methodBuilder(f.setter().getSimpleName().toString())
                    .addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(interfaceType);
                
                f.setter().getParameters().forEach(p -> setter.addParameter(TypeName.get(p.asType()), p.getSimpleName().toString()));
                String param = f.setter().getParameters().get(0).getSimpleName().toString();

                if (f.isString()) {
                    setter.addStatement("byte[] b = $L.getBytes(java.nio.charset.StandardCharsets.UTF_8)", param);
                    setter.addStatement("int cl = Math.min(b.length, $L)", f.length());
                    setter.addStatement("java.lang.foreign.MemorySegment.copy(java.lang.foreign.MemorySegment.ofArray(b), 0, $L, $L * $L, cl)", seg, offset, f.length());
                } else if (f.isBitField()) {
                    String accessParam = isSoA && !f.isStatic() ? "(long)currentIndex" : "0L";
                    long mask = (f.bitCount() == 64) ? -1L : (1L << f.bitCount()) - 1;
                    setter.addStatement("long original = ((Number) $L.get($L, $L)).longValue()", handle, seg, accessParam);
                    setter.addStatement("long cleared = original & ~($LL << $L)", mask, f.bitOffset());
                    setter.addStatement("long updated = cleared | (((long)$L & $LL) << $L)", param, mask, f.bitOffset());
                    setter.addStatement("$L.set($L, $L, ($T)updated)", handle, seg, accessParam, f.type());
                } else if (f.isAtomic() || (!f.isString() && !f.isRaw() && !f.isArray() && !f.isEnum() && !f.isPointer() && !f.isStruct())) {
                    String accessParam = isSoA && !f.isStatic() ? "(long)currentIndex" : "0L";
                    setter.addStatement("$L.set($L, $L, $L)", handle, seg, accessParam, param);
                } else if (f.isEnum()) {
                    String layout = f.enumSize() == 1 ? "java.lang.foreign.ValueLayout.JAVA_BYTE" : "java.lang.foreign.ValueLayout.JAVA_INT";
                    setter.addStatement("$L.set($L, $L, (byte)$L.ordinal())", seg, layout, isSoA ? offset : aosImpl + "." + f.name().toUpperCase() + "_OFFSET", param);
                } else if (f.isArray()) {
                    String layout = getSimpleLayoutCode(f.type());
                    String baseOffset = isSoA ? "0" : aosImpl + "." + f.name().toUpperCase() + "_OFFSET";
                    String valParam = f.setter().getParameters().get(1).getSimpleName().toString();
                    setter.addStatement("if (idx < 0 || idx >= $L) throw new IndexOutOfBoundsException()", f.length());
                    if (isSoA) setter.addStatement("$L.setAtIndex($L, (long)currentIndex * $L + idx, $L)", seg, layout, f.length(), valParam);
                    else setter.addStatement("$L.set($L, $L + (long)idx * $L.byteSize(), $L)", seg, layout, baseOffset, layout, valParam);
                }
                setter.addStatement("return this");
                classBuilder.addMethod(setter.build());
            }

            // Atomic methods (Fluent API: returns interfaceType)
            if (f.isAtomic()) {
                String capitalized = f.name().substring(0, 1).toUpperCase() + f.name().substring(1);
                String accessParam = isSoA && !f.isStatic() ? "(long)currentIndex" : "0L";
                
                classBuilder.addMethod(MethodSpec.methodBuilder("cas" + capitalized).addModifiers(Modifier.PUBLIC).returns(boolean.class)
                    .addParameter(TypeName.get(f.type()), "e").addParameter(TypeName.get(f.type()), "n")
                    .addStatement("return $L.compareAndSet($L, $L, e, n)", handle, seg, accessParam).build());

                if (f.type().getKind() == TypeKind.INT || f.type().getKind() == TypeKind.LONG) {
                    classBuilder.addMethod(MethodSpec.methodBuilder("addAndGet" + capitalized).addModifiers(Modifier.PUBLIC).returns(interfaceType)
                        .addParameter(TypeName.get(f.type()), "d")
                        .addStatement("$L.getAndAdd($L, $L, d)", handle, seg, accessParam)
                        .addStatement("return this").build());
                }
            }
        }

        // Native Call methods
        for (ExecutableElement nc : model.nativeCalls()) {
            MethodSpec.Builder mb = MethodSpec.overriding(nc);
            String handleName = aosImpl + ".NC_" + nc.getSimpleName().toString().toUpperCase() + "_HANDLE";
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
        return MethodSpec.methodBuilder("sum" + capitalized).addModifiers(Modifier.PRIVATE).returns(double.class)
                .addStatement("jdk.incubator.vector.VectorSpecies<Double> species = jdk.incubator.vector.DoubleVector.SPECIES_PREFERRED")
                .addStatement("jdk.incubator.vector.DoubleVector acc = jdk.incubator.vector.DoubleVector.zero(species)")
                .addStatement("int i = 0").addStatement("int upperBound = species.loopBound(capacity)")
                .beginControlFlow("for (; i < upperBound; i += species.length())")
                .addStatement("jdk.incubator.vector.DoubleVector v = jdk.incubator.vector.DoubleVector.fromMemorySegment(species, this.$L_Segment, (long)i * 8, java.nio.ByteOrder.nativeOrder())", fieldName)
                .addStatement("acc = acc.add(v)")
                .endControlFlow()
                .addStatement("double total = acc.reduceLanes(jdk.incubator.vector.VectorOperators.ADD)")
                .beginControlFlow("for (; i < capacity; i++)")
                .addStatement("total += (double)this.$L_Segment.getAtIndex(java.lang.foreign.ValueLayout.JAVA_DOUBLE, (long)i)", fieldName)
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
            case INT -> "java.lang.foreign.ValueLayout.JAVA_INT";
            case LONG -> "java.lang.foreign.ValueLayout.JAVA_LONG";
            case DOUBLE -> "java.lang.foreign.ValueLayout.JAVA_DOUBLE";
            case FLOAT -> "java.lang.foreign.ValueLayout.JAVA_FLOAT";
            case BYTE -> "java.lang.foreign.ValueLayout.JAVA_BYTE";
            default -> "java.lang.foreign.ValueLayout.ADDRESS";
        };
    }

    private String getDescriptorCode(ExecutableElement nc) {
        String ret = nc.getReturnType().getKind() == TypeKind.VOID ? "ofVoid" : "of";
        String retLayout = nc.getReturnType().getKind() == TypeKind.VOID ? "" : getLayoutForType(nc.getReturnType());
        String args = nc.getParameters().stream().map(p -> getLayoutForType(p.asType())).collect(Collectors.joining(", "));
        return "java.lang.foreign.FunctionDescriptor." + ret + "(" + retLayout + (retLayout.isEmpty() || args.isEmpty() ? "" : ", ") + args + ")";
    }

    private String getLayoutForType(TypeMirror t) {
        return switch (t.getKind()) {
            case INT -> "java.lang.foreign.ValueLayout.JAVA_INT";
            case LONG -> "java.lang.foreign.ValueLayout.JAVA_LONG";
            case DOUBLE -> "java.lang.foreign.ValueLayout.JAVA_DOUBLE";
            case FLOAT -> "java.lang.foreign.ValueLayout.JAVA_FLOAT";
            default -> "java.lang.foreign.ValueLayout.ADDRESS";
        };
    }
}
