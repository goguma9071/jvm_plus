package com.github.goguma9071.jvmplus.memory;

/**
 * AoS와 SoA 구조를 모두 아우르는 공통 구조체 배열 인터페이스입니다.
 */
public interface StructArray<T extends Struct> extends Iterable<T>, AutoCloseable {
    /**
     * 특정 인덱스의 요소를 가져옵니다. (Flyweight 재사용)
     */
    T get(int index);

    /**
     * 배열의 전체 크기를 반환합니다.
     */
    int size();

    /**
     * 특정 필드의 합계를 SIMD로 계산합니다. (AoS/SoA 최적화 방식 자동 선택)
     * @param fieldName 필드 이름 (예: "x", "hp")
     */
    double sumDouble(String fieldName);

    /**
     * 배열에 할당된 전체 메모리를 즉시 해제합니다.
     */
    @Override
    void close();
}
