package com.github.goguma9071.jvmplus.memory;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 모든 Off-Heap 구조체 인터페이스가 상속받아야 하는 마커 인터페이스.
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
     * 클래스 단위 어노테이션. 
     * 미니 컴파일러(Annotation Processor)가 이 어노테이션을 찾아서 구현체를 생성합니다.
     */
    @Retention(RetentionPolicy.RUNTIME) // 런타임에 리플렉션으로 읽어야 하므로 RUNTIME으로 변경
    @Target(ElementType.TYPE)
    @interface Type {
        int defaultCapacity() default 1000;
    }

    /**
     * 고정 길이 UTF-8 문자열 필드를 정의합니다.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface UTF8 {
        int length();
    }

    /**
     * 필드의 순서를 정의하는 어노테이션.
     */
    @Retention(RetentionPolicy.RUNTIME) // 런타임에 리플렉션으로 읽어야 하므로 RUNTIME으로 변경
    @Target(ElementType.METHOD)
    @interface Field {
        int order();
    }
}
