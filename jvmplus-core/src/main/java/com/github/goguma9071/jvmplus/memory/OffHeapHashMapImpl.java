package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 오프힙 헤더를 가진 고성능 해시맵 구현체.
 * [Layout] | size(4) | capacity(4) | keySize(8) | valueSize(8) | loadFactor(4) | ... segments ... |
 */
public class OffHeapHashMapImpl<K, V> implements OffHeapHashMap<K, V> {
    private static final long HEADER_SIZE = 32; // 정렬을 위해 32바이트 확보
    private static final long OFF_SIZE = 0;
    private static final long OFF_CAP = 4;
    private static final long OFF_KSIZE = 8;
    private static final long OFF_VSIZE = 16;
    private static final long OFF_LFACTOR = 24;

    private static final byte STATE_EMPTY = 0;
    private static final byte STATE_FULL = 1;
    private static final byte STATE_REMOVED = 2;

    private final Allocator allocator;
    private final boolean isAllocatorOwned;
    
    private MemorySegment headerSeg;
    private MemorySegment keys;
    private MemorySegment values;
    private MemorySegment states;

    private final Class<K> keyType;
    private final Class<V> valueType;
    private V flyweight;

    public OffHeapHashMapImpl(Class<K> keyType, Class<V> valueType, int initialCapacity, int keyLen, int valLen) {
        this(keyType, valueType, initialCapacity, keyLen, valLen, null);
    }

    public OffHeapHashMapImpl(Class<K> keyType, Class<V> valueType, int initialCapacity, long explicitKeySize, long explicitValueSize) {
        this.allocator = new ArenaAllocator(Arena.ofShared());
        this.isAllocatorOwned = true;
        this.keyType = keyType;
        this.valueType = valueType;
        
        // 헤더 할당
        this.headerSeg = allocator.allocate(HEADER_SIZE, 8);
        headerSeg.set(ValueLayout.JAVA_INT, OFF_SIZE, 0);
        headerSeg.set(ValueLayout.JAVA_INT, OFF_CAP, initialCapacity);
        headerSeg.set(ValueLayout.JAVA_LONG, OFF_KSIZE, explicitKeySize);
        headerSeg.set(ValueLayout.JAVA_LONG, OFF_VSIZE, explicitValueSize);
        headerSeg.set(ValueLayout.JAVA_FLOAT, OFF_LFACTOR, 0.7f);

        this.flyweight = null;
        allocateSegments(initialCapacity);
    }

    @SuppressWarnings("unchecked")
    public OffHeapHashMapImpl(Class<K> keyType, Class<V> valueType, int initialCapacity, int keyLen, int valLen, Allocator allocator) {
        this.isAllocatorOwned = allocator == null;
        this.allocator = isAllocatorOwned ? new ArenaAllocator(Arena.ofShared()) : allocator;
        this.keyType = keyType;
        this.valueType = valueType;

        long kSize = determineSize(keyType, keyLen);
        long vSize = determineSize(valueType, valLen);

        // 헤더 할당
        this.headerSeg = this.allocator.allocate(HEADER_SIZE, 8);
        headerSeg.set(ValueLayout.JAVA_INT, OFF_SIZE, 0);
        headerSeg.set(ValueLayout.JAVA_INT, OFF_CAP, initialCapacity);
        headerSeg.set(ValueLayout.JAVA_LONG, OFF_KSIZE, kSize);
        headerSeg.set(ValueLayout.JAVA_LONG, OFF_VSIZE, vSize);
        headerSeg.set(ValueLayout.JAVA_FLOAT, OFF_LFACTOR, 0.7f);

        allocateSegments(initialCapacity);
    }

    private long getKeySize() { return headerSeg.get(ValueLayout.JAVA_LONG, OFF_KSIZE); }
    private long getValueSize() { return headerSeg.get(ValueLayout.JAVA_LONG, OFF_VSIZE); }
    private int getCapacity() { return headerSeg.get(ValueLayout.JAVA_INT, OFF_CAP); }
    private float getLoadFactor() { return headerSeg.get(ValueLayout.JAVA_FLOAT, OFF_LFACTOR); }
    private void setSize(int s) { headerSeg.set(ValueLayout.JAVA_INT, OFF_SIZE, s); }
    private void setCapacity(int c) { headerSeg.set(ValueLayout.JAVA_INT, OFF_CAP, c); }

