package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Iterator;

public class StructVectorImpl<T> implements StructVector<T> {
    private final Allocator allocator;
    private final boolean isAllocatorOwned;
    private MemorySegment segment;
    private final long elementSize;
    private final long alignment;
    private T flyweight;
    private T flyweight2;
    private final Class<T> type;
    
    private int size = 0;
    private int capacity;

    public StructVectorImpl(Class<T> type, int initialCapacity, int elementSizeIfString) {
        this(type, initialCapacity, elementSizeIfString, null);
    }

    /** 
     * 부트스트래핑을 위한 생성자. 
     * explicitSize가 0보다 크면 리플렉션(Impl 클래스 찾기)을 생략하고 해당 크기를 직접 사용합니다.
     */
    public StructVectorImpl(Class<T> type, int initialCapacity, long explicitSize, Allocator allocator) {
        this.type = type;
        this.capacity = initialCapacity;
        if (allocator == null) {
            this.allocator = new ArenaAllocator(Arena.ofShared());
            this.isAllocatorOwned = true;
        } else {
            this.allocator = allocator;
            this.isAllocatorOwned = false;
        }

        if (explicitSize > 0) {
            this.elementSize = explicitSize;
            this.alignment = 8;
        } else {
            if (Struct.class.isAssignableFrom(type)) {
                try {
                    String implClassName = type.getName().replace('$', '_') + "Impl";
                    Class<?> implClass = Class.forName(implClassName);
                    java.lang.foreign.GroupLayout layout = (java.lang.foreign.GroupLayout) implClass.getField("LAYOUT").get(null);
                    this.elementSize = layout.byteSize();
                    this.alignment = layout.byteAlignment();
                } catch (Exception e) {
                    throw new RuntimeException("Failed to initialize StructVector for struct: " + type.getName(), e);
                }
            } else if (type == String.class) {
                this.elementSize = 0; // String은 생성 시 elementSizeIfString을 통해야 함
                this.alignment = 1;
            } else {
                ValueLayout primitiveLayout = getPrimitiveLayout(type);
                this.elementSize = primitiveLayout.byteSize();
                this.alignment = primitiveLayout.byteAlignment();
            }
        }
        
        // 플라이웨이트는 처음 사용될 때 지연 생성 (부트스트래핑 대응)
        this.segment = this.allocator.allocate(elementSize * capacity, alignment);
    }

    @SuppressWarnings("unchecked")
    private void checkFlyweights() {
        if (flyweight == null && Struct.class.isAssignableFrom(type)) {
            try {
                this.flyweight = (T) MemoryManager.createEmptyStruct((Class) type);
                this.flyweight2 = (T) MemoryManager.createEmptyStruct((Class) type);
            } catch (Exception ignored) {}
        }
    }

    public void setFlyweight(T fw) {
        this.flyweight = fw;
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

        if (Struct.class.isAssignableFrom(type)) {
            checkFlyweights();
            if (flyweight != null) {
                // 부트스트래핑 중에는 새 객체 생성 대신 flyweight를 복사하거나 재사용하는 전략 검토
                // 현재는 안전하게 getFlyweight()와 유사하게 동작하되, 
                // 생성된 Impl이 없을 때의 예외를 방지
                ((Struct) flyweight).rebase(segment.asSlice(offset, elementSize));
                return flyweight;
            }
            throw new RuntimeException("StructVector: Cannot create new instances during bootstrapping for type " + type.getName());
        } else if (type == String.class) {
            byte[] b = segment.asSlice(offset, elementSize).toArray(ValueLayout.JAVA_BYTE);
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
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        checkFlyweights();
        long offset = (long) index * elementSize;
        
        if (flyweight != null) {
            ((Struct) flyweight).rebase(segment.asSlice(offset, elementSize));
            return flyweight;
        }
        return get(index);
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
        checkFlyweights();
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
            do { i++; } while (comparator.compare(getFlyweight(i), pivotObj) < 0);
            do { j--; } while (comparator.compare(getFlyweight(j), pivotObj) > 0);
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
            MemorySegment newSegment = allocator.allocate(elementSize * newCapacity, alignment);
            MemorySegment.copy(segment, 0, newSegment, 0, elementSize * size);
            allocator.free(segment);
            this.segment = newSegment;
            this.capacity = newCapacity;
        }
    }

    @Override
    public void free() {
        if (isAllocatorOwned) {
            allocator.free();
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
            @Override public boolean hasNext() { return current < size; }
            @Override public T next() { return getFlyweight(current++); }
        };
    }
}
