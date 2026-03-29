package com.github.goguma9071.jvmplus.processor;

import com.github.goguma9071.jvmplus.memory.Struct;
import com.github.goguma9071.jvmplus.processor.model.*;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.*;
import java.util.stream.Collectors;

public class JPCAnalyzer {
    private final Elements elementUtils;
    private final Types typeUtils;
    private final JPCDiagnostic diag;
    private final TypeMirror structType;
    private final TypeMirror stringType;
    private final TypeMirror pointerType;

    public JPCAnalyzer(Elements elementUtils, Types typeUtils, JPCDiagnostic diag) {
        this.elementUtils = elementUtils;
        this.typeUtils = typeUtils;
        this.diag = diag;
        this.structType = elementUtils.getTypeElement("com.github.goguma9071.jvmplus.memory.Struct").asType();
        this.stringType = elementUtils.getTypeElement("java.lang.String").asType();
        this.pointerType = elementUtils.getTypeElement("com.github.goguma9071.jvmplus.memory.Pointer").asType();
    }

    public Optional<StructModel> analyze(TypeElement interfaceElement) {
        String packageName = elementUtils.getPackageOf(interfaceElement).getQualifiedName().toString();
        String fullName = interfaceElement.getQualifiedName().toString();
        String relativeName = packageName.isEmpty() ? fullName : fullName.substring(packageName.length() + 1);
        String implBaseName = relativeName.replace('.', '_');

        Struct.Type structTypeAnn = interfaceElement.getAnnotation(Struct.Type.class);
        String defaultLib = structTypeAnn != null ? structTypeAnn.defaultLib() : "";

        List<ExecutableElement> allMethods = elementUtils.getAllMembers(interfaceElement).stream()
            .filter(e -> e.getKind() == ElementKind.METHOD)
            .map(e -> (ExecutableElement) e)
            .collect(Collectors.toList());

        Map<String, List<ExecutableElement>> nameGroups = allMethods.stream()
            .collect(Collectors.groupingBy(m -> m.getSimpleName().toString()));

        List<String> fieldNames = nameGroups.keySet().stream()
            .filter(name -> nameGroups.get(name).stream().anyMatch(m -> m.getAnnotation(Struct.Field.class) != null))
            .sorted(Comparator.comparingInt(name -> {
                return nameGroups.get(name).stream()
                    .filter(m -> m.getAnnotation(Struct.Field.class) != null)
                    .findFirst().get().getAnnotation(Struct.Field.class).order();
            }))
            .collect(Collectors.toList());

        List<FieldModel> fieldModels = new ArrayList<>();
        long currentOffset = 0;
        long lastFieldOffset = 0;
        long staticOffset = 0;
        boolean hasStatic = false;
        boolean hasError = false;

        for (String name : fieldNames) {
            List<ExecutableElement> ms = nameGroups.get(name);
            ExecutableElement getter = ms.stream().filter(m -> m.getParameters().isEmpty()).findFirst().orElse(null);
            ExecutableElement setter = ms.stream().filter(m -> !m.getParameters().isEmpty()).findFirst().orElse(null);

            if (getter == null) continue;

            Struct.Field fieldAnn = getter.getAnnotation(Struct.Field.class);
            if (fieldAnn == null && setter != null) fieldAnn = setter.getAnnotation(Struct.Field.class);
            
            boolean isAtomic = ms.stream().anyMatch(m -> m.getAnnotation(Struct.Atomic.class) != null);
            boolean isUnion = ms.stream().anyMatch(m -> m.getAnnotation(Struct.Union.class) != null);
            boolean isStatic = ms.stream().anyMatch(m -> m.getAnnotation(Struct.Static.class) != null);
            boolean isRaw = ms.stream().anyMatch(m -> m.getAnnotation(Struct.Raw.class) != null);
            boolean isEnum = ms.stream().anyMatch(m -> m.getAnnotation(Struct.Enum.class) != null);
            boolean isArray = ms.stream().anyMatch(m -> m.getAnnotation(Struct.Array.class) != null);
            
            TypeMirror type = getter.getReturnType();
            boolean isString = typeUtils.isSameType(type, stringType);
            boolean isPointer = typeUtils.isAssignable(typeUtils.erasure(type), pointerType);
            boolean isStruct = typeUtils.isAssignable(type, structType);
            
            if (isStatic) hasStatic = true;

            long alignment = getNaturalAlignment(getter, isStruct, isRaw, isAtomic);
            long size = getByteSize(getter, isStruct, isRaw, isArray, isString, isEnum, alignment);

            long offsetVar = isStatic ? staticOffset : currentOffset;
            
            if (isAtomic && (offsetVar % size != 0) && !isUnion && !isStatic) {
                long paddingNeeded = size - (offsetVar % size);
                String fix = String.format(
                    "// Add this padding to align '%s'\n@Struct.Field(order = %d) byte[] _pad_%s = new byte[%d];\n@Struct.Atomic @Struct.Field(order = %d) %s %s();",
                    name, fieldAnn.order(), name, paddingNeeded, fieldAnn.order() + 1, type.toString(), name
                );
                
                diag.error(getter, "J001", "misaligned atomic field '" + name + "'",
                    offsetVar, size, 
                    "Atomic fields MUST be aligned to their natural size for hardware safety.",
                    fix);
                hasError = true;
            }

            if (isUnion) {
                offsetVar = lastFieldOffset;
            } else if (fieldAnn.offset() != -1) {
                offsetVar = fieldAnn.offset();
            } else {
                if (offsetVar % alignment != 0) {
                    offsetVar += (alignment - (offsetVar % alignment));
                }
            }

            String nestedImpl = "";
            if (isStruct) {
                TypeElement te = (TypeElement) typeUtils.asElement(type);
                String pkg = te == null ? "" : elementUtils.getPackageOf(te).getQualifiedName().toString();
                String tName = te == null ? "" : te.getQualifiedName().toString();
                String rel = pkg.isEmpty() ? tName : tName.substring(pkg.length() + 1);
                nestedImpl = pkg + "." + rel.replace('.', '_') + "Impl";
            }

            fieldModels.add(new FieldModel(
                name, type, fieldAnn.order(), fieldAnn.offset(),
                offsetVar, size, alignment, isAtomic, isUnion, isStatic,
                isRaw, isEnum, isArray, isString, isPointer, isStruct,
                getLength(getter), getEnumSize(getter), nestedImpl, getter, setter
            ));

            if (!isUnion) {
                if (isStatic) staticOffset = offsetVar + size;
                else {
                    lastFieldOffset = offsetVar;
                    currentOffset = offsetVar + size;
                }
            }
        }

        if (hasError) return Optional.empty();

        List<ExecutableElement> nativeCalls = allMethods.stream()
            .filter(m -> m.getAnnotation(Struct.NativeCall.class) != null)
            .collect(Collectors.toList());

        return Optional.of(new StructModel(packageName, interfaceElement.getQualifiedName().toString(), implBaseName, fieldModels, nativeCalls, hasStatic, defaultLib));
    }

