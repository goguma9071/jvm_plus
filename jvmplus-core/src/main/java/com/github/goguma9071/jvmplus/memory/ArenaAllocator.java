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
        return arena.allocate(size, alignment);
    }

    @Override
    public void free(MemorySegment segment) {
        // Arena는 개별 해제를 지원하지 않음
    }

    @Override
    public void close() {
        // 인자로 받은 arena는 호출자가 닫아야 하므로 여기선 닫지 않음
    }
}
