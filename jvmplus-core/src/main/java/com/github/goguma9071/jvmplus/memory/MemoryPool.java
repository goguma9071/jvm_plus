package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 고성능 가변(Growing) Off-Heap 메모리 풀.
 * C++의 pool_resource와 유사하게 동작하며, 용량이 가득 차면 자동으로 확장됩니다.
 * 내부적으로 슬롯의 첫 8바이트를 포인터로 사용하는 Lock-free Internal Free List를 사용합니다.
 */
public class MemoryPool implements AutoCloseable {
    private final long slotSize;
    private final Arena arena;
    
    private static class Chunk {
        final MemorySegment segment;
        final long capacity;
        final AtomicLong nextIndex = new AtomicLong(0);

        Chunk(MemorySegment segment, long capacity) {
            this.segment = segment;
            this.capacity = capacity;
        }
    }

    private final List<Chunk> chunks = new ArrayList<>();
    private volatile Chunk activeChunk;
    
    // 자유 슬롯의 주소를 저장하는 헤드 포인터 (Lock-free Stack)
    private final AtomicLong freeListHead = new AtomicLong(0);

    public MemoryPool(MemoryLayout layout, long initialCapacity) {
        // 포인터(8바이트) 저장을 위해 최소 슬롯 크기를 8로 제한
        this.slotSize = Math.max(8, (layout.byteSize() + 7) & ~7);
        this.arena = Arena.ofShared();
        addNewChunk(initialCapacity);
    }

    private synchronized void addNewChunk(long capacity) {
        // 경합으로 인해 이미 다른 스레드가 확장했을 경우 방어
        if (activeChunk != null && activeChunk.nextIndex.get() < activeChunk.capacity) {
            return;
        }
        
        MemorySegment seg = arena.allocate(slotSize * capacity, 8);
        Chunk chunk = new Chunk(seg, capacity);
        chunks.add(chunk);
        activeChunk = chunk;
    }

    public MemorySegment allocate() {
        // 1. Free List에서 재사용 시도 (Zero-allocation, LIFO)
        while (true) {
            long headAddr = freeListHead.get();
            if (headAddr == 0) break;
            
            // 해당 주소에서 다음 자유 슬롯의 주소를 읽어옴
            MemorySegment headSeg = MemorySegment.ofAddress(headAddr).reinterpret(8);
            long nextAddr = headSeg.get(ValueLayout.JAVA_LONG, 0);
            
            if (freeListHead.compareAndSet(headAddr, nextAddr)) {
                MemorySegment slot = MemorySegment.ofAddress(headAddr).reinterpret(slotSize);
                slot.fill((byte) 0);
                MemoryManager.track(slot);
                return slot;
            }
        }
        
        // 2. 현재 활성 Chunk에서 새 메모리 할당
        while (true) {
            Chunk current = activeChunk;
            long index = current.nextIndex.getAndIncrement();
            if (index < current.capacity) {
                MemorySegment slot = current.segment.asSlice(index * slotSize, slotSize);
                MemoryManager.track(slot);
                return slot;
            }
            
            // 3. 현재 Chunk가 가득 차면 지수적으로 확장
            addNewChunk(current.capacity * 2);
        }
    }

    public void free(MemorySegment segment) {
        long addr = segment.address();
        if (addr == 0) return;
        
        // 1. 이 풀에 소속된 세그먼트인지 검증 (오염 방지)
        boolean belongs = false;
        synchronized (this) {
            for (Chunk c : chunks) {
                long start = c.segment.address();
                long end = start + (c.capacity * slotSize);
                if (addr >= start && addr < end) {
                    belongs = true;
                    break;
                }
            }
        }
        if (!belongs) {
            throw new IllegalArgumentException("Segment does not belong to this pool: " + Long.toHexString(addr));
        }

        while (true) {
            long oldHead = freeListHead.get();
            // 2. 간단한 Double-Free 방지: 현재 헤드와 동일한지 체크
            if (addr == oldHead) return; 

            // 슬롯의 첫 8바이트에 이전 헤드 주소를 기록 (내부 포인터 연결)
            segment.set(ValueLayout.JAVA_LONG, 0, oldHead);
            if (freeListHead.compareAndSet(oldHead, addr)) {
                return;
            }
        }
    }

    public void preallocate(long count) {
        synchronized (this) {
            Chunk current = activeChunk;
            if (current.nextIndex.get() + count > current.capacity) {
                addNewChunk(Math.max(current.capacity * 2, count));
            }
            activeChunk.nextIndex.addAndGet(count);
        }
    }

    public void clear() {
        synchronized (this) {
            for (Chunk c : chunks) {
                c.nextIndex.set(0);
            }
            freeListHead.set(0);
        }
    }
    
    public void free() {
        arena.close();
    }

    /** @deprecated try-with-resources 지원용입니다. 수동 해제 시에는 free()를 사용하세요. */
    @Override
    @Deprecated
    public void close() {
        free();
    }
}
