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
        TypeMirror structType = processingEnv.getElementUtils().getTypeElement("com.github.goguma9071.jvmplus.memory.Struct").asType();
        TypeMirror stringType = processingEnv.getElementUtils().getTypeElement("java.lang.String").asType();

        for (ExecutableElement m : fieldMethods) {
            String name = m.getSimpleName().toString();
            TypeMirror type = m.getReturnType().getKind() == TypeKind.VOID ? m.getParameters().get(0).asType() : m.getReturnType();
            boolean isStruct = processingEnv.getTypeUtils().isAssignable(type, structType);
            boolean isString = processingEnv.getTypeUtils().isSameType(type, stringType);

            classBuilder.addField(long.class, name.toUpperCase() + "_OFFSET", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
            staticInit.add("{\n").indent();
            if (isStruct) staticInit.addStatement("java.lang.foreign.MemoryLayout fl = $L.LAYOUT.withName($S)", getFullImplName(type, "Impl"), name);
            else if (isString) staticInit.addStatement("java.lang.foreign.MemoryLayout fl = java.lang.foreign.MemoryLayout.sequenceLayout($L, java.lang.foreign.ValueLayout.JAVA_BYTE).withName($S)", m.getAnnotation(Struct.UTF8.class).length(), name);
            else staticInit.addStatement("java.lang.foreign.ValueLayout fl = $L.withName($S)", getValueLayoutFor(type), name);
            staticInit.addStatement("$L_OFFSET = currentOffset", name.toUpperCase()).addStatement("elements.add(fl)").addStatement("currentOffset += fl.byteSize()").unindent().add("}\n");
            if (!isStruct && !isString) classBuilder.addField(VarHandle.class, name.toUpperCase() + "_HANDLE", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
        }
        classBuilder.addField(java.lang.foreign.GroupLayout.class, "LAYOUT", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
        staticInit.addStatement("LAYOUT = java.lang.foreign.MemoryLayout.structLayout(elements.toArray(new java.lang.foreign.MemoryLayout[0]))");
        for (ExecutableElement m : fieldMethods) {
            String name = m.getSimpleName().toString();
            TypeMirror type = m.getReturnType().getKind() == TypeKind.VOID ? m.getParameters().get(0).asType() : m.getReturnType();
            if (!processingEnv.getTypeUtils().isAssignable(type, structType) && !processingEnv.getTypeUtils().isSameType(type, stringType))
                staticInit.addStatement("$L_HANDLE = LAYOUT.varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement($S))", name.toUpperCase(), name);
        }
        classBuilder.addStaticBlock(staticInit.build());
        addStandardMethods(classBuilder, allMethods, fieldMethods, interfaceElement, structType, stringType);
        JavaFile.builder(packageName, classBuilder.build()).build().writeTo(processingEnv.getFiler());
    }

    private void generateSoAImplementation(TypeElement interfaceElement) throws IOException {
        String packageName = processingEnv.getElementUtils().getPackageOf(interfaceElement).getQualifiedName().toString();
        String implName = getImplBaseName(interfaceElement) + "SoAImpl";

        List<ExecutableElement> allMethods = processingEnv.getElementUtils().getAllMembers(interfaceElement).stream()
                .filter(e -> e.getKind() == ElementKind.METHOD).map(e -> (ExecutableElement) e).collect(Collectors.toList());
        List<ExecutableElement> fieldMethods = allMethods.stream()
                .filter(m -> m.getAnnotation(Struct.Field.class) != null)
                .sorted(Comparator.comparingInt(m -> m.getAnnotation(Struct.Field.class).order())).collect(Collectors.toList());

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(implName).addModifiers(Modifier.PUBLIC, Modifier.FINAL).addSuperinterface(TypeName.get(interfaceElement.asType()));
        classBuilder.addField(int.class, "currentIndex", Modifier.PRIVATE).addField(int.class, "capacity", Modifier.PRIVATE, Modifier.FINAL);

        TypeMirror stringType = processingEnv.getElementUtils().getTypeElement("java.lang.String").asType();

        MethodSpec.Builder constr = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(int.class, "capacity")
                .addStatement("this.capacity = capacity").addStatement("java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofAuto()");
        
        CodeBlock.Builder staticInit = CodeBlock.builder();
        for (ExecutableElement m : fieldMethods) {
            String name = m.getSimpleName().toString();
            TypeMirror type = m.getReturnType().getKind() == TypeKind.VOID ? m.getParameters().get(0).asType() : m.getReturnType();
            classBuilder.addField(MemorySegment.class, name + "_Segment", Modifier.PUBLIC, Modifier.FINAL);

            if (type.getKind().isPrimitive()) {
                String layout = getValueLayoutFor(type);
                classBuilder.addField(VarHandle.class, name.toUpperCase() + "_HANDLE", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
                constr.addStatement("this.$L_Segment = arena.allocate($L.byteSize() * capacity, $L.byteAlignment())", name, layout, layout);
                staticInit.addStatement("$L_HANDLE = $L.arrayElementVarHandle()", name.toUpperCase(), layout);
                if (type.getKind() == TypeKind.DOUBLE) classBuilder.addMethod(generateSoASimdSum(name));
            } else if (processingEnv.getTypeUtils().isSameType(type, stringType)) {
                int len = m.getAnnotation(Struct.UTF8.class).length();
                classBuilder.addField(int.class, name.toUpperCase() + "_LEN", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
                staticInit.addStatement("$L_LEN = $L", name.toUpperCase(), len);
                constr.addStatement("this.$L_Segment = arena.allocate((long)$L * capacity, 1)", name, len);
            } else {
                constr.addStatement("this.$L_Segment = arena.allocate(16L * capacity)", name);
            }
        }
        classBuilder.addStaticBlock(staticInit.build()).addMethod(constr.build());

        classBuilder.addMethod(MethodSpec.methodBuilder("at").addModifiers(Modifier.PUBLIC).addParameter(int.class, "index").returns(TypeName.get(interfaceElement.asType()))
                .addStatement("this.currentIndex = index").addStatement("return this").build());

        for (ExecutableElement m : allMethods) {
            String name = m.getSimpleName().toString();
            Optional<ExecutableElement> fm = fieldMethods.stream().filter(f -> f.getSimpleName().toString().equals(name)).findFirst();
            if (fm.isPresent()) {
                boolean isSetter = m.getParameters().size() > 0;
                TypeMirror type = isSetter ? m.getParameters().get(0).asType() : m.getReturnType();
                MethodSpec.Builder mb = MethodSpec.methodBuilder(name).addModifiers(Modifier.PUBLIC).addAnnotation(Override.class);
                if (type.getKind().isPrimitive()) {
                    if (isSetter) mb.addParameter(TypeName.get(type), "v").addStatement("$L_HANDLE.set($L_Segment, (long)currentIndex, v)", name.toUpperCase(), name);
                    else mb.returns(TypeName.get(type)).addStatement("return ($T)$L_HANDLE.get($L_Segment, (long)currentIndex)", TypeName.get(type), name.toUpperCase(), name);
                    classBuilder.addMethod(mb.build());
                } else if (processingEnv.getTypeUtils().isSameType(type, stringType)) {
                    String lenField = name.toUpperCase() + "_LEN";
                    String segmentField = name + "_Segment";
                    if (isSetter) {
                        mb.addParameter(String.class, "v")
                          .addStatement("byte[] b = v.getBytes(java.nio.charset.StandardCharsets.UTF_8)")
                          .addStatement("int cl = Math.min(b.length, $L)", lenField)
                          .addStatement("java.lang.foreign.MemorySegment.copy(java.lang.foreign.MemorySegment.ofArray(b), 0, this.$L, (long)currentIndex * $L, cl)", segmentField, lenField)
                          .addCode("if(cl < $L) {\n", lenField)
                          .addStatement("  this.$L.asSlice((long)currentIndex * $L + cl, (long)$L - cl).fill((byte)0)", segmentField, lenField, lenField)
                          .addCode("}\n");
                    } else {
                        mb.returns(String.class)
                          .addStatement("byte[] b = this.$L.asSlice((long)currentIndex * $L, (long)$L).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)", segmentField, lenField, lenField)
                          .addStatement("int l=0; while(l<b.length && b[l]!=0) l++; return new String(b, 0, l, java.nio.charset.StandardCharsets.UTF_8)");
                    }
                    classBuilder.addMethod(mb.build());
                }
            }
        }
        
        classBuilder.addMethod(MethodSpec.methodBuilder("address").addModifiers(Modifier.PUBLIC).returns(long.class).addAnnotation(Override.class).addStatement("return 0").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("segment").addModifiers(Modifier.PUBLIC).returns(MemorySegment.class).addAnnotation(Override.class).addStatement("return null").build());
        classBuilder.addMethod(MethodSpec.methodBuilder("rebase").addModifiers(Modifier.PUBLIC).addParameter(MemorySegment.class, "s").addAnnotation(Override.class).addStatement("").build());

        JavaFile.builder(packageName, classBuilder.build()).build().writeTo(processingEnv.getFiler());
    }

    private MethodSpec generateSoASimdSum(String fieldName) {
        String handleName = fieldName.toUpperCase() + "_HANDLE";
        String segmentName = fieldName + "_Segment";
        return MethodSpec.methodBuilder("sum" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1))
                .addModifiers(Modifier.PUBLIC).returns(double.class).addStatement("double total = 0")
                .addStatement("jdk.incubator.vector.VectorSpecies<Double> species = jdk.incubator.vector.DoubleVector.SPECIES_PREFERRED")
                .addStatement("int i = 0").addStatement("int upperBound = species.loopBound(capacity)")
                .addCode("for (; i < upperBound; i += species.length()) {\n")
                .addStatement("  jdk.incubator.vector.DoubleVector v = jdk.incubator.vector.DoubleVector.fromMemorySegment(species, $L, (long)i * 8, java.nio.ByteOrder.nativeOrder())", segmentName)
                .addStatement("  total += v.reduceLanes(jdk.incubator.vector.VectorOperators.ADD)")
                .addCode("}\n")
                .addCode("for (; i < capacity; i++) { total += (double)$L.get($L, (long)i); }\n", handleName, segmentName)
                .addStatement("return total").build();
    }

    private String getFullImplName(TypeMirror type, String suffix) {
        TypeElement el = (TypeElement) processingEnv.getTypeUtils().asElement(type);
        String pkg = processingEnv.getElementUtils().getPackageOf(el).getQualifiedName().toString();
        return pkg + "." + getImplBaseName(el) + suffix;
    }

    private void addStandardMethods(TypeSpec.Builder classBuilder, List<ExecutableElement> allMethods, List<ExecutableElement> fieldMethods, TypeElement interfaceElement, TypeMirror structType, TypeMirror stringType) {
        for (ExecutableElement m : allMethods) {
            String name = m.getSimpleName().toString();
            if (name.equals("address")) { classBuilder.addMethod(MethodSpec.methodBuilder("address").addModifiers(Modifier.PUBLIC).returns(long.class).addAnnotation(Override.class).addStatement("return segment.address()").build()); continue; }
            if (name.equals("segment")) { classBuilder.addMethod(MethodSpec.methodBuilder("segment").addModifiers(Modifier.PUBLIC).returns(MemorySegment.class).addAnnotation(Override.class).addStatement("return segment").build()); continue; }
            if (name.equals("rebase")) { classBuilder.addMethod(MethodSpec.methodBuilder("rebase").addModifiers(Modifier.PUBLIC).addParameter(MemorySegment.class, "s").addAnnotation(Override.class).addStatement("this.segment = s").build()); continue; }

            Optional<ExecutableElement> fm = fieldMethods.stream().filter(f -> f.getSimpleName().toString().equals(name)).findFirst();
            if (fm.isPresent()) {
                boolean isSetter = m.getParameters().size() > 0;
                TypeMirror type = isSetter ? m.getParameters().get(0).asType() : m.getReturnType();
                boolean isStruct = processingEnv.getTypeUtils().isAssignable(type, structType);
                boolean isString = processingEnv.getTypeUtils().isSameType(type, stringType);
                MethodSpec.Builder mb = MethodSpec.methodBuilder(name).addModifiers(Modifier.PUBLIC).addAnnotation(Override.class);
                if (isStruct) {
                    String n = getFullImplName(type, "Impl");
                    if (isSetter) mb.addParameter(TypeName.get(type), "v").addStatement("java.lang.foreign.MemorySegment.copy(v.segment(), 0, this.segment, $L_OFFSET, $L.LAYOUT.byteSize())", name.toUpperCase(), n);
                    else mb.returns(TypeName.get(type)).addStatement("return new $L(segment.asSlice($L_OFFSET, $L.LAYOUT.byteSize()), null)", n, name.toUpperCase(), n);
                } else if (isString) {
                    int len = fm.get().getAnnotation(Struct.UTF8.class).length();
                    if (isSetter) mb.addParameter(String.class, "v").addStatement("byte[] b = v.getBytes(java.nio.charset.StandardCharsets.UTF_8)").addStatement("int cl = Math.min(b.length, $L)", len).addStatement("java.lang.foreign.MemorySegment.copy(java.lang.foreign.MemorySegment.ofArray(b), 0, this.segment, $L_OFFSET, cl)", name.toUpperCase()).addCode("if(cl<$L){this.segment.asSlice($L_OFFSET+cl,$L-cl).fill((byte)0);}\n", len, name.toUpperCase(), len);
                    else mb.returns(String.class).addStatement("byte[] b = this.segment.asSlice($L_OFFSET, $L).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)", name.toUpperCase(), len).addStatement("int l=0; while(l<b.length && b[l]!=0) l++; return new String(b, 0, l, java.nio.charset.StandardCharsets.UTF_8)");
                } else {
                    if (isSetter) mb.addParameter(TypeName.get(type), "v").addStatement("$L_HANDLE.set(segment, 0L, v)", name.toUpperCase());
                    else mb.returns(TypeName.get(type)).addStatement("return ($T)$L_HANDLE.get(segment, 0L)", TypeName.get(type), name.toUpperCase());
                }
                classBuilder.addMethod(mb.build());
            }
        }
        classBuilder.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(MemorySegment.class, "segment").addParameter(ClassName.get("com.github.goguma9071.jvmplus.memory", "MemoryPool"), "pool").addStatement("this.segment = segment").addStatement("this.pool = pool").build());
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
}
