package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Iterator;

/**
 * StructVector의 기본 구현체.
 * 내부적으로 MemorySegment를 관리하며 용량 부족 시 자동으로 확장합니다.
 */
public class StructVectorImpl<T extends Struct> implements StructVector<T> {
    private final Arena arena;
    private MemorySegment segment;
    private final long elementSize;
    private final long alignment;
    private final T flyweight;
    
    private int size = 0;
    private int capacity;

    public StructVectorImpl(Class<T> type, int initialCapacity) {
        this.arena = Arena.ofShared();
        
        try {
            // Impl 클래스의 정적 필드 LAYOUT에 직접 접근
            String implClassName = type.getName().replace('$', '_') + "Impl";
            Class<?> implClass = Class.forName(implClassName);
            java.lang.foreign.GroupLayout layout = (java.lang.foreign.GroupLayout) implClass.getField("LAYOUT").get(null);
            
            this.elementSize = layout.byteSize();
            this.alignment = layout.byteAlignment();
            this.flyweight = MemoryManager.createEmptyStruct(type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize StructVector for type: " + type.getName(), e);
        }
        
        this.capacity = initialCapacity;
        this.segment = arena.allocate(elementSize * capacity, alignment);
    }

    @Override
    public void add(T value) {
        ensureCapacity(size + 1);
        long offset = (long) size * elementSize;
        MemorySegment.copy(value.segment(), 0, segment, offset, elementSize);
        size++;
    }

    @Override
    public T get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        segment.asSlice((long) index * elementSize, elementSize); // 스코프 체크
        flyweight.rebase(segment.asSlice((long) index * elementSize, elementSize));
        return flyweight;
    }

    @Override
    public void set(int index, T value) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        long offset = (long) index * elementSize;
        MemorySegment.copy(value.segment(), 0, segment, offset, elementSize);
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public void clear() {
        size = 0;
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity > capacity) {
            int newCapacity = Math.max(capacity * 2, minCapacity);
            MemorySegment newSegment = arena.allocate(elementSize * newCapacity, alignment);
            MemorySegment.copy(segment, 0, newSegment, 0, elementSize * size);
            // 기존 segment는 arena가 닫힐 때까지 유지됨 (FFM Arena 제약)
            // 잦은 확장이 우려된다면 별도의 Arena 교체 로직이 필요함
            this.segment = newSegment;
            this.capacity = newCapacity;
        }
    }

    @Override
    public void close() {
        arena.close();
    }

    @Override
    public Iterator<T> iterator() {
        return new Iterator<>() {
            private int current = 0;
            @Override public boolean hasNext() { return current < size; }
            @Override public T next() { return get(current++); }
        };
    }
}
