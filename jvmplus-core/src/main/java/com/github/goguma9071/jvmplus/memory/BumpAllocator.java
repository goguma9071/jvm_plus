package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 포인터만 전진시키는 극속 할당자입니다.
 * 개별 free()는 지원하지 않으며, close() 시 리전 전체를 반환합니다.
 */
public class BumpAllocator implements Allocator {
    private final Arena arena;
    private final MemorySegment root;
    private final AtomicLong offset = new AtomicLong(0);
    private final long capacity;

    public BumpAllocator(long capacity) {
        this.arena = Arena.ofShared();
        this.root = arena.allocate(capacity, 8);
        this.capacity = capacity;
    }

    @Override
    public MemorySegment allocate(long size, long alignment) {
        long current;
        long next;
        do {
            current = offset.get();
            // 정렬 맞춤
            long aligned = (current + alignment - 1) & ~(alignment - 1);
            next = aligned + size;
            if (next > capacity) throw new OutOfMemoryError("BumpAllocator full");
        } while (!offset.compareAndSet(current, next));

        return root.asSlice(current, size);
    }

    @Override
    public void free(MemorySegment segment) {
        // BumpAllocator는 개별 해제를 지원하지 않음 (No-op)
    }

    @Override
    public void free() {
        arena.close();
    }
}
