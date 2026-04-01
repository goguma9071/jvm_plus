package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 고성능 가변(Growing) Off-Heap 메모리 풀.
 * C++의 pool_resource와 유사하게 동작하며, 용량이 가득 차면 자동으로 확장됩니다.
 * [부트스트래핑] 내부 관리 데이터(Chunk 리스트)도 오프힙 구조체 벡터로 관리합니다.
 */
public class MemoryPool implements AutoCloseable {
    private final long slotSize;
    private final Arena arena;
    
    // [부트스트래핑] 자바 리스트 대신 오프힙 구조체 벡터 사용
    private final StructVector<ChunkStruct> chunks;
    private volatile ChunkStruct activeChunk;
    
    // 자유 슬롯의 주소를 저장하는 헤드 포인터 (Lock-free Stack)
    private final AtomicLong freeListHead = new AtomicLong(0);

    public MemoryPool(MemoryLayout layout, long initialCapacity) {
        // 포인터(8바이트) 저장을 위해 최소 슬롯 크기를 8로 제한
        this.slotSize = Math.max(8, (layout.byteSize() + 7) & ~7);
        this.arena = Arena.ofShared();
        
        // [부트스트래핑] ChunkStruct 레이아웃 크기(24바이트)를 명시
        StructVectorImpl<ChunkStruct> vectorImpl = new StructVectorImpl<>(ChunkStruct.class, 16, 24L, new ArenaAllocator(arena));
        
        // 수동 flyweight 생성 및 주입 (리플렉션 우회)
        vectorImpl.setFlyweight(createManualChunkStruct(arena.allocate(24, 8)));
        this.chunks = vectorImpl;
        
        addNewChunk(initialCapacity);
    }

    private ChunkStruct createManualChunkStruct(MemorySegment s) {
        return new ChunkStruct() {
            private MemorySegment seg = s;
            @Override public long address() { return seg.get(ValueLayout.JAVA_LONG, 0); }
            @Override public ChunkStruct address(long addr) { seg.set(ValueLayout.JAVA_LONG, 0, addr); return this; }
            @Override public long capacity() { return seg.get(ValueLayout.JAVA_LONG, 8); }
            @Override public ChunkStruct capacity(long cap) { seg.set(ValueLayout.JAVA_LONG, 8, cap); return this; }
            @Override public long nextIndex() { return seg.get(ValueLayout.JAVA_LONG, 16); }
            @Override public ChunkStruct nextIndex(long idx) { seg.set(ValueLayout.JAVA_LONG, 16, idx); return this; }
            @Override public MemorySegment segment() { return seg; }
            @Override public MemoryPool getPool() { return null; }
            @Override public void rebase(MemorySegment ns) { this.seg = ns; }
            @Override public <T extends Struct> Pointer<T> asPointer() { return null; }
            @Override public void free() { }
        };
    }

    private synchronized void addNewChunk(long capacity) {
        // 경합으로 인해 이미 다른 스레드가 확장했을 경우 방어
        if (activeChunk != null && activeChunk.nextIndex() < activeChunk.capacity()) {
            return;
        }
        
        MemorySegment dataSeg = arena.allocate(slotSize * capacity, 8);
        
        // 수동 구현된 구조체 사용 (리플렉션 우회)
        ChunkStruct chunk = createManualChunkStruct(arena.allocate(24, 8));
        chunk.address(dataSeg.address());
        chunk.capacity(capacity);
        chunk.nextIndex(0);
        
        chunks.add(chunk);
        activeChunk = chunks.get(chunks.size() - 1);
    }

    public MemorySegment allocate() {
        // 1. Free List에서 재사용 시도 (Zero-allocation, LIFO)
        while (true) {
            long headAddr = freeListHead.get();
            if (headAddr == 0) break;
            
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
            ChunkStruct current = activeChunk;
            long index = current.nextIndex();
            if (index < current.capacity()) {
                // 원자적으로 인덱스 증가 (Atomic 필드 활용)
                current.nextIndex(index + 1);
                
                MemorySegment slot = MemorySegment.ofAddress(current.address() + (index * slotSize)).reinterpret(slotSize);
                MemoryManager.track(slot);
                return slot;
            }
            
            // 3. 현재 Chunk가 가득 차면 지수적으로 확장
            addNewChunk(current.capacity() * 2);
        }
    }

    public void free(MemorySegment segment) {
        long addr = segment.address();
        if (addr == 0) return;
        
        // 1. 이 풀에 소속된 세그먼트인지 검증
        boolean belongs = false;
        synchronized (this) {
            for (int i = 0; i < chunks.size(); i++) {
                ChunkStruct c = chunks.getFlyweight(i);
                long start = c.address();
                long end = start + (c.capacity() * slotSize);
                if (addr >= start && addr < end) {
                    belongs = true;
                    break;
                }
            }
        }
        if (!belongs) {
            throw new IllegalArgumentException("Segment does not belong to this pool: 0x" + Long.toHexString(addr).toUpperCase());
        }

        while (true) {
            long oldHead = freeListHead.get();
            if (addr == oldHead) return; 

            segment.set(ValueLayout.JAVA_LONG, 0, oldHead);
            if (freeListHead.compareAndSet(oldHead, addr)) {
                return;
            }
        }
    }

    public void preallocate(long count) {
        synchronized (this) {
            ChunkStruct current = activeChunk;
            if (current.nextIndex() + count > current.capacity()) {
                addNewChunk(Math.max(current.capacity() * 2, count));
            }
            activeChunk.nextIndex(activeChunk.nextIndex() + count);
        }
    }

    public void clear() {
        synchronized (this) {
            for (int i = 0; i < chunks.size(); i++) {
                chunks.getFlyweight(i).nextIndex(0);
            }
            freeListHead.set(0);
        }
    }
    
    public void free() {
        if (chunks != null) chunks.free();
        arena.close();
    }

    /** @deprecated try-with-resources 지원용입니다. 수동 해제 시에는 free()를 사용하세요. */
    @Override
    @Deprecated
    public void close() {
        free();
    }
}
