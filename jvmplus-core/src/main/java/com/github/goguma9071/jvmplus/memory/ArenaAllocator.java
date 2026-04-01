package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * 자바 Arena를 직접 사용하는 할당자입니다.
 */
public class ArenaAllocator implements Allocator {
    private final Arena arena;

    public ArenaAllocator(Arena arena) {
        this.arena = arena;
    }

    @Override
    public MemorySegment allocate(long size, long alignment) {
        MemorySegment seg = arena.allocate(size, alignment);
        MemoryManager.track(seg);
        return seg;
    }

    @Override
    public void free(MemorySegment segment) {
        MemoryManager.untrack(segment);
    }

    @Override
    public void free() {
        // 인자로 받은 arena는 호출자가 닫아야 하므로 여기선 닫지 않음
    }
}
