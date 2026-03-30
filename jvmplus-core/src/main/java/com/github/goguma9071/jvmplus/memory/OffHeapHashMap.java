package com.github.goguma9071.jvmplus.memory;

/**
 * 모든 데이터를 Off-Heap에 저장하는 고성능 해시맵.
 * @param <K> 키 타입 (Integer, Long, String 지원)
 * @param <V> 값 타입 (Struct, Primitive, String 지원)
 */
public interface OffHeapHashMap<K, V> extends AutoCloseable {
    /**
     * 키-값 쌍을 저장합니다.
     */
    void put(K key, V value);

    /**
     * 키에 해당하는 값을 가져옵니다.
     * @return 값, 없으면 null
     */
    V get(K key);

    /**
     * 키에 해당하는 데이터를 삭제합니다.
     */
    void remove(K key);

    /**
     * 현재 저장된 요소의 개수를 반환합니다.
     */
    int size();

    /**
     * 모든 데이터를 삭제합니다.
     */
    void clear();

    /**
     * 모든 자원을 수동으로 해제합니다.
     */
    void free();

    /** @deprecated try-with-resources 지원용입니다. 수동 해제 시에는 free()를 사용하세요. */
    @Override
    @Deprecated
    default void close() { free(); }
}
