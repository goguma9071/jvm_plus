package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 고성능 Off-Heap 메모리 풀.
 * 모든 할당은 최소 8바이트 정렬을 보장합니다.
 */
public class MemoryPool {
    private final long slotSize;
    private final long capacity;
    private final Arena arena;
    private final MemorySegment memoryBlock;
    
    private final AtomicLong nextIndex = new AtomicLong(0);
    
    private static class Node {
        final long index;
        Node next;
        Node(long index) { this.index = index; }
    }
    private final AtomicReference<Node> freeList = new AtomicReference<>();

    public MemoryPool(MemoryLayout layout, long capacity) {
        // 슬롯 사이즈를 8의 배수로 맞춰서 각 객체의 시작 주소 정렬 보장
        this.slotSize = (layout.byteSize() + 7) & ~7;
        this.capacity = capacity;
        this.arena = Arena.ofShared();
        // 메모리 블록 자체도 최소 8바이트 정렬로 할당
        this.memoryBlock = arena.allocate(slotSize * capacity, 8);
    }

    public MemorySegment allocate() {
        Node oldHead;
        while ((oldHead = freeList.get()) != null) {
            if (freeList.compareAndSet(oldHead, oldHead.next)) {
                return sliceAtIndex(oldHead.index);
            }
        }
        
        long index = nextIndex.getAndIncrement();
        if (index < capacity) {
            return sliceAtIndex(index);
        }
        
        throw new OutOfMemoryError("MemoryPool is full! Capacity: " + capacity);
    }

    public void free(MemorySegment segment) {
        long offset = segment.address() - memoryBlock.address();
        if (offset < 0 || offset >= memoryBlock.byteSize() || offset % slotSize != 0) {
            throw new IllegalArgumentException("Invalid memory segment for this pool");
        }
        
        long index = offset / slotSize;
        Node newNode = new Node(index);
        Node oldHead;
        do {
            oldHead = freeList.get();
            newNode.next = oldHead;
        } while (!freeList.compareAndSet(oldHead, newNode));
    }

    private MemorySegment sliceAtIndex(long index) {
        return memoryBlock.asSlice(index * slotSize, slotSize);
    }

    public void preallocate(long count) {
        if (nextIndex.get() + count > capacity) throw new OutOfMemoryError("Cannot preallocate");
        nextIndex.addAndGet(count);
    }

    public void clear() {
        nextIndex.set(0);
        freeList.set(null);
    }
    
    public void close() {
        arena.close();
    }
}
