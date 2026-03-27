package com.github.goguma9071.jvmplus.processor;

import com.github.goguma9071.jvmplus.memory.Struct;
import com.google.auto.service.AutoService;
import com.squareup.javapoet.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
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

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(Struct.Type.class)) {
            if (element.getKind() != ElementKind.INTERFACE) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "@Struct.Type can only be applied to interfaces", element);
                continue;
            }

            try {
                generateAoSImplementation((TypeElement) element);
                generateSoAImplementation((TypeElement) element);
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed to generate implementation: " + e.getMessage(), element);
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

    // --- AoS Implementation ---
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

        CodeBlock.Builder staticInit = CodeBlock.builder()
                .addStatement("java.util.List<java.lang.foreign.MemoryLayout> elements = new java.util.ArrayList<>()")
                .addStatement("long currentOffset = 0");
        TypeMirror structType = processingEnv.getElementUtils().getTypeElement("com.github.goguma9071.jvmplus.memory.Struct").asType();
        TypeMirror stringType = processingEnv.getElementUtils().getTypeElement("java.lang.String").asType();

        // 메타데이터 상수 필드
        TypeSpec.Builder fieldsBuilder = TypeSpec.classBuilder("Fields").addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);

        // 1차 루프: 레이아웃 계산
        for (ExecutableElement m : fieldMethods) {
            String name = m.getSimpleName().toString();
            TypeMirror type = m.getReturnType().getKind() == TypeKind.VOID ? m.getParameters().get(0).asType() : m.getReturnType();
            boolean isStruct = processingEnv.getTypeUtils().isAssignable(type, structType);
            boolean isString = processingEnv.getTypeUtils().isSameType(type, stringType);

            // Impl 클래스 자체에 OFFSET 필드를 만듭니다.
            classBuilder.addField(long.class, name.toUpperCase() + "_OFFSET", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
            
            // Fields 내부 클래스에도 NAME과 OFFSET 필드를 추가합니다.
            fieldsBuilder.addField(FieldSpec.builder(String.class, name.toUpperCase() + "_NAME", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$S", name).build());
            fieldsBuilder.addField(FieldSpec.builder(long.class, name.toUpperCase() + "_OFFSET", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$T.$L_OFFSET", ClassName.get(packageName, implName), name.toUpperCase()).build());

            staticInit.beginControlFlow(""); 
            if (isStruct) staticInit.addStatement("java.lang.foreign.MemoryLayout fl = $L.LAYOUT.withName($S)", getFullImplName(type, "Impl"), name);
            else if (isString) {
                int len = m.getAnnotation(Struct.UTF8.class).length();
                staticInit.addStatement("java.lang.foreign.MemoryLayout fl = java.lang.foreign.MemoryLayout.sequenceLayout($L, java.lang.foreign.ValueLayout.JAVA_BYTE).withName($S)", len, name);
                fieldsBuilder.addField(FieldSpec.builder(int.class, name.toUpperCase() + "_LEN", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).initializer("$L", len).build());
            } else staticInit.addStatement("java.lang.foreign.ValueLayout fl = $L.withName($S)", getValueLayoutFor(type), name);
            
            staticInit.addStatement("long alignment = fl.byteAlignment()");
            staticInit.beginControlFlow("if (currentOffset % alignment != 0)");
            staticInit.addStatement("long p = alignment - (currentOffset % alignment)");
            staticInit.addStatement("elements.add(java.lang.foreign.MemoryLayout.paddingLayout(p))");
            staticInit.addStatement("currentOffset += p");
            staticInit.endControlFlow();
            
            staticInit.addStatement("$L_OFFSET = currentOffset", name.toUpperCase());
            staticInit.addStatement("elements.add(fl)");
            staticInit.addStatement("currentOffset += fl.byteSize()");
            staticInit.endControlFlow(); 
        }
        
        // 2차: LAYOUT 초기화 (VarHandle보다 무조건 먼저 실행)
        classBuilder.addField(java.lang.foreign.GroupLayout.class, "LAYOUT", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
        staticInit.addStatement("LAYOUT = java.lang.foreign.MemoryLayout.structLayout(elements.toArray(new java.lang.foreign.MemoryLayout[0]))");

        // 3차: VarHandle 초기화
        for (ExecutableElement m : fieldMethods) {
            String name = m.getSimpleName().toString();
            TypeMirror type = m.getReturnType().getKind() == TypeKind.VOID ? m.getParameters().get(0).asType() : m.getReturnType();
            if (!processingEnv.getTypeUtils().isAssignable(type, structType) && !processingEnv.getTypeUtils().isSameType(type, stringType)) {
                classBuilder.addField(VarHandle.class, name.toUpperCase() + "_HANDLE", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
                staticInit.addStatement("$L_HANDLE = LAYOUT.varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement($S))", name.toUpperCase(), name);
            }
        }

        classBuilder.addStaticBlock(staticInit.build());
        classBuilder.addType(fieldsBuilder.build());
        
        addMethodsToClass(classBuilder, allMethods, fieldMethods, interfaceElement, structType, stringType, false);
        JavaFile.builder(packageName, classBuilder.build()).build().writeTo(processingEnv.getFiler());
    }

    // --- SoA Implementation ---
    private void generateSoAImplementation(TypeElement interfaceElement) throws IOException {
        String packageName = processingEnv.getElementUtils().getPackageOf(interfaceElement).getQualifiedName().toString();
        String implName = getImplBaseName(interfaceElement) + "SoAImpl";
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

        TypeMirror stringType = processingEnv.getElementUtils().getTypeElement("com.github.goguma9071.jvmplus.memory.Struct").asType();
        TypeMirror structType = processingEnv.getElementUtils().getTypeElement("com.github.goguma9071.jvmplus.memory.Struct").asType();

        MethodSpec.Builder constr = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(int.class, "capacity")
                .addStatement("this.capacity = capacity").addStatement("this.arena = java.lang.foreign.Arena.ofShared()");
        
        CodeBlock.Builder staticInit = CodeBlock.builder();
        for (ExecutableElement m : fieldMethods) {
            String name = m.getSimpleName().toString();
            TypeMirror type = m.getReturnType().getKind() == TypeKind.VOID ? m.getParameters().get(0).asType() : m.getReturnType();
            classBuilder.addField(MemorySegment.class, name + "_Segment", Modifier.PUBLIC, Modifier.FINAL);

            if (type.getKind().isPrimitive()) {
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
            } else if (processingEnv.getTypeUtils().isAssignable(type, structType)) { // SoA 중첩 구조체 세그먼트 할당 (AoS LAYOUT 사용)
                String nestedImplName = getFullImplName(type, "Impl");
                constr.addStatement("this.$L_Segment = arena.allocate($L.LAYOUT.byteSize() * (long)capacity, $L.LAYOUT.byteAlignment())", name, nestedImplName, nestedImplName);
                
                // 중첩 구조체 Flyweight 필드 선언 및 초기화
                classBuilder.addField(TypeName.get(type), name + "_flyweight", Modifier.PRIVATE, Modifier.FINAL);
                constr.addStatement("this.$L_flyweight = ($T) com.github.goguma9071.jvmplus.memory.MemoryManager.createEmptyStruct($T.class)", name, TypeName.get(type), TypeName.get(type));
            }
        }
        classBuilder.addStaticBlock(staticInit.build()).addMethod(constr.build());

        // StructArray 인터페이스 구현 (핵심!!)
        classBuilder.addMethod(MethodSpec.methodBuilder("get").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addParameter(int.class, "index").returns(TypeName.get(interfaceElement.asType())).addStatement("this.currentIndex = index").addStatement("return this").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("size").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(int.class).addStatement("return capacity").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("iterator").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).returns(ParameterizedTypeName.get(ClassName.get("java.util", "Iterator"), TypeName.get(interfaceElement.asType()))).addCode("return new java.util.Iterator<>() { private int current = 0; @Override public boolean hasNext() { return current < capacity; } @Override public $T next() { return get(current++); }}; ", TypeName.get(interfaceElement.asType())).build());

        MethodSpec.Builder sumDoubleBuilder = MethodSpec.methodBuilder("sumDouble").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class).addParameter(String.class, "fieldName").returns(double.class);
        sumDoubleBuilder.beginControlFlow("switch(fieldName)");
        for (ExecutableElement m : fieldMethods) {
            String name = m.getSimpleName().toString();
            TypeMirror type = m.getReturnType().getKind() == TypeKind.VOID ? m.getParameters().get(0).asType() : m.getReturnType();
            if (type.getKind() == TypeKind.DOUBLE) {
                sumDoubleBuilder.addCode("case $S: return sum$L(); ", name, name.substring(0, 1).toUpperCase() + (name.length()>1?name.substring(1):""));
            }
        }
        sumDoubleBuilder.addStatement("default: throw new UnsupportedOperationException(\"Field not found or not a double: \" + fieldName)");
        sumDoubleBuilder.endControlFlow();
        classBuilder.addMethod(sumDoubleBuilder.build());

        addMethodsToClass(classBuilder, allMethods, fieldMethods, interfaceElement, structType, stringType, true);
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

    private void addMethodsToClass(TypeSpec.Builder classBuilder, List<ExecutableElement> allMethods, List<ExecutableElement> fieldMethods, TypeElement interfaceElement, TypeMirror structType, TypeMirror stringType, boolean isSoA) {
        classBuilder.addMethod(MethodSpec.methodBuilder("address").addModifiers(Modifier.PUBLIC).returns(long.class).addAnnotation(Override.class).addStatement(isSoA ? "return 0" : "return segment.address()").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("segment").addModifiers(Modifier.PUBLIC).returns(MemorySegment.class).addAnnotation(Override.class).addStatement(isSoA ? "return null" : "return segment").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("rebase").addModifiers(Modifier.PUBLIC).addParameter(MemorySegment.class, "s").addAnnotation(Override.class).addStatement(isSoA ? "" : "this.segment = s").build());

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
                boolean isSetter = m.getParameters().size() > 0;
                TypeMirror fieldType = isSetter ? m.getParameters().get(0).asType() : m.getReturnType();
                boolean isStruct = processingEnv.getTypeUtils().isAssignable(fieldType, structType);
                boolean isString = processingEnv.getTypeUtils().isSameType(fieldType, stringType);
                MethodSpec.Builder mb = MethodSpec.methodBuilder(name).addModifiers(Modifier.PUBLIC).addAnnotation(Override.class);
                
                if (isStruct) { 
                    String nestedAoSImplName = getFullImplName(fieldType, "Impl"); // e.g., Main_Vec3Impl

                    if (isSoA) { // SoA 중첩 구조체 처리
                        String segmentForNestedStruct = name + "_Segment"; // e.g., pos_Segment

                        if (!isSetter) { // SoA Nested Struct Getter
                            mb.returns(TypeName.get(fieldType)).addStatement(
                                    "this.$L_flyweight.rebase(this.$L.asSlice((long)currentIndex * $L.LAYOUT.byteSize(), $L.LAYOUT.byteSize())); return this.$L_flyweight",
                                    name, segmentForNestedStruct, nestedAoSImplName, nestedAoSImplName, name
                            );
                        } else { // SoA Nested Struct Setter
                            mb.addParameter(TypeName.get(fieldType), "v")
                                    .addStatement(
                                            "java.lang.foreign.MemorySegment.copy(v.segment(), 0, this.$L, (long)currentIndex * $L.LAYOUT.byteSize(), $L.LAYOUT.byteSize())",
                                            segmentForNestedStruct, nestedAoSImplName, nestedAoSImplName
                                    );
                        }
                    } else { // AoS 중첩 구조체 처리 (기존 로직)
                        String n = nestedAoSImplName;
                        if (isSetter) mb.addParameter(TypeName.get(fieldType), "v").addStatement("java.lang.foreign.MemorySegment.copy(v.segment(), 0, this.segment, $L_OFFSET, $L.LAYOUT.byteSize())", name.toUpperCase(), n);
                        else mb.returns(TypeName.get(fieldType)).addStatement("return new $L(segment.asSlice($L_OFFSET, $L.LAYOUT.byteSize()), null)", n, name.toUpperCase(), n);
                    }
                    classBuilder.addMethod(mb.build());
                    generatedSignatures.add(signature);

                } else if (isString) {
                    int len = fm.get().getAnnotation(Struct.UTF8.class).length();
                    String seg = isSoA ? "this." + name + "_Segment" : "this.segment";
                    String offset = isSoA ? "(long)currentIndex * " + name.toUpperCase() + "_LEN" : name.toUpperCase() + "_OFFSET";
                    String lenAccess = isSoA ? name.toUpperCase() + "_LEN" : "" + len;
                    if (isSetter) {
                        mb.addParameter(String.class, "v").addStatement("byte[] b = v.getBytes(java.nio.charset.StandardCharsets.UTF_8)").addStatement("int cl = Math.min(b.length, $L)", lenAccess).addStatement("java.lang.foreign.MemorySegment.copy(java.lang.foreign.MemorySegment.ofArray(b), 0, $L, $L, cl)", seg, offset).beginControlFlow("if (cl < $L)", lenAccess).addStatement("$L.asSlice($L + (long)cl, (long)$L - cl).fill((byte)0)", seg, offset, lenAccess).endControlFlow();
                    } else {
                        mb.returns(String.class).addStatement("byte[] b = $L.asSlice($L, (long)$L).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)", seg, offset, lenAccess).addStatement("int l=0; while(l<b.length && b[l]!=0) l++; return new String(b, 0, l, java.nio.charset.StandardCharsets.UTF_8)");
                    }
                    classBuilder.addMethod(mb.build());
                    generatedSignatures.add(signature);
                } else { 
                    String layout = getValueLayoutFor(fieldType);
                    if (isSoA) {
                        if (isSetter) mb.addParameter(TypeName.get(fieldType), "v").addStatement("this.$L_Segment.setAtIndex($L, (long)currentIndex, v)", name, layout);
                        else mb.returns(TypeName.get(fieldType)).addStatement("return this.$L_Segment.getAtIndex($L, (long)currentIndex)", name, layout);
                    } else {
                        if (isSetter) mb.addParameter(TypeName.get(fieldType), "v").addStatement("segment.set($L, $L_OFFSET, v)", layout, name.toUpperCase());
                        else mb.returns(TypeName.get(fieldType)).addStatement("return segment.get($L, $L_OFFSET)", layout, name.toUpperCase());
                    }
                    classBuilder.addMethod(mb.build());
                    generatedSignatures.add(signature);
                }
            }
        }
    }
}
