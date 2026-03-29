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
        /** 해당 구조체 내 모든 @NativeCall에 적용될 기본 라이브러리 파일 명 */
        String defaultLib() default "";
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Field {
        int order();
        long offset() default -1;
    }

    /** 해당 필드를 이전 필드와 동일한 메모리 위치에 배치합니다. (C++ Union) */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Union {}

    /** 모든 인스턴스가 공유하는 정적 필드를 정의합니다. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Static {}

    /** 필드에 대한 원자적 연산(CAS, AtomicAdd 등) 메서드를 생성합니다. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Atomic {}

    /** 고정 길이 UTF-8 문자열 필드를 정의합니다. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface UTF8 {
        int length();
    }

    /** 고정 길이 배열 필드를 정의합니다. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Array {
        int length();
    }

    /** 로우 바이트 데이터를 다루기 위한 버퍼 필드를 정의합니다. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Raw {
        int length();
    }

    /** 자바 Enum을 오프힙 정수값으로 매핑합니다. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Enum {
        int byteSize() default 4;
    }

    /** 외부 C 라이브러리 함수를 연결합니다. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface NativeCall {
        String name() default "";
        /** 연결할 라이브러리 명. 비어있을 경우 @Type의 defaultLib을 사용합니다. */
        String lib() default "";
    }

    // --- 기본 메서드 ---
    long address();
    MemorySegment segment();
    void rebase(MemorySegment segment);
    <T extends Struct> Pointer<T> asPointer();
    @Override void close();
}
