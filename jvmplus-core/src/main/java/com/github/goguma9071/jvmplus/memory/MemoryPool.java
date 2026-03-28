package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 고성능 Off-Heap 메모리 풀.
 * Lock-free 스택을 사용하여 멀티스레드 환경에서 경합을 최소화하고 성능을 극대화합니다.
 */
public class MemoryPool {
    private final long slotSize;
    private final long capacity;
    private final Arena arena;
    private final MemorySegment memoryBlock;
    
    // 할당된 최대 인덱스를 관리하는 Bump Pointer
    private final AtomicLong nextIndex = new AtomicLong(0);
    
    // 해제된 인덱스들을 관리하는 Lock-free 프리리스트 (스택 구조)
    private static class Node {
        final long index;
        Node next;
        Node(long index) { this.index = index; }
    }
    private final AtomicReference<Node> freeList = new AtomicReference<>();

    public MemoryPool(MemoryLayout layout, long capacity) {
        this.slotSize = layout.byteSize();
        this.capacity = capacity;
        this.arena = Arena.ofShared();
        // 메모리 정렬을 보장하며 할당
        this.memoryBlock = arena.allocate(slotSize * capacity, layout.byteAlignment());
    }

    public MemorySegment allocate() {
        // 1. 프리리스트에서 재사용 가능한 슬롯 시도 (CAS 루프)
        Node oldHead;
        while ((oldHead = freeList.get()) != null) {
            if (freeList.compareAndSet(oldHead, oldHead.next)) {
                return sliceAtIndex(oldHead.index);
            }
        }
        
        // 2. 프리리스트가 비어있으면 Bump Pointer 사용
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
        
        // 3. 프리리스트에 반환 (CAS 루프)
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

    /**
     * 지정된 개수만큼 메모리를 미리 확보(범프 포인터 전진)합니다.
     */
    public void preallocate(long count) {
        if (nextIndex.get() + count > capacity) {
            throw new OutOfMemoryError("Cannot preallocate beyond capacity");
        }
        nextIndex.addAndGet(count);
    }

    /**
     * 풀의 모든 할당 상태를 초기화합니다.
     * 주의: 현재 이 풀을 통해 할당된 모든 인스턴스가 무효화됩니다.
     */
    public void clear() {
        nextIndex.set(0);
        freeList.set(null);
    }
    
    public void close() {
        arena.close();
    }
}
