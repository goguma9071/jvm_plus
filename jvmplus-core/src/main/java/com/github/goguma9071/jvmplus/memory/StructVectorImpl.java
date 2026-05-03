package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Iterator;

/**
 * 오프힙 헤더를 가진 고성능 가변 배열 구현체.
 * [Layout] | size(4) | capacity(4) | elementSize(8) | alignment(8) | DATA... |
 */
public class StructVectorImpl<T> implements StructVector<T> {
    private static final long HEADER_SIZE = 24;
    private static final long OFF_SIZE = 0;
    private static final long OFF_CAP = 4;
    private static final long OFF_ESIZE = 8;
    private static final long OFF_ALIGN = 16;

    private final Allocator allocator;
    private final boolean isAllocatorOwned;
    private MemorySegment segment; // Header + Data 통합 세그먼트
    
    private T flyweight;
    private T flyweight2;
    private final Class<T> type;

    public StructVectorImpl(Class<T> type, int initialCapacity, int elementSizeIfString) {
        this(type, initialCapacity, (long)elementSizeIfString, null);
    }

    public StructVectorImpl(Class<T> type, int initialCapacity, long explicitSize, Allocator allocator) {
        this.type = type;
        this.isAllocatorOwned = allocator == null;
        this.allocator = isAllocatorOwned ? new ArenaAllocator(Arena.ofShared()) : allocator;

        long eSize;
        long align;

        if (explicitSize > 0) {
            eSize = explicitSize;
            align = 8;
        } else {
            if (Struct.class.isAssignableFrom(type)) {
                try {
                    String implClassName = type.getName().replace('$', '_') + "Impl";
                    Class<?> implClass = Class.forName(implClassName);
                    java.lang.foreign.GroupLayout layout = (java.lang.foreign.GroupLayout) implClass.getField("LAYOUT").get(null);
                    eSize = layout.byteSize();
                    align = layout.byteAlignment();
                } catch (Exception e) {
                    throw new RuntimeException("Failed to initialize StructVector for struct: " + type.getName(), e);
                }
            } else if (type == String.class) {
                eSize = 0; // String은 실제 사용 시 elementSizeIfString을 통해야 함
                align = 1;
            } else {
                java.lang.foreign.ValueLayout primitiveLayout = getPrimitiveLayout(type);
                eSize = primitiveLayout.byteSize();
                align = primitiveLayout.byteAlignment();
            }
        }

        // 초기 메모리 할당 (Header + Data)
        this.segment = this.allocator.allocate(HEADER_SIZE + (eSize * initialCapacity), 8);
        
        // 헤더 초기화
        this.segment.set(ValueLayout.JAVA_INT, OFF_SIZE, 0);
        this.segment.set(ValueLayout.JAVA_INT, OFF_CAP, initialCapacity);
        this.segment.set(ValueLayout.JAVA_LONG, OFF_ESIZE, eSize);
        this.segment.set(ValueLayout.JAVA_LONG, OFF_ALIGN, align);
    }

    private long getElementSize() { return segment.get(ValueLayout.JAVA_LONG, OFF_ESIZE); }
    private long getAlignment() { return segment.get(ValueLayout.JAVA_LONG, OFF_ALIGN); }
    private void setSize(int s) { segment.set(ValueLayout.JAVA_INT, OFF_SIZE, s); }
    private void setCapacity(int c) { segment.set(ValueLayout.JAVA_INT, OFF_CAP, c); }

    @SuppressWarnings("unchecked")
    private void checkFlyweights() {
        if (flyweight == null && Struct.class.isAssignableFrom(type)) {
            try {
                this.flyweight = (T) MemoryManager.createFlyweight((Class) type);
                this.flyweight2 = (T) MemoryManager.createFlyweight((Class) type);
            } catch (Exception ignored) {}
        }
    }

    public void setFlyweight(T fw) { this.flyweight = fw; }

    private java.lang.foreign.ValueLayout getPrimitiveLayout(Class<?> type) {
        if (type == Integer.class) return ValueLayout.JAVA_INT;
        if (type == Long.class) return ValueLayout.JAVA_LONG;
        if (type == Double.class) return ValueLayout.JAVA_DOUBLE;
        if (type == Float.class) return ValueLayout.JAVA_FLOAT;
        if (type == Byte.class) return ValueLayout.JAVA_BYTE;
        throw new UnsupportedOperationException("Unsupported primitive type: " + type);
    }

