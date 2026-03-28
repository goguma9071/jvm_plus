package com.github.goguma9071.jvmplus.memory;

/**
 * C++의 포인터(T*)와 유사한 역할을 하는 인터페이스입니다.
 * Off-Heap 메모리 상의 다른 주소를 가리키고 조작할 수 있습니다.
 *
 * @param <T> 가리키는 타입 (Struct 또는 Wrapper 타입)
 */
public interface Pointer<T> {
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
     * 포인터를 다른 타입으로 형변환합니다. (C++의 reinterpret_cast)
     * 주소는 유지하되, 역참조 시의 타입 해석만 변경합니다.
     */
    <U> Pointer<U> cast(Class<U> targetType);

    /**
     * 현재 포인터와 다른 포인터 사이의 요소 개수 차이를 계산합니다. (ptr1 - ptr2)
     * 결과 = (this.address - other.address) / sizeof(T)
     */
    long distanceTo(Pointer<T> other);

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