    private long getNaturalAlignment(ExecutableElement m, boolean isStruct, boolean isRaw, boolean isAtomic) {
        if (isRaw) return 8;
        TypeMirror type = m.getReturnType();
        long baseAlign = 8;
        if (!isStruct) {
            baseAlign = switch (type.getKind()) {
                case INT, FLOAT -> 4;
                case LONG, DOUBLE -> 8;
                case BYTE, BOOLEAN -> 1;
                case CHAR, SHORT -> 2;
                default -> 8;
            };
        }
        if (isAtomic) return Math.max(baseAlign, getSimpleSize(type));
        return baseAlign;
    }

    private long getSimpleSize(TypeMirror type) {
        return switch (type.getKind()) {
            case INT, FLOAT -> 4;
            case LONG, DOUBLE -> 8;
            case BYTE, BOOLEAN -> 1;
            case CHAR, SHORT -> 2;
            default -> 8;
        };
    }

    private long getByteSize(ExecutableElement m, boolean isStruct, boolean isRaw, boolean isArray, boolean isString, boolean isEnum, long alignment) {
        if (isRaw) return m.getAnnotation(Struct.Raw.class).length();
        if (isString) return m.getAnnotation(Struct.UTF8.class).length();
        if (isArray) {
            int len = m.getAnnotation(Struct.Array.class).length();
            return getSimpleSize(m.getReturnType()) * len;
        }
        if (isEnum) return m.getAnnotation(Struct.Enum.class).byteSize();
        return alignment;
    }

    private int getLength(ExecutableElement m) {
        if (m.getAnnotation(Struct.Raw.class) != null) return m.getAnnotation(Struct.Raw.class).length();
        if (m.getAnnotation(Struct.UTF8.class) != null) return m.getAnnotation(Struct.UTF8.class).length();
        if (m.getAnnotation(Struct.Array.class) != null) return m.getAnnotation(Struct.Array.class).length();
        return 0;
    }

    private int getEnumSize(ExecutableElement m) {
        return m.getAnnotation(Struct.Enum.class) != null ? m.getAnnotation(Struct.Enum.class).byteSize() : 0;
    }
}
