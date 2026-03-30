package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.MemorySegment;

/**
 * 오프힙 메모리 할당 전략을 정의하는 인터페이스입니다.
 * C++의 std::pmr::memory_resource와 유사한 역할을 합니다.
 */
public interface Allocator extends AutoCloseable {
    /**
     * 요청된 크기와 정렬에 맞는 메모리 세그먼트를 할당합니다.
     */
    MemorySegment allocate(long size, long alignment);

    /**
     * 할당된 메모리를 반환합니다. 할당자 전략에 따라 무시될 수 있습니다.
     */
    void free(MemorySegment segment);

    /** 할당자 전체 자원 해제 */
    void free();

    /** @deprecated try-with-resources 지원용입니다. 수동 해제 시에는 free()를 사용하세요. */
    @Override
    @Deprecated
    default void close() { free(); }
}
