package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * AoS (Array of Structs) 구조에서의 고속 순회 및 최적화된 데이터 접근을 지원하는 뷰입니다.
 */
public class StructArrayView<T extends Struct> implements Iterable<T> {
    private final MemorySegment bulkSegment;
    private final long stride;
    private final int count;
    private final T flyweight;

    public StructArrayView(MemorySegment bulkSegment, long stride, int count, T flyweight) {
        this.bulkSegment = bulkSegment;
        this.stride = stride;
        this.count = count;
        this.flyweight = flyweight;
    }

    public T get(int index) {
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException();
        flyweight.rebase(bulkSegment.asSlice(index * stride, stride));
        return flyweight;
    }

    /**
     * AoS 구조에서 특정 필드의 합계를 계산합니다.
     * (AoS는 메모리 배치가 불연속적이므로 SIMD 효율이 낮아 일반 루프를 권장합니다.)
     */
    public double sumDoubleField(long fieldOffset) {
        double total = 0;
        for (int i = 0; i < count; i++) {
            total += bulkSegment.get(ValueLayout.JAVA_DOUBLE, i * stride + fieldOffset);
        }
        return total;
    }

    public int size() { return count; }

    @Override
    public Iterator<T> iterator() {
        return new Iterator<>() {
            private int current = 0;
            @Override public boolean hasNext() { return current < count; }
            @Override public T next() { return get(current++); }
        };
    }
}
