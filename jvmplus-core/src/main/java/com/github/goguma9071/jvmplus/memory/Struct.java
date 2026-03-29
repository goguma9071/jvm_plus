package com.github.goguma9071.jvmplus.memory;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.foreign.MemorySegment;

/**
 * JVM Plus 구조체 정의를 위한 핵심 어노테이션 모음입니다.
 */
public interface Struct extends AutoCloseable {
    
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Type {
        String defaultLib() default "";
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Field {
        int order();
        long offset() default -1;
    }

    /** 비트 단위 필드를 정의합니다. (예: int flag : 1) */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface BitField {
        int bits();
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Union {}

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Static {}

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Atomic {}

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface UTF8 {
        int length();
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Array {
        int length();
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Raw {
        int length();
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Enum {
        int byteSize() default 4;
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface NativeCall {
        String name() default "";
        String lib() default "";
    }

    long address();
    MemorySegment segment();
    void rebase(MemorySegment segment);
    <T extends Struct> Pointer<T> asPointer();
    @Override void close();
}
