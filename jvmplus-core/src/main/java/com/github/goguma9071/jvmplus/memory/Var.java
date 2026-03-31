package com.github.goguma9071.jvmplus.memory;

/**
 * 오프힙 메모리에 실재하는 변수를 나타냅니다.
 * C 언어의 'int a = 10;'에서 'a'에 해당하며, 실제 값을 저장하는 공간을 상징합니다.
 */
public interface Var<T> extends AutoCloseable {
    /** 변수의 값을 가져옵니다. */
    T get();

    /** 변수에 새로운 값을 저장합니다. */
    void set(T value);

    /** 
     * 이 변수의 주소를 가리키는 포인터를 반환합니다.
     * C 언어의 주소 연산자(&)와 동일한 역할을 합니다.
     */
    Pointer<T> asPointer();

    /** 변수가 점유한 메모리를 수동으로 해제합니다. */
    void free();

    /** @deprecated try-with-resources 지원용입니다. 수동 해제 시에는 free()를 사용하세요. */
    @Override
    @Deprecated
    default void close() { free(); }
}
