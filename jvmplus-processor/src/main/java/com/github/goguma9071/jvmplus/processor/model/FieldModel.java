package com.github.goguma9071.jvmplus.processor.model;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeMirror;

public record FieldModel(
    String name,
    TypeMirror type,
    int order,
    long explicitOffset,
    long calculatedOffset,
    long size,
    long alignment,
    boolean isAtomic,
    boolean isUnion,
    boolean isStatic,
    boolean isRaw,
    boolean isEnum,
    boolean isArray,
    boolean isString,
    boolean isPointer,
    boolean isStruct,
    int length,
    int enumSize,
    String nestedImplName, // 중첩 구조체용
    ExecutableElement getter,
    ExecutableElement setter
) {}
