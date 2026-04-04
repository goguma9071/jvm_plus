package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicLong;

public class MemoryPool implements AutoCloseable {
    private final long slotSize;
    private final Arena arena;
    private final StructVector<ChunkStruct> chunks;
    private ChunkStruct activeChunk;
    private MemorySegment activeSegment;
    private final AtomicLong freeListHead = new AtomicLong(0);
    private final boolean track;

    public MemoryPool(MemoryLayout layout, long initialCapacity) {
        this(layout, initialCapacity, true);
    }

    public MemoryPool(MemoryLayout layout, long initialCapacity, boolean track) {
        this.track = track;
        // 정렬을 위해 슬롯 크기를 8의 배수로 맞춤
        this.slotSize = (layout.byteSize() + 7) & ~7;
        this.arena = Arena.ofShared();
        
        Allocator vectorAllocator = new ArenaAllocator(arena, false); // 벡터 자체는 트래킹 안함
        StructVectorImpl<ChunkStruct> vectorImpl = new StructVectorImpl<>(ChunkStruct.class, 16, 24L, vectorAllocator);
        
        MemorySegment headerSeg = arena.allocate(24, 8);
        vectorImpl.setFlyweight(createManualChunkStruct(headerSeg));
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
            @Override public ChunkStruct addAndGetNextIndex(long d) {
                ChunkStruct.NEXT_INDEX_HANDLE.getAndAdd(seg, 16L, d);
                return this;
            }
        };
    }

    private synchronized void addNewChunk(long capacity) {
        if (activeChunk != null && activeChunk.nextIndex() < activeChunk.capacity()) return;
        
        // [성능 최적화] 청크 단위로만 트래킹. 64바이트(캐시라인) 정렬 강제.
        MemoryLayout chunkLayout = MemoryLayout.sequenceLayout(capacity, MemoryLayout.sequenceLayout(slotSize, ValueLayout.JAVA_BYTE))
                                              .withByteAlignment(64);
        MemorySegment dataSeg = arena.allocate(chunkLayout);
        
        // 개별 슬롯이 아닌, 이 청크 전체를 트래킹함
        if (track) MemoryManager.track(dataSeg);
        
        MemorySegment headerSeg = arena.allocate(24, 8);
        ChunkStruct chunk = createManualChunkStruct(headerSeg);
        chunk.address(dataSeg.address());
        chunk.capacity(capacity);
        chunk.nextIndex(0);
        
        chunks.add(chunk);
        activeChunk = chunks.get(chunks.size() - 1);
        activeSegment = dataSeg;
    }

    public MemorySegment allocate() {
        // 1. Free List 재사용 (트래킹 호출 없음 -> 초고속)
        while (true) {
            long headAddr = freeListHead.get();
            if (headAddr == 0) break;
            
            MemorySegment headSeg = MemorySegment.ofAddress(headAddr).reinterpret(slotSize, arena, s -> {});
            long nextAddr = headSeg.get(ValueLayout.JAVA_LONG, 0);
            
            if (freeListHead.compareAndSet(headAddr, nextAddr)) {
                headSeg.fill((byte) 0);
                return headSeg;
            }
        }
        
        // 2. 활성 청크에서 슬라이싱 (트래킹 호출 없음 -> 초고속)
        while (true) {
            ChunkStruct current = activeChunk;
            MemorySegment currentSeg = activeSegment;
            long capacity = current.capacity();
            long index = (long) ChunkStruct.NEXT_INDEX_HANDLE.getAndAdd(current.segment(), 16L, 1L);
            
            if (index < capacity) {
                return currentSeg.asSlice(index * slotSize, slotSize);
            }
            
            addNewChunk(capacity * 2);
        }
    }

    public void free(MemorySegment segment) {
        if (segment == null || segment.address() == 0) return;
        
        // 개별 슬롯 해제 시에는 프리 리스트에만 넣음. untrack 호출 안함.
        while (true) {
            long head = freeListHead.get();
            segment.set(ValueLayout.JAVA_LONG, 0, head);
            if (freeListHead.compareAndSet(head, segment.address())) break;
        }
    }

    public void clear() {
        for (int i = 0; i < chunks.size(); i++) {
            chunks.getFlyweight(i).nextIndex(0);
            freeListHead.set(0);
        }
    }
    
    public void free() {
        if (chunks != null) chunks.free();
        arena.close();
    }

    @Override public void close() { free(); }
}
