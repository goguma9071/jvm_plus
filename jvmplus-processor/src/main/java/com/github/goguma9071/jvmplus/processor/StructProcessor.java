package com.github.goguma9071.jvmplus.processor;

import com.github.goguma9071.jvmplus.memory.Struct;
import com.github.goguma9071.jvmplus.memory.Pointer;
import com.google.auto.service.AutoService;
import com.squareup.javapoet.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.util.*;
import java.util.stream.Collectors;

@AutoService(Processor.class)
@SupportedAnnotationTypes("com.github.goguma9071.jvmplus.memory.Struct.Type")
@SupportedSourceVersion(SourceVersion.RELEASE_22)
public class StructProcessor extends AbstractProcessor {

    private TypeMirror structType;
    private TypeMirror stringType;
    private TypeMirror pointerType;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        structType = processingEnv.getElementUtils().getTypeElement("com.github.goguma9071.jvmplus.memory.Struct").asType();
        stringType = processingEnv.getElementUtils().getTypeElement("java.lang.String").asType();
        pointerType = processingEnv.getElementUtils().getTypeElement("com.github.goguma9071.jvmplus.memory.Pointer").asType();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(Struct.Type.class)) {
            if (element.getKind() != ElementKind.INTERFACE) continue;
            try {
                generateAoSImplementation((TypeElement) element);
                generateSoAImplementation((TypeElement) element);
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.getMessage(), element);
            }
        }
        return true;
    }

    private String getImplBaseName(TypeElement element) {
        String packageName = processingEnv.getElementUtils().getPackageOf(element).getQualifiedName().toString();
        String fullName = element.getQualifiedName().toString();
        String relativeName = packageName.isEmpty() ? fullName : fullName.substring(packageName.length() + 1);
        return relativeName.replace('.', '_');
    }

    private String getFullImplName(TypeMirror type, String suffix) {
        TypeElement el = (TypeElement) processingEnv.getTypeUtils().asElement(type);
        String pkg = processingEnv.getElementUtils().getPackageOf(el).getQualifiedName().toString();
        return pkg + "." + getImplBaseName(el) + suffix;
    }

    private String getValueLayoutFor(TypeMirror type) {
        if (processingEnv.getTypeUtils().isAssignable(processingEnv.getTypeUtils().erasure(type), pointerType)) {
            return "java.lang.foreign.ValueLayout.JAVA_LONG";
        }
        return switch (type.getKind()) {
            case INT -> "java.lang.foreign.ValueLayout.JAVA_INT";
            case LONG -> "java.lang.foreign.ValueLayout.JAVA_LONG";
            case DOUBLE -> "java.lang.foreign.ValueLayout.JAVA_DOUBLE";
            case FLOAT -> "java.lang.foreign.ValueLayout.JAVA_FLOAT";
            case BYTE -> "java.lang.foreign.ValueLayout.JAVA_BYTE";
            case CHAR -> "java.lang.foreign.ValueLayout.JAVA_CHAR";
            case SHORT -> "java.lang.foreign.ValueLayout.JAVA_SHORT";
            case BOOLEAN -> "java.lang.foreign.ValueLayout.JAVA_BOOLEAN";
            default -> throw new IllegalArgumentException("Unsupported type: " + type);
        };
    }

    private void generateAoSImplementation(TypeElement interfaceElement) throws IOException {
        String packageName = processingEnv.getElementUtils().getPackageOf(interfaceElement).getQualifiedName().toString();
        String implName = getImplBaseName(interfaceElement) + "Impl";

        List<ExecutableElement> allMethods = processingEnv.getElementUtils().getAllMembers(interfaceElement).stream()
                .filter(e -> e.getKind() == ElementKind.METHOD).map(e -> (ExecutableElement) e).collect(Collectors.toList());
        List<ExecutableElement> fieldMethods = allMethods.stream()
                .filter(m -> m.getAnnotation(Struct.Field.class) != null)
                .sorted(Comparator.comparingInt(m -> m.getAnnotation(Struct.Field.class).order())).collect(Collectors.toList());

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(implName).addModifiers(Modifier.PUBLIC, Modifier.FINAL).addSuperinterface(TypeName.get(interfaceElement.asType()));
        classBuilder.addField(MemorySegment.class, "segment", Modifier.PRIVATE).addField(ClassName.get("com.github.goguma9071.jvmplus.memory", "MemoryPool"), "pool", Modifier.PRIVATE, Modifier.FINAL);

        CodeBlock.Builder staticInit = CodeBlock.builder().addStatement("java.util.List<java.lang.foreign.MemoryLayout> elements = new java.util.ArrayList<>()").addStatement("long currentOffset = 0");
        CodeBlock.Builder staticFieldsInit = CodeBlock.builder().addStatement("java.util.List<java.lang.foreign.MemoryLayout> staticElements = new java.util.ArrayList<>()").addStatement("long staticOffset = 0");
        
        TypeSpec.Builder fieldsBuilder = TypeSpec.classBuilder("Fields").addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);

        boolean hasStatic = false;
        for (ExecutableElement m : fieldMethods) {
            String name = m.getSimpleName().toString();
            TypeMirror type = m.getAnnotation(Struct.Array.class) != null ? m.getReturnType() : (m.getReturnType().getKind() == TypeKind.VOID ? m.getParameters().get(0).asType() : m.getReturnType());
            boolean isStruct = processingEnv.getTypeUtils().isAssignable(type, structType);
            boolean isString = processingEnv.getTypeUtils().isSameType(type, stringType);
            boolean isArray = m.getAnnotation(Struct.Array.class) != null;
            boolean isStatic = m.getAnnotation(Struct.Static.class) != null;

            String offsetFieldName = name.toUpperCase() + "_OFFSET";
            classBuilder.addField(long.class, offsetFieldName, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
            fieldsBuilder.addField(FieldSpec.builder(String.class, name.toUpperCase() + "_NAME", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).initializer("$S", name).build());
            fieldsBuilder.addField(FieldSpec.builder(long.class, offsetFieldName, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).initializer("$T.$L", ClassName.get(packageName, implName), offsetFieldName).build());

            CodeBlock.Builder targetInit = isStatic ? staticFieldsInit : staticInit;
            String offsetVar = isStatic ? "staticOffset" : "currentOffset";
            String elementsVar = isStatic ? "staticElements" : "elements";
            if (isStatic) hasStatic = true;

            targetInit.beginControlFlow(""); 
            if (isStruct) targetInit.addStatement("java.lang.foreign.MemoryLayout fl = $L.LAYOUT.withName($S)", getFullImplName(type, "Impl"), name);
            else if (isString) {
                int len = m.getAnnotation(Struct.UTF8.class).length();
                targetInit.addStatement("java.lang.foreign.MemoryLayout fl = java.lang.foreign.MemoryLayout.sequenceLayout($L, java.lang.foreign.ValueLayout.JAVA_BYTE).withName($S)", len, name);
                fieldsBuilder.addField(FieldSpec.builder(int.class, name.toUpperCase() + "_LEN", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).initializer("$L", len).build());
            } else if (isArray) {
                int len = m.getAnnotation(Struct.Array.class).length();
                targetInit.addStatement("java.lang.foreign.MemoryLayout fl = java.lang.foreign.MemoryLayout.sequenceLayout($L, $L).withName($S)", len, getValueLayoutFor(type), name);
                fieldsBuilder.addField(FieldSpec.builder(int.class, name.toUpperCase() + "_LEN", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).initializer("$L", len).build());
            } else targetInit.addStatement("java.lang.foreign.ValueLayout fl = $L.withName($S)", getValueLayoutFor(type), name);
            
            targetInit.addStatement("long alignment = fl.byteAlignment()");
            targetInit.beginControlFlow("if ($L % alignment != 0)", offsetVar);
            targetInit.addStatement("long p = alignment - ($L % alignment)", offsetVar);
            targetInit.addStatement("$L.add(java.lang.foreign.MemoryLayout.paddingLayout(p))", elementsVar);
            targetInit.addStatement("$L += p", offsetVar);
            targetInit.endControlFlow();
            
            targetInit.addStatement("$L = $L", offsetFieldName, offsetVar);
            targetInit.addStatement("$L.add(fl)", elementsVar);
            targetInit.addStatement("$L += fl.byteSize()", offsetVar);
            targetInit.endControlFlow(); 
        }
        
        classBuilder.addField(java.lang.foreign.GroupLayout.class, "LAYOUT", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
        staticInit.addStatement("LAYOUT = java.lang.foreign.MemoryLayout.structLayout(elements.toArray(new java.lang.foreign.MemoryLayout[0]))");

        classBuilder.addField(MemorySegment.class, "STATIC_SEGMENT", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
        if (hasStatic) {
            staticFieldsInit.addStatement("java.lang.foreign.GroupLayout staticLayout = java.lang.foreign.MemoryLayout.structLayout(staticElements.toArray(new java.lang.foreign.MemoryLayout[0]))");
            staticFieldsInit.addStatement("STATIC_SEGMENT = java.lang.foreign.Arena.ofShared().allocate(staticLayout.byteSize(), staticLayout.byteAlignment())");
            staticInit.add(staticFieldsInit.build());
        } else {
            staticInit.addStatement("STATIC_SEGMENT = java.lang.foreign.MemorySegment.NULL");
        }

        for (ExecutableElement m : fieldMethods) {
            String name = m.getSimpleName().toString();
            TypeMirror type = m.getAnnotation(Struct.Array.class) != null ? m.getReturnType() : (m.getReturnType().getKind() == TypeKind.VOID ? m.getParameters().get(0).asType() : m.getReturnType());
            if (!processingEnv.getTypeUtils().isAssignable(type, structType) && !processingEnv.getTypeUtils().isSameType(type, stringType) && m.getAnnotation(Struct.Array.class) == null) {
                classBuilder.addField(VarHandle.class, name.toUpperCase() + "_HANDLE", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
                String targetLayout = m.getAnnotation(Struct.Static.class) != null ? "staticLayout" : "LAYOUT";
                staticInit.addStatement("$L_HANDLE = $L.varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement($S))", name.toUpperCase(), targetLayout, name);
            }
        }

        classBuilder.addStaticBlock(staticInit.build());
        classBuilder.addType(fieldsBuilder.build());
        
        addMethodsToClass(classBuilder, allMethods, fieldMethods, interfaceElement, false);
        JavaFile.builder(packageName, classBuilder.build()).build().writeTo(processingEnv.getFiler());
    }

    private void generateSoAImplementation(TypeElement interfaceElement) throws IOException {
        String packageName = processingEnv.getElementUtils().getPackageOf(interfaceElement).getQualifiedName().toString();
        String implName = getImplBaseName(interfaceElement) + "SoAImpl";
        String aosImplName = getImplBaseName(interfaceElement) + "Impl";
        
        List<ExecutableElement> allMethods = processingEnv.getElementUtils().getAllMembers(interfaceElement).stream()
                .filter(e -> e.getKind() == ElementKind.METHOD).map(e -> (ExecutableElement) e).collect(Collectors.toList());
        List<ExecutableElement> fieldMethods = allMethods.stream()
                .filter(m -> m.getAnnotation(Struct.Field.class) != null)
                .sorted(Comparator.comparingInt(m -> m.getAnnotation(Struct.Field.class).order())).collect(Collectors.toList());

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(implName).addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(ClassName.get("com.github.goguma9071.jvmplus.memory", "StructArray"), TypeName.get(interfaceElement.asType())))
                .addSuperinterface(TypeName.get(interfaceElement.asType()));

        classBuilder.addField(int.class, "currentIndex", Modifier.PRIVATE).addField(int.class, "capacity", Modifier.PRIVATE, Modifier.FINAL)
                .addField(java.lang.foreign.Arena.class, "arena", Modifier.PRIVATE, Modifier.FINAL);

        MethodSpec.Builder constr = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(int.class, "capacity")
                .addStatement("this.capacity = capacity").addStatement("this.arena = java.lang.foreign.Arena.ofShared()");
        
        CodeBlock.Builder staticInit = CodeBlock.builder();
        classBuilder.addField(MemorySegment.class, "STATIC_SEGMENT", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
        staticInit.addStatement("STATIC_SEGMENT = $T.STATIC_SEGMENT", ClassName.get(packageName, aosImplName));

        for (ExecutableElement m : fieldMethods) {
            String name = m.getSimpleName().toString();
            TypeMirror type = m.getAnnotation(Struct.Array.class) != null ? m.getReturnType() : (m.getReturnType().getKind() == TypeKind.VOID ? m.getParameters().get(0).asType() : m.getReturnType());
            boolean isArray = m.getAnnotation(Struct.Array.class) != null;
            boolean isPointer = processingEnv.getTypeUtils().isAssignable(processingEnv.getTypeUtils().erasure(type), pointerType);
            boolean isStatic = m.getAnnotation(Struct.Static.class) != null;
            
            String offsetFieldName = name.toUpperCase() + "_OFFSET";
            classBuilder.addField(long.class, offsetFieldName, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
            staticInit.addStatement("$L = $T.$L", offsetFieldName, ClassName.get(packageName, aosImplName), offsetFieldName);

            if (!isStatic) {
                classBuilder.addField(MemorySegment.class, name + "_Segment", Modifier.PUBLIC, Modifier.FINAL);
                if (type.getKind().isPrimitive() && !isArray && !isPointer) {
                    String layout = getValueLayoutFor(type);
                    classBuilder.addField(VarHandle.class, name.toUpperCase() + "_HANDLE", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
                    constr.addStatement("this.$L_Segment = arena.allocate($L.byteSize() * (long)capacity, $L.byteAlignment())", name, layout, layout);
                    staticInit.addStatement("$L_HANDLE = $L.arrayElementVarHandle()", name.toUpperCase(), layout);
                    if (type.getKind() == TypeKind.DOUBLE) classBuilder.addMethod(generateSoASimdSum(name));
                } else if (processingEnv.getTypeUtils().isSameType(type, stringType)) {
                    int len = m.getAnnotation(Struct.UTF8.class).length();
                    classBuilder.addField(int.class, name.toUpperCase() + "_LEN", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
                    staticInit.addStatement("$L_LEN = $L", name.toUpperCase(), len);
                    constr.addStatement("this.$L_Segment = arena.allocate((long)$L * capacity, 1)", name, len);
                } else if (isArray) {
                    int len = m.getAnnotation(Struct.Array.class).length();
                    String componentLayout = getValueLayoutFor(type);
                    constr.addStatement("this.$L_Segment = arena.allocate($L.byteSize() * $L * (long)capacity, $L.byteAlignment())", name, componentLayout, len, componentLayout);
                } else if (isPointer) {
                    constr.addStatement("this.$L_Segment = arena.allocate(8 * (long)capacity, 8)", name);
                } else if (processingEnv.getTypeUtils().isAssignable(type, structType)) {
                    String nestedImplName = getFullImplName(type, "Impl");
                    constr.addStatement("this.$L_Segment = arena.allocate($L.LAYOUT.byteSize() * (long)capacity, $L.LAYOUT.byteAlignment())", name, nestedImplName, nestedImplName);
                    classBuilder.addField(TypeName.get(type), name + "_flyweight", Modifier.PRIVATE, Modifier.FINAL);
                    constr.addStatement("this.$L_flyweight = ($T) com.github.goguma9071.jvmplus.memory.MemoryManager.createEmptyStruct($T.class)", name, TypeName.get(type), TypeName.get(type));
                }
            }
        }
        classBuilder.addStaticBlock(staticInit.build()).addMethod(constr.build());

        classBuilder.addMethod(MethodSpec.methodBuilder("get").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addParameter(int.class, "index").returns(TypeName.get(interfaceElement.asType())).addStatement("this.currentIndex = index").addStatement("return this").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("size").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(int.class).addStatement("return capacity").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("iterator").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(ParameterizedTypeName.get(ClassName.get("java.util", "Iterator"), TypeName.get(interfaceElement.asType()))).addCode("return new java.util.Iterator<>() { private int current = 0; @Override public boolean hasNext() { return current < capacity; } @Override public $T next() { return get(current++); } }; ", TypeName.get(interfaceElement.asType())).build());

        MethodSpec.Builder sumDoubleBuilder = MethodSpec.methodBuilder("sumDouble").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addParameter(String.class, "fieldName").returns(double.class);
        sumDoubleBuilder.beginControlFlow("switch(fieldName)");
        for (ExecutableElement m : fieldMethods) {
            String name = m.getSimpleName().toString();
            TypeMirror type = m.getAnnotation(Struct.Array.class) != null ? m.getReturnType() : (m.getReturnType().getKind() == TypeKind.VOID ? m.getParameters().get(0).asType() : m.getReturnType());
            if (type.getKind() == TypeKind.DOUBLE && m.getAnnotation(Struct.Array.class) == null && m.getAnnotation(Struct.Static.class) == null) {
                sumDoubleBuilder.addCode("case $S: return sum$L(); ", name, name.substring(0, 1).toUpperCase() + (name.length()>1?name.substring(1):""));
            }
        }
        sumDoubleBuilder.addStatement("default: throw new UnsupportedOperationException(\"Field not found or not a double: \" + fieldName)");
        sumDoubleBuilder.endControlFlow();
        classBuilder.addMethod(sumDoubleBuilder.build());

        addMethodsToClass(classBuilder, allMethods, fieldMethods, interfaceElement, true);
        JavaFile.builder(packageName, classBuilder.build()).build().writeTo(processingEnv.getFiler());
    }

    private MethodSpec generateSoASimdSum(String fieldName) {
        String segmentName = fieldName + "_Segment";
        String capitalized = fieldName.substring(0, 1).toUpperCase() + (fieldName.length() > 1 ? fieldName.substring(1) : "");
        return MethodSpec.methodBuilder("sum" + capitalized).addModifiers(Modifier.PRIVATE).returns(double.class)
                .addStatement("jdk.incubator.vector.VectorSpecies<Double> species = jdk.incubator.vector.DoubleVector.SPECIES_PREFERRED")
                .addStatement("jdk.incubator.vector.DoubleVector acc = jdk.incubator.vector.DoubleVector.zero(species)")
                .addStatement("int i = 0").addStatement("int upperBound = species.loopBound(capacity)")
                .beginControlFlow("for (; i < upperBound; i += species.length())")
                .addStatement("jdk.incubator.vector.DoubleVector v = jdk.incubator.vector.DoubleVector.fromMemorySegment(species, this.$L, (long)i * 8, java.nio.ByteOrder.nativeOrder())", segmentName)
                .addStatement("acc = acc.add(v)")
                .endControlFlow()
                .addStatement("double total = acc.reduceLanes(jdk.incubator.vector.VectorOperators.ADD)")
                .beginControlFlow("for (; i < capacity; i++)")
                .addStatement("total += (double)this.$L.getAtIndex(java.lang.foreign.ValueLayout.JAVA_DOUBLE, (long)i)", segmentName)
                .endControlFlow()
                .addStatement("return total").build();
    }

    private void addMethodsToClass(TypeSpec.Builder classBuilder, List<ExecutableElement> allMethods, List<ExecutableElement> fieldMethods, TypeElement interfaceElement, boolean isSoA) {
        String outerClassName = isSoA ? getImplBaseName(interfaceElement) + "SoAImpl" : getImplBaseName(interfaceElement) + "Impl";
        
        classBuilder.addMethod(MethodSpec.methodBuilder("address").addModifiers(Modifier.PUBLIC).returns(long.class).addAnnotation(Override.class).addStatement(isSoA ? "return 0" : "return segment.address()").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("segment").addModifiers(Modifier.PUBLIC).returns(MemorySegment.class).addAnnotation(Override.class).addStatement(isSoA ? "return null" : "return segment").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("rebase").addModifiers(Modifier.PUBLIC).addParameter(MemorySegment.class, "s").addAnnotation(Override.class).addStatement(isSoA ? "" : "this.segment = s").build());

        MethodSpec.Builder asPointerBuilder = MethodSpec.methodBuilder("asPointer").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addTypeVariable(TypeVariableName.get("T", ClassName.get("com.github.goguma9071.jvmplus.memory", "Struct")))
                .returns(ParameterizedTypeName.get(ClassName.get("com.github.goguma9071.jvmplus.memory", "Pointer"), TypeVariableName.get("T")));
        
        if (isSoA) {
            asPointerBuilder.addStatement("throw new UnsupportedOperationException(\"asPointer not supported for SoA\")");
        } else {
            asPointerBuilder.addStatement("final java.lang.foreign.MemorySegment _s = this.segment")
                .addCode("return (Pointer<T>) new Pointer<$T>() {\n", TypeName.get(interfaceElement.asType()))
                .addCode("  @Override public $T deref() {\n", TypeName.get(interfaceElement.asType()))
                .addStatement("    $T obj = com.github.goguma9071.jvmplus.memory.MemoryManager.createEmptyStruct($T.class)", TypeName.get(interfaceElement.asType()), TypeName.get(interfaceElement.asType()))
                .addStatement("    obj.rebase(_s)")
                .addStatement("    return obj")
                .addCode("  }\n")
                .addCode("  @Override public void set($T v) { throw new UnsupportedOperationException(); }\n", TypeName.get(interfaceElement.asType()))
                .addCode("  @Override public long address() { return _s.address(); }\n")
                .addCode("  @Override public Pointer<$T> offset(long count) {\n", TypeName.get(interfaceElement.asType()))
                .addStatement("    long newAddr = _s.address() + count * LAYOUT.byteSize()")
                .addCode("    return new Pointer<$T>() {\n", TypeName.get(interfaceElement.asType()))
                .addCode("      @Override public $T deref() {\n", TypeName.get(interfaceElement.asType()))
                .addStatement("        $T obj = com.github.goguma9071.jvmplus.memory.MemoryManager.createEmptyStruct($T.class)", TypeName.get(interfaceElement.asType()), TypeName.get(interfaceElement.asType()))
                .addStatement("        obj.rebase(java.lang.foreign.MemorySegment.ofAddress(newAddr).reinterpret(LAYOUT.byteSize()))")
                .addStatement("        return obj")
                .addCode("      }\n")
                .addCode("      @Override public void set($T v) { throw new UnsupportedOperationException(); }\n", TypeName.get(interfaceElement.asType()))
                .addCode("      @Override public long address() { return newAddr; }\n")
                .addCode("      @Override public Pointer<$T> offset(long c) { throw new UnsupportedOperationException(); }\n", TypeName.get(interfaceElement.asType()))
                .addCode("      @Override public Class<$T> targetType() { return $T.class; }\n", TypeName.get(interfaceElement.asType()), TypeName.get(interfaceElement.asType()))
                .addCode("    };\n")
                .addCode("  }\n")
                .addCode("  @Override public Class<$T> targetType() { return $T.class; }\n", TypeName.get(interfaceElement.asType()), TypeName.get(interfaceElement.asType()))
                .addCode("};\n");
        }
        classBuilder.addMethod(asPointerBuilder.build());

        MethodSpec.Builder closeBuilder = MethodSpec.methodBuilder("close").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class);
        if (isSoA) closeBuilder.addStatement("arena.close()");
        else closeBuilder.addStatement("com.github.goguma9071.jvmplus.memory.MemoryManager.free(this)");
        classBuilder.addMethod(closeBuilder.build());

        if (!isSoA) {
            classBuilder.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(MemorySegment.class, "segment").addParameter(ClassName.get("com.github.goguma9071.jvmplus.memory", "MemoryPool"), "pool").addStatement("this.segment = segment").addStatement("this.pool = pool").build());
        }

        Set<String> generatedSignatures = new HashSet<>();
        for (ExecutableElement m : allMethods) {
            String name = m.getSimpleName().toString();
            String signature = name + m.getParameters().size();
            if (generatedSignatures.contains(signature)) continue;
            
            Optional<ExecutableElement> fm = fieldMethods.stream().filter(f -> f.getSimpleName().toString().equals(name)).findFirst();
            if (fm.isPresent()) {
                boolean isArray = fm.get().getAnnotation(Struct.Array.class) != null;
                boolean isSetter = isArray ? m.getParameters().size() == 2 : m.getParameters().size() == 1;
                TypeMirror fieldType = isSetter ? (isArray ? m.getParameters().get(1).asType() : m.getParameters().get(0).asType()) : m.getReturnType();
                boolean isStatic = fm.get().getAnnotation(Struct.Static.class) != null;
                
                boolean isStruct = processingEnv.getTypeUtils().isAssignable(fieldType, structType);
                boolean isString = processingEnv.getTypeUtils().isSameType(fieldType, stringType);
                boolean isPointer = processingEnv.getTypeUtils().isAssignable(processingEnv.getTypeUtils().erasure(fieldType), pointerType);
                
                MethodSpec.Builder mb = MethodSpec.methodBuilder(name).addModifiers(Modifier.PUBLIC).addAnnotation(Override.class);
                
                if (isPointer) { 
                    TypeMirror targetType = ((DeclaredType) fieldType).getTypeArguments().get(0);
                    String targetImpl = getFullImplName(targetType, "Impl");
                    String seg = isStatic ? "STATIC_SEGMENT" : (isSoA ? outerClassName + ".this." + name + "_Segment" : "this.segment");
                    String offset = isStatic ? name.toUpperCase() + "_OFFSET" : (isSoA ? "(long)currentIndex * 8" : name.toUpperCase() + "_OFFSET");

                    if (isSetter) {
                        mb.addParameter(TypeName.get(fieldType), "v");
                        mb.addStatement("throw new UnsupportedOperationException(\"Pointer setter not yet implemented\")");
                    } else {
                        mb.returns(TypeName.get(fieldType));
                        mb.addStatement("final java.lang.foreign.MemorySegment _s = $L", seg)
                                .addCode("return new Pointer<$T>() {\n", TypeName.get(targetType))
                                .addCode("  @Override public $T deref() {\n", TypeName.get(targetType))
                                .addStatement("    long addr = _s.get(java.lang.foreign.ValueLayout.JAVA_LONG, $L)", offset)
                                .addStatement("    if (addr == 0) return null")
                                .addStatement("    $T obj = ($T) com.github.goguma9071.jvmplus.memory.MemoryManager.createEmptyStruct($T.class)", TypeName.get(targetType), TypeName.get(targetType), TypeName.get(targetType))
                                .addStatement("    obj.rebase(java.lang.foreign.MemorySegment.ofAddress(addr).reinterpret($L.LAYOUT.byteSize()))", targetImpl)
                                .addStatement("    return obj")
                                .addCode("  }\n")
                                .addCode("  @Override public void set($T v) {\n", TypeName.get(targetType))
                                .addStatement("    _s.set(java.lang.foreign.ValueLayout.JAVA_LONG, $L, v.address())", offset)
                                .addCode("  }\n")
                                .addCode("  @Override public long address() { return _s.get(java.lang.foreign.ValueLayout.JAVA_LONG, $L); }\n", offset)
                                .addCode("  @Override public Pointer<$T> offset(long count) {\n", TypeName.get(targetType))
                                .addStatement("    long baseAddr = address()")
                                .addStatement("    long newAddr = baseAddr + count * $L.LAYOUT.byteSize()", targetImpl)
                                .addCode("    return new Pointer<$T>() {\n", TypeName.get(targetType))
                                .addCode("      @Override public $T deref() {\n", TypeName.get(targetType))
                                .addStatement("        $T obj = com.github.goguma9071.jvmplus.memory.MemoryManager.createEmptyStruct($T.class)", TypeName.get(targetType), TypeName.get(targetType))
                                .addStatement("        obj.rebase(java.lang.foreign.MemorySegment.ofAddress(newAddr).reinterpret($L.LAYOUT.byteSize()))", targetImpl)
                                .addStatement("        return obj")
                                .addCode("      }\n")
                                .addCode("      @Override public void set($T v) { throw new UnsupportedOperationException(); }\n", TypeName.get(targetType))
                                .addCode("      @Override public long address() { return newAddr; }\n")
                                .addCode("      @Override public Pointer<$T> offset(long c) { throw new UnsupportedOperationException(); }\n", TypeName.get(targetType))
                                .addCode("      @Override public Class<$T> targetType() { return $T.class; }\n", TypeName.get(targetType), TypeName.get(targetType))
                                .addCode("    };\n")
                                .addCode("  }\n")
                                .addCode("  @Override public Class<$T> targetType() { return $T.class; }\n", TypeName.get(targetType), TypeName.get(targetType))
                                .addCode("};\n");
                    }
                    classBuilder.addMethod(mb.build());
                    generatedSignatures.add(signature);
                } else if (isStruct) { 
                    String nestedAoSImplName = getFullImplName(fieldType, "Impl");
                    if (isSoA && !isStatic) {
                        String segName = name + "_Segment";
                        if (!isSetter) mb.returns(TypeName.get(fieldType)).addStatement("this.$L_flyweight.rebase(this.$L.asSlice((long)currentIndex * $L.LAYOUT.byteSize(), $L.LAYOUT.byteSize())); return this.$L_flyweight", name, segName, nestedAoSImplName, nestedAoSImplName, name);
                        else mb.addParameter(TypeName.get(fieldType), "v").addStatement("java.lang.foreign.MemorySegment.copy(v.segment(), 0, this.$L, (long)currentIndex * $L.LAYOUT.byteSize(), $L.LAYOUT.byteSize())", segName, nestedAoSImplName, nestedAoSImplName);
                    } else {
                        String seg = isStatic ? "STATIC_SEGMENT" : "this.segment";
                        if (isSetter) mb.addParameter(TypeName.get(fieldType), "v").addStatement("java.lang.foreign.MemorySegment.copy(v.segment(), 0, $L, $L_OFFSET, $L.LAYOUT.byteSize())", seg, name.toUpperCase(), nestedAoSImplName);
                        else mb.returns(TypeName.get(fieldType)).addStatement("return new $L($L.asSlice($L_OFFSET, $L.LAYOUT.byteSize()), null)", nestedAoSImplName, seg, name.toUpperCase(), nestedAoSImplName);
                    }
                    classBuilder.addMethod(mb.build());
                    generatedSignatures.add(signature);
                } else if (isString) {
                    int len = fm.get().getAnnotation(Struct.UTF8.class).length();
                    String seg = isStatic ? "STATIC_SEGMENT" : (isSoA ? "this." + name + "_Segment" : "this.segment");
                    String offset = isStatic ? name.toUpperCase() + "_OFFSET" : (isSoA ? "(long)currentIndex * " + name.toUpperCase() + "_LEN" : name.toUpperCase() + "_OFFSET");
                    String lenAccess = isSoA && !isStatic ? name.toUpperCase() + "_LEN" : "" + len;
                    if (isSetter) {
                        mb.addParameter(String.class, "v").addStatement("byte[] b = v.getBytes(java.nio.charset.StandardCharsets.UTF_8)").addStatement("int cl = Math.min(b.length, $L)", lenAccess).addStatement("java.lang.foreign.MemorySegment.copy(java.lang.foreign.MemorySegment.ofArray(b), 0, $L, $L, cl)", seg, offset).beginControlFlow("if (cl < $L)", lenAccess).addStatement("$L.asSlice($L + (long)cl, (long)$L - cl).fill((byte)0)", seg, offset, lenAccess).endControlFlow();
                    } else {
                        mb.returns(String.class).addStatement("byte[] b = $L.asSlice($L, (long)$L).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)", seg, offset, lenAccess).addStatement("int l=0; while(l<b.length && b[l]!=0) l++; return new String(b, 0, l, java.nio.charset.StandardCharsets.UTF_8)");
                    }
                    classBuilder.addMethod(mb.build());
                    generatedSignatures.add(signature);
                } else if (isArray) {
                    int arrayLen = fm.get().getAnnotation(Struct.Array.class).length();
                    String layout = getValueLayoutFor(fieldType);
                    String seg = isStatic ? "STATIC_SEGMENT" : (isSoA ? "this." + name + "_Segment" : "this.segment");
                    String baseOffset = isStatic ? name.toUpperCase() + "_OFFSET" : (isSoA ? "0" : name.toUpperCase() + "_OFFSET");
                    
                    mb.addParameter(int.class, "index");
                    mb.addStatement("if (index < 0 || index >= $L) throw new IndexOutOfBoundsException()", arrayLen);
                    if (isSetter) {
                        mb.addParameter(TypeName.get(fieldType), "v");
                        if (isSoA && !isStatic) mb.addStatement("$L.setAtIndex($L, (long)currentIndex * $L + index, v)", seg, layout, arrayLen);
                        else mb.addStatement("$L.set($L, $L + (long)index * $L.byteSize(), v)", seg, layout, baseOffset, layout);
                    } else {
                        mb.returns(TypeName.get(fieldType));
                        if (isSoA && !isStatic) mb.addStatement("return $L.getAtIndex($L, (long)currentIndex * $L + index)", seg, layout, arrayLen);
                        else mb.addStatement("return $L.get($L, $L + (long)index * $L.byteSize())", seg, layout, baseOffset, layout);
                    }
                    classBuilder.addMethod(mb.build());
                    generatedSignatures.add(signature);
                } else {
                    String layout = getValueLayoutFor(fieldType);
                    String seg = isStatic ? "STATIC_SEGMENT" : (isSoA ? "this." + name + "_Segment" : "this.segment");
                    String offset = isStatic ? name.toUpperCase() + "_OFFSET" : (isSoA ? "(long)currentIndex" : name.toUpperCase() + "_OFFSET");
                    
                    if (isSoA && !isStatic) {
                        if (isSetter) mb.addParameter(TypeName.get(fieldType), "v").addStatement("$L.setAtIndex($L, $L, v)", seg, layout, offset);
                        else mb.returns(TypeName.get(fieldType)).addStatement("return $L.getAtIndex($L, $L)", seg, layout, offset);
                    } else {
                        if (isSetter) mb.addParameter(TypeName.get(fieldType), "v").addStatement("$L.set($L, $L, v)", seg, layout, offset);
                        else mb.returns(TypeName.get(fieldType)).addStatement("return $L.get($L, $L)", seg, layout, offset);
                    }
                    classBuilder.addMethod(mb.build());
                    generatedSignatures.add(signature);
                }
            }
        }
    }
}
