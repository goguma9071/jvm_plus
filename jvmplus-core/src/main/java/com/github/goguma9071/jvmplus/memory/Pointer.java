package com.github.goguma9071.jvmplus.memory;

/**
 * C++의 포인터(T*)와 유사한 역할을 하는 인터페이스입니다.
 * Off-Heap 메모리 상의 다른 구조체 주소를 가리키고 조작할 수 있습니다.
 *
 * @param <T> 가리키는 구조체 타입
 */
public interface Pointer<T extends Struct> {
    /**
     * 포인터가 가리키는 실제 구조체 객체를 반환합니다. (역참조, *ptr)
     * @return 가리키는 객체, 주소가 0인 경우 null
     */
    T deref();

    /**
     * 포인터가 가리키는 주소를 다른 구조체의 주소로 변경합니다. (ptr = &value)
     * @param value 대입할 구조체 객체
     */
    void set(T value);

    /**
     * 현재 가리키고 있는 메모리 주소를 반환합니다.
     */
    long address();

    /**
     * 현재 위치에서 count만큼 떨어진 위치의 새 포인터를 반환합니다. (ptr + count)
     * 주소는 count * sizeof(T) 만큼 이동합니다.
     */
    Pointer<T> offset(long count);

    /**
     * 다음 요소를 가리키는 포인터를 반환합니다. (ptr + 1)
     */
    default Pointer<T> next() { return offset(1); }

    /**
     * 이전 요소를 가리키는 포인터를 반환합니다. (ptr - 1)
     */
    default Pointer<T> prev() { return offset(-1); }

    /**
     * 널 포인터 여부를 확인합니다.
     */
    default boolean isNull() {
        return address() == 0;
    }

    /**
     * 포인터가 가리키는 타입의 정보를 반환합니다.
     */
    Class<T> targetType();
}
