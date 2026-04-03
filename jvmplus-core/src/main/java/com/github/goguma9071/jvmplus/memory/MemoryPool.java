package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 고성능 가변 Off-Heap 메모리 풀.
 * 모든 할당은 최초 할당된 세그먼트로부터의 슬라이싱(Slicing)을 통해 이루어집니다.
 * 이 방식은 부동의 정렬(Alignment) 정보를 완벽히 상속받아 Misaligned Access를 원천 차단합니다.
 */
public class MemoryPool implements AutoCloseable {
    private final long slotSize;
    private final Arena arena;
    private final StructVector<ChunkStruct> chunks;
    private volatile ChunkStruct activeChunk;
    private volatile MemorySegment activeSegment; // 현재 활성 청크의 세그먼트 직접 참조
    private final AtomicLong freeListHead = new AtomicLong(0);

    public MemoryPool(MemoryLayout layout, long initialCapacity) {
        // 정렬을 위해 슬롯 크기를 8의 배수로 맞춤
        this.slotSize = (layout.byteSize() + 7) & ~7;
        this.arena = Arena.ofShared();
        
        // 부트스트래핑용 벡터 생성 (24바이트 명시)
        StructVectorImpl<ChunkStruct> vectorImpl = new StructVectorImpl<>(ChunkStruct.class, 16, 24L, new ArenaAllocator(arena));
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
            @Override public ChunkStruct addAndGetNextIndex(long d) {
                ChunkStruct.NEXT_INDEX_HANDLE.getAndAdd(seg, 16L, d);
                return this;
            }
        };
    }

    private synchronized void addNewChunk(long capacity) {
        if (activeChunk != null && activeChunk.nextIndex() < activeChunk.capacity()) return;
        
        // 전체 청크 레이아웃 할당 (8바이트 정렬 강제)
        MemoryLayout chunkLayout = MemoryLayout.sequenceLayout(capacity, MemoryLayout.sequenceLayout(slotSize, ValueLayout.JAVA_BYTE))
                                              .withByteAlignment(8);
        MemorySegment dataSeg = arena.allocate(chunkLayout);
        
        ChunkStruct chunk = createManualChunkStruct(arena.allocate(24, 8));
        chunk.address(dataSeg.address());
        chunk.capacity(capacity);
        chunk.nextIndex(0);
        
        chunks.add(chunk);
        activeChunk = chunks.get(chunks.size() - 1);
        activeSegment = dataSeg; // 원본 세그먼트 저장 (슬라이싱용)
    }

    public MemorySegment allocate() {
        // 1. Free List 재사용
        while (true) {
            long headAddr = freeListHead.get();
            if (headAddr == 0) break;
            
            // 프리 리스트의 메모리도 원래 정렬 정보를 복원해야 함 (여기서는 최소 8로 간주)
            MemorySegment headSeg = MemorySegment.ofAddress(headAddr).reinterpret(slotSize, arena, s -> {});
            long nextAddr = headSeg.get(ValueLayout.JAVA_LONG, 0);
            
            if (freeListHead.compareAndSet(headAddr, nextAddr)) {
                headSeg.fill((byte) 0);
                MemoryManager.track(headSeg);
                return headSeg;
            }
        }
        
        // 2. 활성 청크에서 슬라이싱
        while (true) {
            ChunkStruct current = activeChunk;
            MemorySegment currentSeg = activeSegment;
            long capacity = current.capacity();
            long index = (long) ChunkStruct.NEXT_INDEX_HANDLE.getAndAdd(current.segment(), 16L, 1L);
            
            if (index < capacity) {
                // [핵심] 주소 계산 대신 asSlice를 사용하여 정렬 정보를 완벽히 유지
                MemorySegment slot = currentSeg.asSlice(index * slotSize, slotSize);
                MemoryManager.track(slot);
                return slot;
            }
            
            addNewChunk(capacity * 2);
        }
    }

    public void free(MemorySegment segment) {
        long addr = segment.address();
        if (addr == 0) return;
        
        while (true) {
            long oldHead = freeListHead.get();
            if (addr == oldHead) return; 
            segment.set(ValueLayout.JAVA_LONG, 0, oldHead);
            if (freeListHead.compareAndSet(oldHead, addr)) return;
        }
    }

    public void preallocate(long count) {
        synchronized (this) {
            if (activeChunk.nextIndex() + count > activeChunk.capacity()) {
                addNewChunk(Math.max(activeChunk.capacity() * 2, count));
            }
            activeChunk.nextIndex(activeChunk.nextIndex() + count);
        }
    }

    public void clear() {
        synchronized (this) {
            for (int i = 0; i < chunks.size(); i++) chunks.getFlyweight(i).nextIndex(0);
            freeListHead.set(0);
        }
    }
    
    public void free() {
        if (chunks != null) chunks.free();
        arena.close();
    }

    @Override public void close() { free(); }
}
