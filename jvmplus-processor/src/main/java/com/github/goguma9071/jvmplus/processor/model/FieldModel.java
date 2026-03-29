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
    boolean isBitField,
    int length,
    int enumSize,
    int bitCount,    // 비트 개수
    int bitOffset,   // 저장 공간 내 비트 시작 위치
    String nestedImplName,
    String bitFieldBackingName, // 비트 필드가 속한 실제 레이아웃 필드 명
    ExecutableElement getter,
    ExecutableElement setter
) {}
