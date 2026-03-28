package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Iterator;

/**
 * StructVector의 기본 구현체.
 * 구조체, 기본 타입, 문자열을 지원하며 자동 확장, 삭제, 오프힙 정렬 기능을 제공합니다.
 */
public class StructVectorImpl<T> implements StructVector<T> {
    private final Arena arena;
    private MemorySegment segment;
    private final long elementSize;
    private final long alignment;
    private final T flyweight;
    private final T flyweight2;
    private final Class<T> type;
    
    private int size = 0;
    private int capacity;

    @SuppressWarnings("unchecked")
    public StructVectorImpl(Class<T> type, int initialCapacity, int elementSizeIfString) {
        this.arena = Arena.ofShared();
        this.type = type;
        
        if (Struct.class.isAssignableFrom(type)) {
            try {
                String implClassName = type.getName().replace('$', '_') + "Impl";
                Class<?> implClass = Class.forName(implClassName);
                java.lang.foreign.GroupLayout layout = (java.lang.foreign.GroupLayout) implClass.getField("LAYOUT").get(null);
                this.elementSize = layout.byteSize();
                this.alignment = layout.byteAlignment();
                this.flyweight = (T) MemoryManager.createEmptyStruct((Class<? extends Struct>) type);
                this.flyweight2 = (T) MemoryManager.createEmptyStruct((Class<? extends Struct>) type);
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize StructVector for struct: " + type.getName(), e);
            }
        } else if (type == String.class) {
            this.elementSize = elementSizeIfString;
            this.alignment = 1;
            this.flyweight = null;
            this.flyweight2 = null;
        } else {
            ValueLayout primitiveLayout = getPrimitiveLayout(type);
            this.elementSize = primitiveLayout.byteSize();
            this.alignment = primitiveLayout.byteAlignment();
            this.flyweight = null;
            this.flyweight2 = null;
        }
        
        this.capacity = initialCapacity;
        this.segment = arena.allocate(elementSize * capacity, alignment);
    }

    private ValueLayout getPrimitiveLayout(Class<?> type) {
        if (type == Integer.class) return ValueLayout.JAVA_INT;
        if (type == Long.class) return ValueLayout.JAVA_LONG;
        if (type == Double.class) return ValueLayout.JAVA_DOUBLE;
        if (type == Float.class) return ValueLayout.JAVA_FLOAT;
        if (type == Byte.class) return ValueLayout.JAVA_BYTE;
        throw new UnsupportedOperationException("Unsupported primitive type: " + type);
    }

    @Override
    public void add(T value) {
        ensureCapacity(size + 1);
        set(size, value);
        size++;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        long offset = (long) index * elementSize;
        
        if (flyweight != null) { // Struct
            ((Struct) flyweight).rebase(segment.asSlice(offset, elementSize));
            return flyweight;
        } else if (type == String.class) { // String
            byte[] b = segment.asSlice(offset, elementSize).toArray(ValueLayout.JAVA_BYTE);
            int len = 0;
            while (len < b.length && b[len] != 0) len++;
            return (T) new String(b, 0, len, StandardCharsets.UTF_8);
        } else { // Primitive
            if (type == Integer.class) return (T) (Integer) segment.get(ValueLayout.JAVA_INT, offset);
            if (type == Long.class) return (T) (Long) segment.get(ValueLayout.JAVA_LONG, offset);
            if (type == Double.class) return (T) (Double) segment.get(ValueLayout.JAVA_DOUBLE, offset);
            throw new UnsupportedOperationException("Unsupported type: " + type);
        }
    }

    @Override
    public void set(int index, T value) {
        if (index < 0 || index > size) throw new IndexOutOfBoundsException();
        long offset = (long) index * elementSize;
        
        if (value instanceof Struct s) {
            MemorySegment.copy(s.segment(), 0, segment, offset, elementSize);
        } else if (value instanceof String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            int copyLen = (int) Math.min(b.length, elementSize);
            MemorySegment.copy(MemorySegment.ofArray(b), 0, segment, offset, copyLen);
            if (copyLen < elementSize) {
                segment.asSlice(offset + copyLen, elementSize - copyLen).fill((byte) 0);
            }
        } else {
            if (type == Integer.class) segment.set(ValueLayout.JAVA_INT, offset, (Integer) value);
            else if (type == Long.class) segment.set(ValueLayout.JAVA_LONG, offset, (Long) value);
            else if (type == Double.class) segment.set(ValueLayout.JAVA_DOUBLE, offset, (Double) value);
        }
    }

    @Override
    public void remove(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        if (index < size - 1) {
            long destOffset = (long) index * elementSize;
            long srcOffset = (long) (index + 1) * elementSize;
            long length = (long) (size - index - 1) * elementSize;
            MemorySegment.copy(segment, srcOffset, segment, destOffset, length);
        }
        size--;
    }

    @Override
    public void sort(Comparator<? super T> comparator) {
        if (size <= 1) return;
        quickSort(0, size - 1, comparator);
    }

    private void quickSort(int low, int high, Comparator<? super T> comparator) {
        if (low < high) {
            int pivotIndex = partition(low, high, comparator);
            quickSort(low, pivotIndex, comparator);
            quickSort(pivotIndex + 1, high, comparator);
        }
    }

    private int partition(int low, int high, Comparator<? super T> comparator) {
        byte[] pivotBuffer = new byte[(int) elementSize];
        MemorySegment pivotSeg = MemorySegment.ofArray(pivotBuffer);
        MemorySegment.copy(segment, (long) low * elementSize, pivotSeg, 0, elementSize);
        
        T pivotObj;
        if (flyweight2 != null) {
            ((Struct) flyweight2).rebase(pivotSeg);
            pivotObj = flyweight2;
        } else {
            pivotObj = get(low);
        }

        int i = low - 1;
        int j = high + 1;
        while (true) {
            do { i++; } while (comparator.compare(get(i), pivotObj) < 0);
            do { j--; } while (comparator.compare(get(j), pivotObj) > 0);
            if (i >= j) return j;
            swap(i, j);
        }
    }

    private void swap(int i, int j) {
        if (i == j) return;
        long offsetI = (long) i * elementSize;
        long offsetJ = (long) j * elementSize;
        
        byte[] temp = new byte[(int) elementSize];
        MemorySegment tempSeg = MemorySegment.ofArray(temp);
        
        MemorySegment.copy(segment, offsetI, tempSeg, 0, elementSize);
        MemorySegment.copy(segment, offsetJ, segment, offsetI, elementSize);
        MemorySegment.copy(tempSeg, 0, segment, offsetJ, elementSize);
    }

    @Override
    public int size() { return size; }

    @Override
    public int capacity() { return capacity; }

    @Override
    public void clear() { size = 0; }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity > capacity) {
            int newCapacity = Math.max(capacity * 2, minCapacity);
            MemorySegment newSegment = arena.allocate(elementSize * newCapacity, alignment);
            MemorySegment.copy(segment, 0, newSegment, 0, elementSize * size);
            this.segment = newSegment;
            this.capacity = newCapacity;
        }
    }

    @Override
    public void close() { arena.close(); }

    @Override
    public Iterator<T> iterator() {
        return new Iterator<>() {
            private int current = 0;
            @Override public boolean hasNext() { return current < size; }
            @Override public T next() { return get(current++); }
        };
    }
}
