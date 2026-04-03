package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

public class ArenaAllocator implements Allocator {
    private final Arena arena;
    private final boolean track;

    public ArenaAllocator(Arena arena) {
        this(arena, true);
    }

    public ArenaAllocator(Arena arena, boolean track) {
        this.arena = arena;
        this.track = track;
    }

    @Override
    public MemorySegment allocate(long byteSize, long byteAlignment) {
        MemorySegment seg = arena.allocate(byteSize, byteAlignment);
        if (track) {
            MemoryManager.track(seg);
        }
        return seg;
    }

    @Override
    public void free(MemorySegment segment) {
        if (track) {
            MemoryManager.untrack(segment);
        }
    }

    @Override
    public void free() {
        // 호출자가 arena를 닫아야 함
    }
}
