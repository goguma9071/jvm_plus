package com.github.goguma9071.jvmplus.memory;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 모든 Off-Heap 구조체 인터페이스가 상속받아야 하는 마커 인터페이스.
 */
public interface Struct {
    // 런타임에 실제 메모리 주소를 얻어오기 위한 내부 메소드
    long address();
    
    /**
     * 클래스 단위 어노테이션. 
     * 미니 컴파일러(Annotation Processor)가 이 어노테이션을 찾아서 구현체를 생성합니다.
     */
    @Retention(RetentionPolicy.CLASS) // 컴파일 타임까지만 유지되면 됨
    @Target(ElementType.TYPE)
    @interface Type {
        int defaultCapacity() default 1000;
    }

    /**
     * 필드의 순서를 정의하는 어노테이션.
     */
    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.METHOD)
    @interface Field {
        int order();
    }
}
