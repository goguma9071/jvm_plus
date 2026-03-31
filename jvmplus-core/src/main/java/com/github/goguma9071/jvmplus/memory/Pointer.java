package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.FunctionDescriptor;

/**
 * 오프힙 메모리를 가리키는 포인터 인터페이스입니다.
 * RAII와 네이티브 함수 호출(invoke)을 지원합니다.
 */
public interface Pointer<T> extends AutoCloseable {
    /** *p */
    T deref();

    /** *p = v */
    void set(T value);

    long address();

    <U> Pointer<U> cast(Class<U> targetType);

    long distanceTo(Pointer<T> other);

    Pointer<T> offset(long count);

    Class<T> targetType();

    /** 널 포인터 여부 확인 */
    default boolean isNull() { return address() == 0; }

    /** 다른 포인터와 주소가 동일한지 확인 */
    default boolean isSame(Pointer<?> other) { return other != null && address() == other.address(); }

    /** 이 포인터가 다른 포인터보다 메모리 상 앞서 있는지 확인 */
    default boolean isBefore(Pointer<?> other) { return other != null && address() < other.address(); }

    /** 이 포인터가 다른 포인터보다 메모리 상 뒤에 있는지 확인 */
    default boolean isAfter(Pointer<?> other) { return other != null && address() > other.address(); }

    /** 포인터를 GC 관리 모드로 전환합니다. */
    Pointer<T> auto();

    /** 
     * 포인터가 가리키는 주소를 네이티브 함수로 간주하여 호출합니다.
     * @param descriptor 함수의 인자 및 반환 타입 정의
     * @param args 전달할 인자들
     * @return 함수 실행 결과
     */
    Object invoke(FunctionDescriptor descriptor, Object... args);

    /** 포인터가 가리키는 메모리를 즉시 해제하거나 풀로 반환합니다. */
    void free();

    /** @deprecated try-with-resources 지원용입니다. 수동 해제 시에는 free()를 사용하세요. */
    @Override
    @Deprecated
    default void close() { free(); }
}
