package com.github.goguma9071.jvmplus.memory;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 모든 Off-Heap 구조체 인터페이스의 마커 인터페이스입니다.
 * 이 인터페이스를 구현하는 인터페이스는 @Struct.Type으로 어노테이션되어야 합니다.
 * <p>
 * Off-Heap 메모리에서 구조체처럼 동작하며, GC의 영향을 받지 않고 직접 메모리를 제어합니다.
 */
public interface Struct extends AutoCloseable {
    // 런타임에 실제 메모리 주소를 얻어오기 위한 내부 메소드
    long address();

    /**
     * 구조체가 관리하는 실제 메모리 세그먼트를 반환합니다.
     */
    java.lang.foreign.MemorySegment segment();

    /**
     * 기존 객체가 가리키는 메모리 세그먼트를 변경합니다.
     */
    void rebase(java.lang.foreign.MemorySegment segment);

    /**
     * 할당된 메모리를 즉시 해제합니다.
     */
    @Override
    void close();
    
    /**
     * 현재 구조체 객체 자신을 가리키는 포인터를 반환합니다.
     */
    <T extends Struct> Pointer<T> asPointer();

    /**
     * 클래스 단위 어노테이션.
     * 미니 컴파일러(Annotation Processor)가 이 어노테이션을 찾아서 구현체를 생성합니다.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Type {
        int defaultCapacity() default 1;
    }

    /**
     * 필드 단위 어노테이션.
     * 구조체의 필드를 나타내며, 순서(order)를 통해 메모리 레이아웃 순서를 결정합니다.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Field {
        int order();
    }

    /**
     * 고정 길이 UTF-8 문자열 필드를 나타냅니다.
     * 바이트 길이를 지정하여 해당 길이만큼의 메모리를 할당합니다.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface UTF8 {
        int length();
    }

    /**
     * 원시 타입 고정 길이 배열 필드를 나타냅니다.
     * 배열의 길이를 지정합니다.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Array {
        int length();
    }
}
