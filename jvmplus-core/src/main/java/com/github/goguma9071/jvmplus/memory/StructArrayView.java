package com.github.goguma9071.jvmplus.memory;

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

    public StructArrayView(MemorySegment bulkSegment, long stride, int count, T flyweight) {
        this.bulkSegment = bulkSegment;
        this.stride = stride;
        this.count = count;
        this.flyweight = flyweight;
    }

    @Override
    public T get(int index) {
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException();
        flyweight.rebase(bulkSegment.asSlice(index * stride, stride));
        return flyweight;
    }

    @Override
    public int size() { return count; }

    @Override
    public double sumDouble(String fieldName) {
        // AoS에서는 리플렉션이나 미리 정의된 오프셋 상수를 통해 필드 위치를 찾음
        try {
            String implName = flyweight.getClass().getName();
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

    @Override
    public Iterator<T> iterator() {
        return new Iterator<>() {
            private int current = 0;
            @Override public boolean hasNext() { return current < count; }
            @Override public T next() { return get(current++); }
        };
    }
}
