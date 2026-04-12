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
        /** 
         * 구조체의 메모리 정렬(Alignment) 단위를 설정합니다. 
         * 0: 자동 (데이터 타입에 따른 자연 정렬 및 자동 패딩)
         * 1: 패킹 (패딩 없이 모든 필드를 밀착)
         * N: N바이트 단위로 강제 정렬
         */
        long alignment() default 0;
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Field {
        int order();
        long offset() default -1;
        /** 개별 필드의 정렬 단위를 설정합니다. -1이면 구조체 설정을 따릅니다. */
        long alignment() default -1;
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

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @interface IgnoreLeak {}

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface NativeCall {
        String name() default "";
        String lib() default "";
    }

    long address();
    MemorySegment segment();
    MemoryPool getPool();
    void rebase(MemorySegment segment);
    <T extends Struct> Pointer<T> asPointer();

    /** 이 구조체를 GC 관리 모드로 전환합니다. (편의 기능) */
    default <T extends Struct> T auto() { throw new UnsupportedOperationException(); }

    /** 수동 메모리 해제 (C++ 스타일) */
    void free();

    /** @deprecated try-with-resources 지원용입니다. 수동 해제 시에는 free()를 사용하세요. */
    @Override
    @Deprecated
    default void close() { free(); }
}
