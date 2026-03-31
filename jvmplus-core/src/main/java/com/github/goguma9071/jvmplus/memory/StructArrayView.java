package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * AoS (Array of Structs) 구조의 StructArray 구현체입니다.
 */
public class StructArrayView<T extends Struct> implements StructArray<T> {
    private final MemorySegment bulkSegment;
    private final long stride;
    private final int count;
    private final T flyweight;
    private final MemorySegment originalFlyweightSegment;
    private final Arena arena;
    private final Class<T> type;

    @SuppressWarnings("unchecked")
    public StructArrayView(MemorySegment bulkSegment, long stride, int count, T flyweight, Arena arena) {
        this.bulkSegment = bulkSegment;
        this.stride = stride;
        this.count = count;
        this.flyweight = flyweight;
        this.originalFlyweightSegment = flyweight.segment();
        this.arena = arena;
        // 타입 캐싱으로 get() 성능 최적화
        this.type = (Class<T>) flyweight.getClass().getInterfaces()[0];
    }

    @Override
    public T get(int index) {
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException();
        T obj = MemoryManager.createEmptyStruct(type);
        obj.rebase(bulkSegment.asSlice(index * stride, stride));
        return obj;
    }

    @Override
    public T getFlyweight(int index) {
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException();
        flyweight.rebase(bulkSegment.asSlice(index * stride, stride));
        return flyweight;
    }

    @Override
    public int size() { return count; }

    @Override
    public double sumDouble(String fieldName) {
        try {
            long offset = flyweight.getClass().getField(fieldName.toUpperCase() + "_OFFSET").getLong(null);
            
            double total = 0;
            for (int i = 0; i < count; i++) {
                total += bulkSegment.get(ValueLayout.JAVA_DOUBLE, i * stride + offset);
            }
            return total;
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate sum for field: " + fieldName, e);
        }
    }

    /** 
     * 전체 데이터가 담긴 연속된 메모리 세그먼트를 반환합니다. (Zero-copy I/O용)
     */
    public MemorySegment segment() {
        return bulkSegment;
    }

    @Override
    public void free() {
        // flyweight를 원래의 세그먼트/풀 상태로 안전하게 복구 후 해제 시도 (Well-written logic)
        if (flyweight != null && originalFlyweightSegment != null) {
            flyweight.rebase(originalFlyweightSegment);
            flyweight.free();
        }

        // 벌크 메모리 추적 해제
        com.github.goguma9071.jvmplus.memory.MemoryManager.untrack(bulkSegment);
        
        // 벌크 메모리 해제
        if (arena != null) {
            arena.close();
        }
    }

    /** @deprecated try-with-resources 지원용입니다. 수동 해제 시에는 free()를 사용하세요. */
    @Override
    @Deprecated
    public void close() {
        free();
    }

    @Override
    public Iterator<T> iterator() {
        return new Iterator<>() {
            private int current = 0;
            @Override public boolean hasNext() { return current < count; }
            @Override public T next() { return get(current++); }
        };
    }
}
