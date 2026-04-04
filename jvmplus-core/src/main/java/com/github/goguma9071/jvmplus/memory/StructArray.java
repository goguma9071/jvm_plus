package com.github.goguma9071.jvmplus.memory;

/**
 * AoS와 SoA 구조를 모두 아우르는 공통 구조체 배열 인터페이스입니다.
 */
public interface StructArray<T extends Struct> extends Iterable<T>, AutoCloseable {
    /**
     * 특정 인덱스의 요소를 가져옵니다. (안전한 새 객체 반환)
     */
    T get(int index);

    /**
     * 특정 인덱스의 요소를 가져옵니다. (내부 Flyweight 객체 재사용)
     * 주의: 반환된 객체의 상태는 다음 getFlyweight() 호출 시 변경될 수 있습니다.
     */
    default T getFlyweight(int index) { return get(index); }

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
     * 특정 필드의 합계를 SIMD로 계산합니다. (AoS/SoA 최적화 방식 자동 선택)
     * @param fieldName 필드 이름 (예: "score")
     */
    long sumLong(String fieldName);

    /**
     * 특정 필드의 전체 값을 벌크로 초기화합니다. (SIMD 최적화)
     * @param fieldName 필드 이름
     * @param value 초기화할 값
     */
    void fill(String fieldName, long value);

    /**
     * 배열에 할당된 전체 메모리를 즉시 해제합니다.
     */
    void free();

    /** @deprecated try-with-resources 지원용입니다. 수동 해제 시에는 free()를 사용하세요. */
    @Override
    @Deprecated
    default void close() { free(); }
}
