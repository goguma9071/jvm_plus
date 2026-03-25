package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.util.ArrayDeque;
import java.util.Deque;

public class MemoryPool {
    private final MemoryLayout layout;
    private final long slotSize;
    private final long capacity;
    
    // 실제 메모리 덩어리
    private final Arena arena;
    private final MemorySegment memoryBlock;
    
    // 사용 가능한 슬롯의 인덱스 목록 (Free List)
    private final Deque<Long> freeIndices;
    
    // 현재까지 할당된 최대 인덱스 (Bump Pointer)
    private long nextIndex = 0;

    public MemoryPool(MemoryLayout layout, long capacity) {
        this.layout = layout;
        this.slotSize = layout.byteSize();
        this.capacity = capacity;
        
        // 1. 대용량 메모리 할당 (OS에게 한 번만 요청)
        // 총 크기 = 구조체 크기 * 개수
        this.arena = Arena.ofShared();
        this.memoryBlock = arena.allocate(layout.byteSize() * capacity);
        
        // 2. 프리 리스트 초기화
        this.freeIndices = new ArrayDeque<>();
    }

    public synchronized MemorySegment allocate() {
        long index;
        
        // 1. 재사용 가능한 빈 슬롯이 있는지 확인
        if (!freeIndices.isEmpty()) {
            index = freeIndices.pop();
        } 
        // 2. 없으면 새 슬롯 개척
        else {
            if (nextIndex >= capacity) {
                throw new OutOfMemoryError("MemoryPool is full! Capacity: " + capacity);
            }
            index = nextIndex++;
        }
        
        // 3. 해당 인덱스의 메모리 주소 계산 및 반환
        // 예: 0번 슬롯 -> 0 ~ 16바이트, 1번 슬롯 -> 16 ~ 32바이트
        return memoryBlock.asSlice(index * slotSize, slotSize);
    }

    public synchronized void free(MemorySegment segment) {
        // 주소 계산: (반환된 주소 - 시작 주소) / 슬롯 크기 = 인덱스
        long offset = segment.address() - memoryBlock.address();
        
        // 유효성 검사
        if (offset < 0 || offset >= memoryBlock.byteSize() || offset % slotSize != 0) {
            throw new IllegalArgumentException("Invalid memory segment for this pool");
        }
        
        long index = offset / slotSize;
        
        // 프리 리스트에 반환
        freeIndices.push(index);
    }
    
    public void close() {
        arena.close();
    }
}