    @Override
    public void add(T value) {
        int currentSize = size();
        ensureCapacity(currentSize + 1);
        set(currentSize, value);
        setSize(currentSize + 1);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T get(int index) {
        int currentSize = size();
        if (index < 0 || index >= currentSize) throw new IndexOutOfBoundsException();
        long eSize = getElementSize();
        long offset = HEADER_SIZE + (long) index * eSize;
        
        if (Struct.class.isAssignableFrom(type)) {
            checkFlyweights();
            if (flyweight != null) {
                ((Struct) flyweight).rebase(segment.asSlice(offset, eSize));
                return flyweight;
            }
            T obj = (T) MemoryManager.createFlyweight((Class) type);
            ((Struct) obj).rebase(segment.asSlice(offset, eSize));
            return obj;
        } else if (type == String.class) {
            byte[] b = segment.asSlice(offset, eSize).toArray(ValueLayout.JAVA_BYTE);
            int len = 0; while (len < b.length && b[len] != 0) len++;
            return (T) new String(b, 0, len, StandardCharsets.UTF_8);
        } else {
            if (type == Integer.class) return (T) (Integer) segment.get(ValueLayout.JAVA_INT, offset);
            if (type == Long.class) return (T) (Long) segment.get(ValueLayout.JAVA_LONG, offset);
            if (type == Double.class) return (T) (Double) segment.get(ValueLayout.JAVA_DOUBLE, offset);
            throw new UnsupportedOperationException("Unsupported type: " + type);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public T getFlyweight(int index) {
        if (index < 0 || index >= size()) throw new IndexOutOfBoundsException();
        checkFlyweights();
        long eSize = getElementSize();
        long offset = HEADER_SIZE + (long) index * eSize;
        
        if (flyweight != null) {
            ((Struct) flyweight).rebase(segment.asSlice(offset, eSize));
            return flyweight;
        }
        return get(index);
    }

    @Override
    public void set(int index, T value) {
        if (index < 0 || index > size()) throw new IndexOutOfBoundsException();
        long eSize = getElementSize();
        long offset = HEADER_SIZE + (long) index * eSize;
        
        if (value instanceof Struct s) {
            MemorySegment.copy(s.segment(), 0, segment, offset, eSize);
        } else if (value instanceof String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            int copyLen = (int) Math.min(b.length, eSize);
            MemorySegment.copy(MemorySegment.ofArray(b), 0, segment, offset, copyLen);
            if (copyLen < eSize) {
                segment.asSlice(offset + copyLen, eSize - copyLen).fill((byte) 0);
            }
        } else {
            if (type == Integer.class) segment.set(ValueLayout.JAVA_INT, offset, (Integer) value);
            else if (type == Long.class) segment.set(ValueLayout.JAVA_LONG, offset, (Long) value);
            else if (type == Double.class) segment.set(ValueLayout.JAVA_DOUBLE, offset, (Double) value);
        }
    }

    @Override
    public void remove(int index) {
        int currentSize = size();
        if (index < 0 || index >= currentSize) throw new IndexOutOfBoundsException();
        long eSize = getElementSize();
        if (index < currentSize - 1) {
            long destOffset = HEADER_SIZE + (long) index * eSize;
            long srcOffset = HEADER_SIZE + (long) (index + 1) * eSize;
            long length = (long) (currentSize - index - 1) * eSize;
            MemorySegment.copy(segment, srcOffset, segment, destOffset, length);
        }
        setSize(currentSize - 1);
    }

    @Override
    public void sort(Comparator<? super T> comparator) {
        int currentSize = size();
        if (currentSize <= 1) return;
        quickSort(0, currentSize - 1, comparator);
    }

    private void quickSort(int low, int high, Comparator<? super T> comparator) {
        if (low < high) {
            int pivotIndex = partition(low, high, comparator);
            quickSort(low, pivotIndex, comparator);
            quickSort(pivotIndex + 1, high, comparator);
        }
    }

    private int partition(int low, int high, Comparator<? super T> comparator) {
        checkFlyweights();
        long eSize = getElementSize();
        byte[] pivotBuffer = new byte[(int) eSize];
        MemorySegment pivotSeg = MemorySegment.ofArray(pivotBuffer);
        MemorySegment.copy(segment, HEADER_SIZE + (long) low * eSize, pivotSeg, 0, eSize);
        
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
            do { i++; } while (comparator.compare(getFlyweight(i), pivotObj) < 0);
            do { j--; } while (comparator.compare(getFlyweight(j), pivotObj) > 0);
            if (i >= j) return j;
            swap(i, j);
        }
    }

    private void swap(int i, int j) {
        if (i == j) return;
        long eSize = getElementSize();
        long offsetI = HEADER_SIZE + (long) i * eSize;
        long offsetJ = HEADER_SIZE + (long) j * eSize;
        
        byte[] temp = new byte[(int) eSize];
        MemorySegment tempSeg = MemorySegment.ofArray(temp);
        
        MemorySegment.copy(segment, offsetI, tempSeg, 0, eSize);
        MemorySegment.copy(segment, offsetJ, segment, offsetI, eSize);
        MemorySegment.copy(tempSeg, 0, segment, offsetJ, eSize);
    }

    @Override public int size() { return segment.get(ValueLayout.JAVA_INT, OFF_SIZE); }
    @Override public int capacity() { return segment.get(ValueLayout.JAVA_INT, OFF_CAP); }
    @Override public void clear() { setSize(0); }

    private void ensureCapacity(int minCapacity) {
        int currentCap = capacity();
        if (minCapacity > currentCap) {
            int newCapacity = Math.max(currentCap * 2, minCapacity);
            long eSize = getElementSize();
            long align = getAlignment();
            
            MemorySegment newSegment = allocator.allocate(HEADER_SIZE + (eSize * newCapacity), 8);
            // 헤더와 기존 데이터 전체 복사
            MemorySegment.copy(segment, 0, newSegment, 0, HEADER_SIZE + (eSize * size()));
            // 새로운 용량 업데이트
            newSegment.set(ValueLayout.JAVA_INT, OFF_CAP, newCapacity);
            
            allocator.free(segment);
            this.segment = newSegment;
        }
    }

    @Override
    public void free() {
        if (isAllocatorOwned) {
            allocator.free();
        }
    }

    @Override
    @Deprecated
    public void close() {
        free();
    }

    @Override
    public Iterator<T> iterator() {
        return new Iterator<>() {
            private int current = 0;
            @Override public boolean hasNext() { return current < size(); }
            @Override public T next() { return getFlyweight(current++); }
        };
    }
}
