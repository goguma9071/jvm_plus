package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.MemorySegment;

/**
 * 특정 타입의 MemoryPool을 사용하는 할당자입니다.
 */
public class PoolAllocator implements Allocator {
    private final MemoryPool pool;

    public PoolAllocator(MemoryPool pool) {
        this.pool = pool;
    }

    @Override
    public MemorySegment allocate(long size, long alignment) {
        // Pool은 고정 사이즈(slotSize)를 다루므로 크기 검증이 필요할 수 있으나 
        // 여기선 기본 동작만 구현
        return pool.allocate();
    }

    @Override
    public void free(MemorySegment segment) {
        pool.free(segment);
    }

    @Override
    public void free() {
        // Pool의 수명은 MemoryManager가 관리하므로 여기선 닫지 않음
    }
}
