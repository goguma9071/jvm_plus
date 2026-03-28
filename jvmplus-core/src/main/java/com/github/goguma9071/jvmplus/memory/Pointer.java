package com.github.goguma9071.jvmplus.memory;

/**
 * 오프힙 메모리를 가리키는 포인터 인터페이스입니다.
 * try-with-resources를 통한 RAII(자동 해제)를 지원합니다.
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

    /** 
     * 포인터를 GC 관리 모드로 전환합니다. 
     * 이 메서드가 호출되면 더 이상 수동 해제가 필요 없으며 GC가 수거합니다.
     */
    Pointer<T> auto();

    /** 포인터가 가리키는 메모리를 즉시 해제하거나 풀로 반환합니다. */
    @Override
    void close();
}