    private long determineSize(Class<?> type, int length) {
        if (Struct.class.isAssignableFrom(type)) {
            try {
                String implName = type.getName().replace('$', '_') + "Impl";
                return ((java.lang.foreign.GroupLayout) Class.forName(implName).getField("LAYOUT").get(null)).byteSize();
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        if (type == String.class) return length;
        if (type == Integer.class) return 4;
        if (type == Long.class) return 8;
        if (type == Double.class) return 8;
        throw new UnsupportedOperationException("Unsupported type: " + type);
    }

    private void allocateSegments(int cap) {
        long kSize = getKeySize();
        long vSize = getValueSize();
        this.keys = allocator.allocate(kSize * cap, 8);
        this.values = allocator.allocate(vSize * cap, 8);
        this.states = allocator.allocate((long) cap, 1);
        this.states.fill((byte) 0);
    }

    private int hash(K key, int cap) {
        return (key.hashCode() & 0x7FFFFFFF) % cap;
    }

    @Override
    public void put(K key, V value) {
        int currentSize = size();
        int currentCap = getCapacity();
        if (currentSize >= currentCap * getLoadFactor()) {
            rehash();
            currentCap = getCapacity();
        }
        putInternal(keys, values, states, currentCap, key, value);
    }

    private void putInternal(MemorySegment kSeg, MemorySegment vSeg, MemorySegment sSeg, int cap, K key, V value) {
        int idx = hash(key, cap);
        while (sSeg.get(ValueLayout.JAVA_BYTE, idx) == STATE_FULL) {
            if (isKeyAt(kSeg, idx, key)) {
                writeValAt(vSeg, idx, value);
                return;
            }
            idx = (idx + 1) % cap;
        }
        writeKeyAt(kSeg, idx, key);
        writeValAt(vSeg, idx, value);
        sSeg.set(ValueLayout.JAVA_BYTE, idx, STATE_FULL);
        setSize(size() + 1);
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(K key) {
        int cap = getCapacity();
        int idx = hash(key, cap);
        int startIdx = idx;
        while (states.get(ValueLayout.JAVA_BYTE, idx) != STATE_EMPTY) {
            if (states.get(ValueLayout.JAVA_BYTE, idx) == STATE_FULL && isKeyAt(keys, idx, key)) {
                long vSize = getValueSize();
                long offset = (long) idx * vSize;
                if (Struct.class.isAssignableFrom(valueType)) {
                    V obj = (V) MemoryManager.createEmptyStruct((Class<? extends Struct>) valueType);
                    ((Struct) obj).rebase(values.asSlice(offset, vSize));
                    return obj;
                }
                if (valueType == Integer.class) return (V) (Integer) values.get(ValueLayout.JAVA_INT, offset);
                if (valueType == Long.class) return (V) (Long) values.get(ValueLayout.JAVA_LONG, offset);
                if (valueType == String.class) {
                    byte[] b = values.asSlice(offset, vSize).toArray(ValueLayout.JAVA_BYTE);
                    int len = 0; while (len < b.length && b[len] != 0) len++;
                    return (V) new String(b, 0, len, StandardCharsets.UTF_8);
                }
            }
            idx = (idx + 1) % cap;
            if (idx == startIdx) break;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void checkFlyweight() {
        if (flyweight == null && Struct.class.isAssignableFrom(valueType)) {
            try {
                flyweight = (V) MemoryManager.createEmptyStruct((Class<? extends Struct>) valueType);
            } catch (Exception ignored) {}
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public V getFlyweight(K key) {
        checkFlyweight();
        int cap = getCapacity();
        int idx = hash(key, cap);
        int startIdx = idx;
        while (states.get(ValueLayout.JAVA_BYTE, idx) != STATE_EMPTY) {
            if (states.get(ValueLayout.JAVA_BYTE, idx) == STATE_FULL && isKeyAt(keys, idx, key)) {
                if (flyweight != null) {
                    long vSize = getValueSize();
                    long offset = (long) idx * vSize;
                    ((Struct) flyweight).rebase(values.asSlice(offset, vSize));
                    return flyweight;
                }
                return get(key);
            }
            idx = (idx + 1) % cap;
            if (idx == startIdx) break;
        }
        return null;
    }

    @Override
    public void remove(K key) {
        int cap = getCapacity();
        int idx = hash(key, cap);
        int startIdx = idx;
        while (states.get(ValueLayout.JAVA_BYTE, idx) != STATE_EMPTY) {
            if (states.get(ValueLayout.JAVA_BYTE, idx) == STATE_FULL && isKeyAt(keys, idx, key)) {
                states.set(ValueLayout.JAVA_BYTE, idx, STATE_REMOVED);
                setSize(size() - 1);
                return;
            }
            idx = (idx + 1) % cap;
            if (idx == startIdx) break;
        }
    }

    private boolean isKeyAt(MemorySegment kSeg, int idx, K key) {
        long kSize = getKeySize();
        long offset = (long) idx * kSize;
        if (keyType == Integer.class) return kSeg.get(ValueLayout.JAVA_INT, offset) == (Integer) key;
        if (keyType == Long.class) return kSeg.get(ValueLayout.JAVA_LONG, offset) == (Long) key;
        if (keyType == String.class) {
            byte[] b = kSeg.asSlice(offset, kSize).toArray(ValueLayout.JAVA_BYTE);
            int len = 0; while (len < b.length && b[len] != 0) len++;
            return new String(b, 0, len, StandardCharsets.UTF_8).equals(key);
        }
        return false;
    }

    private void writeKeyAt(MemorySegment kSeg, int idx, K key) {
        long kSize = getKeySize();
        long offset = (long) idx * kSize;
        if (keyType == Integer.class) kSeg.set(ValueLayout.JAVA_INT, offset, (Integer) key);
        else if (keyType == Long.class) kSeg.set(ValueLayout.JAVA_LONG, offset, (Long) key);
        else if (keyType == String.class) {
            byte[] b = ((String) key).getBytes(StandardCharsets.UTF_8);
            int len = (int) Math.min(b.length, kSize);
            MemorySegment.copy(MemorySegment.ofArray(b), 0, kSeg, offset, len);
            if (len < kSize) kSeg.asSlice(offset + len, kSize - len).fill((byte) 0);
        }
    }

    private void writeValAt(MemorySegment vSeg, int idx, V val) {
        long vSize = getValueSize();
        long offset = (long) idx * vSize;
        if (val instanceof Struct s) MemorySegment.copy(s.segment(), 0, vSeg, offset, vSize);
        else if (valueType == Integer.class) vSeg.set(ValueLayout.JAVA_INT, offset, (Integer) val);
        else if (valueType == Long.class) vSeg.set(ValueLayout.JAVA_LONG, offset, (Long) val);
        else if (valueType == String.class) {
            byte[] b = ((String) val).getBytes(StandardCharsets.UTF_8);
            int len = (int) Math.min(b.length, vSize);
            MemorySegment.copy(MemorySegment.ofArray(b), 0, vSeg, offset, len);
            if (len < vSize) vSeg.asSlice(offset + len, vSize - len).fill((byte) 0);
        }
    }

    private void rehash() {
        MemorySegment oldKeys = keys;
        MemorySegment oldValues = values;
        MemorySegment oldStates = states;
        int oldCap = getCapacity();

        int newCap = oldCap * 2;
        setCapacity(newCap);
        setSize(0); // putInternalFromMemory에서 다시 증가함
        allocateSegments(newCap);

        for (int i = 0; i < oldCap; i++) {
            if (oldStates.get(ValueLayout.JAVA_BYTE, i) == STATE_FULL) {
                K key = readKeyFrom(oldKeys, i);
                putInternalFromMemory(oldKeys, oldValues, i, key);
            }
        }
        
        allocator.free(oldKeys);
        allocator.free(oldValues);
        allocator.free(oldStates);
    }

    private void putInternalFromMemory(MemorySegment oldK, MemorySegment oldV, int oldIdx, K key) {
        int cap = getCapacity();
        int idx = hash(key, cap);
        while (states.get(ValueLayout.JAVA_BYTE, idx) == STATE_FULL) {
            idx = (idx + 1) % cap;
        }
        long kSize = getKeySize();
        long vSize = getValueSize();
        MemorySegment.copy(oldK, (long) oldIdx * kSize, keys, (long) idx * kSize, kSize);
        MemorySegment.copy(oldV, (long) oldIdx * vSize, values, (long) idx * vSize, vSize);
        states.set(ValueLayout.JAVA_BYTE, idx, STATE_FULL);
        setSize(size() + 1);
    }

    @SuppressWarnings("unchecked")
    private K readKeyFrom(MemorySegment kSeg, int idx) {
        long kSize = getKeySize();
        long offset = (long) idx * kSize;
        if (keyType == Integer.class) return (K) (Integer) kSeg.get(ValueLayout.JAVA_INT, offset);
        if (keyType == Long.class) return (K) (Long) kSeg.get(ValueLayout.JAVA_LONG, offset);
        if (keyType == String.class) {
            byte[] b = kSeg.asSlice(offset, kSize).toArray(ValueLayout.JAVA_BYTE);
            int len = 0; while (len < b.length && b[len] != 0) len++;
            return (K) new String(b, 0, len, StandardCharsets.UTF_8);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private V readValFrom(MemorySegment vSeg, int idx) {
        long vSize = getValueSize();
        long offset = (long) idx * vSize;
        if (Struct.class.isAssignableFrom(valueType)) {
            V tempFlyweight = (V) MemoryManager.createEmptyStruct((Class) valueType);
            ((Struct) tempFlyweight).rebase(vSeg.asSlice(offset, vSize));
            return tempFlyweight;
        }
        if (valueType == Integer.class) return (V) (Integer) vSeg.get(ValueLayout.JAVA_INT, offset);
        if (valueType == Long.class) return (V) (Long) vSeg.get(ValueLayout.JAVA_LONG, offset);
        if (valueType == String.class) {
            byte[] b = vSeg.asSlice(offset, vSize).toArray(ValueLayout.JAVA_BYTE);
            int len = 0; while (len < b.length && b[len] != 0) len++;
            return (V) new String(b, 0, len, StandardCharsets.UTF_8);
        }
        return null;
    }

    @Override
    public void forEachRaw(java.util.function.BiConsumer<K, MemorySegment> action) {
        int cap = getCapacity();
        long vSize = getValueSize();
        for (int i = 0; i < cap; i++) {
            if (states.get(ValueLayout.JAVA_BYTE, i) == STATE_FULL) {
                K k = readKeyFrom(keys, i);
                MemorySegment vSeg = values.asSlice((long) i * vSize, vSize);
                action.accept(k, vSeg);
            }
        }
    }

    @Override
    public java.util.Iterator<Entry<K, V>> iterator() {
        return new java.util.Iterator<>() {
            private int current = 0;
            private int found = 0;
            private final int total = size();
            private final int cap = getCapacity();

            @Override public boolean hasNext() { return found < total && current < cap; }

            @Override public Entry<K, V> next() {
                while (current < cap) {
                    if (states.get(ValueLayout.JAVA_BYTE, current) == STATE_FULL) {
                        K k = readKeyFrom(keys, current);
                        V v = get(k); // 객체 생성을 위해 get 호출
                        current++;
                        found++;
                        return new Entry<>(k, v);
                    }
                    current++;
                }
                throw new java.util.NoSuchElementException();
            }
        };
    }

    @Override public int size() { return headerSeg.get(ValueLayout.JAVA_INT, OFF_SIZE); }
    @Override public void clear() { states.fill((byte) 0); setSize(0); }
    @Override public void free() { 
        if (headerSeg != null) allocator.free(headerSeg);
        if (keys != null) allocator.free(keys);
        if (values != null) allocator.free(values);
        if (states != null) allocator.free(states);
        if (isAllocatorOwned) {
            allocator.free();
        }
    }
    @Override public void close() { free(); }
}
