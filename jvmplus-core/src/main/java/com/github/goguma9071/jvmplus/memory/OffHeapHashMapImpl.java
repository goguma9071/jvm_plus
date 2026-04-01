package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class OffHeapHashMapImpl<K, V> implements OffHeapHashMap<K, V> {
    private static final byte STATE_EMPTY = 0;
    private static final byte STATE_FULL = 1;
    private static final byte STATE_REMOVED = 2;

    private final Allocator allocator;
    private final boolean isAllocatorOwned;
    private MemorySegment keys;
    private MemorySegment values;
    private MemorySegment states;

    private final Class<K> keyType;
    private final Class<V> valueType;
    private final long keySize;
    private final long valueSize;
    private V flyweight;

    private int capacity;
    private int size = 0;
    private final float loadFactor = 0.7f;

    public OffHeapHashMapImpl(Class<K> keyType, Class<V> valueType, int initialCapacity, int keyLen, int valLen) {
        this(keyType, valueType, initialCapacity, keyLen, valLen, null);
    }

    public OffHeapHashMapImpl(Class<K> keyType, Class<V> valueType, int initialCapacity, long explicitKeySize, long explicitValueSize) {
        this.allocator = new ArenaAllocator(Arena.ofShared());
        this.isAllocatorOwned = true;
        this.keyType = keyType;
        this.valueType = valueType;
        this.capacity = initialCapacity;
        this.keySize = explicitKeySize;
        this.valueSize = explicitValueSize;
        this.flyweight = null;
        // determineSize is NOT called here
        allocateSegments(capacity);
    }

    @SuppressWarnings("unchecked")
    public OffHeapHashMapImpl(Class<K> keyType, Class<V> valueType, int initialCapacity, int keyLen, int valLen, Allocator allocator) {
        if (allocator == null) {
            this.allocator = new ArenaAllocator(Arena.ofShared());
            this.isAllocatorOwned = true;
        } else {
            this.allocator = allocator;
            this.isAllocatorOwned = false;
        }
        this.keyType = keyType;
        this.valueType = valueType;
        this.capacity = initialCapacity;

        this.keySize = determineSize(keyType, keyLen);
        this.valueSize = determineSize(valueType, valLen);

        // flyweight is now lazily initialized
        allocateSegments(capacity);
    }

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
        this.keys = allocator.allocate(keySize * cap, 8);
        this.values = allocator.allocate(valueSize * cap, 8);
        this.states = allocator.allocate((long) cap, 1);
        this.states.fill((byte) 0);
    }

    private int hash(K key, int cap) {
        return (key.hashCode() & 0x7FFFFFFF) % cap;
    }

    @Override
    public void put(K key, V value) {
        if (size >= capacity * loadFactor) {
            rehash();
        }
        putInternal(keys, values, states, capacity, key, value);
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
        size++;
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(K key) {
        int idx = hash(key, capacity);
        int startIdx = idx;
        while (states.get(ValueLayout.JAVA_BYTE, idx) != STATE_EMPTY) {
            if (states.get(ValueLayout.JAVA_BYTE, idx) == STATE_FULL && isKeyAt(keys, idx, key)) {
                long offset = (long) idx * valueSize;
                if (Struct.class.isAssignableFrom(valueType)) {
                    V obj = (V) MemoryManager.createEmptyStruct((Class<? extends Struct>) valueType);
                    ((Struct) obj).rebase(values.asSlice(offset, valueSize));
                    return obj;
                }
                if (valueType == Integer.class) return (V) (Integer) values.get(ValueLayout.JAVA_INT, offset);
                if (valueType == Long.class) return (V) (Long) values.get(ValueLayout.JAVA_LONG, offset);
                if (valueType == String.class) {
                    byte[] b = values.asSlice(offset, valueSize).toArray(ValueLayout.JAVA_BYTE);
                    int len = 0; while (len < b.length && b[len] != 0) len++;
                    return (V) new String(b, 0, len, StandardCharsets.UTF_8);
                }
            }
            idx = (idx + 1) % capacity;
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
        int idx = hash(key, capacity);
        int startIdx = idx;
        while (states.get(ValueLayout.JAVA_BYTE, idx) != STATE_EMPTY) {
            if (states.get(ValueLayout.JAVA_BYTE, idx) == STATE_FULL && isKeyAt(keys, idx, key)) {
                if (flyweight != null) {
                    long offset = (long) idx * valueSize;
                    ((Struct) flyweight).rebase(values.asSlice(offset, valueSize));
                    return flyweight;
                }
                return get(key);
            }
            idx = (idx + 1) % capacity;
            if (idx == startIdx) break;
        }
        return null;
    }

    @Override
    public void remove(K key) {
        int idx = hash(key, capacity);
        int startIdx = idx;
        while (states.get(ValueLayout.JAVA_BYTE, idx) != STATE_EMPTY) {
            if (states.get(ValueLayout.JAVA_BYTE, idx) == STATE_FULL && isKeyAt(keys, idx, key)) {
                states.set(ValueLayout.JAVA_BYTE, idx, STATE_REMOVED);
                size--;
                return;
            }
            idx = (idx + 1) % capacity;
            if (idx == startIdx) break;
        }
    }

    private boolean isKeyAt(MemorySegment kSeg, int idx, K key) {
        long offset = (long) idx * keySize;
        if (keyType == Integer.class) return kSeg.get(ValueLayout.JAVA_INT, offset) == (Integer) key;
        if (keyType == Long.class) return kSeg.get(ValueLayout.JAVA_LONG, offset) == (Long) key;
        if (keyType == String.class) {
            byte[] b = kSeg.asSlice(offset, keySize).toArray(ValueLayout.JAVA_BYTE);
            int len = 0; while (len < b.length && b[len] != 0) len++;
            return new String(b, 0, len, StandardCharsets.UTF_8).equals(key);
        }
        return false;
    }

    private void writeKeyAt(MemorySegment kSeg, int idx, K key) {
        long offset = (long) idx * keySize;
        if (keyType == Integer.class) kSeg.set(ValueLayout.JAVA_INT, offset, (Integer) key);
        else if (keyType == Long.class) kSeg.set(ValueLayout.JAVA_LONG, offset, (Long) key);
        else if (keyType == String.class) {
            byte[] b = ((String) key).getBytes(StandardCharsets.UTF_8);
            int len = (int) Math.min(b.length, keySize);
            MemorySegment.copy(MemorySegment.ofArray(b), 0, kSeg, offset, len);
            if (len < keySize) kSeg.asSlice(offset + len, keySize - len).fill((byte) 0);
        }
    }

    private void writeValAt(MemorySegment vSeg, int idx, V val) {
        long offset = (long) idx * valueSize;
        if (val instanceof Struct s) MemorySegment.copy(s.segment(), 0, vSeg, offset, valueSize);
        else if (valueType == Integer.class) vSeg.set(ValueLayout.JAVA_INT, offset, (Integer) val);
        else if (valueType == Long.class) vSeg.set(ValueLayout.JAVA_LONG, offset, (Long) val);
        else if (valueType == String.class) {
            byte[] b = ((String) val).getBytes(StandardCharsets.UTF_8);
            int len = (int) Math.min(b.length, valueSize);
            MemorySegment.copy(MemorySegment.ofArray(b), 0, vSeg, offset, len);
            if (len < valueSize) vSeg.asSlice(offset + len, valueSize - len).fill((byte) 0);
        }
    }

    private void rehash() {
        MemorySegment oldKeys = keys;
        MemorySegment oldValues = values;
        MemorySegment oldStates = states;
        int oldCap = capacity;

        capacity *= 2;
        size = 0;
        allocateSegments(capacity);

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
        int idx = hash(key, capacity);
        while (states.get(ValueLayout.JAVA_BYTE, idx) == STATE_FULL) {
            idx = (idx + 1) % capacity;
        }
        // Key 직접 복사
        MemorySegment.copy(oldK, (long) oldIdx * keySize, keys, (long) idx * keySize, keySize);
        // Value 직접 복사 (객체 생성 없음)
        MemorySegment.copy(oldV, (long) oldIdx * valueSize, values, (long) idx * valueSize, valueSize);
        states.set(ValueLayout.JAVA_BYTE, idx, STATE_FULL);
        size++;
    }

    @SuppressWarnings("unchecked")
    private K readKeyFrom(MemorySegment kSeg, int idx) {
        long offset = (long) idx * keySize;
        if (keyType == Integer.class) return (K) (Integer) kSeg.get(ValueLayout.JAVA_INT, offset);
        if (keyType == Long.class) return (K) (Long) kSeg.get(ValueLayout.JAVA_LONG, offset);
        if (keyType == String.class) {
            byte[] b = kSeg.asSlice(offset, keySize).toArray(ValueLayout.JAVA_BYTE);
            int len = 0; while (len < b.length && b[len] != 0) len++;
            return (K) new String(b, 0, len, StandardCharsets.UTF_8);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private V readValFrom(MemorySegment vSeg, int idx) {
        long offset = (long) idx * valueSize;
        if (Struct.class.isAssignableFrom(valueType)) {
            V tempFlyweight = (V) MemoryManager.createEmptyStruct((Class<? extends Struct>) valueType);
            ((Struct) tempFlyweight).rebase(vSeg.asSlice(offset, valueSize));
            return tempFlyweight;
        }
        if (valueType == Integer.class) return (V) (Integer) vSeg.get(ValueLayout.JAVA_INT, offset);
        if (valueType == Long.class) return (V) (Long) vSeg.get(ValueLayout.JAVA_LONG, offset);
        if (valueType == String.class) {
            byte[] b = vSeg.asSlice(offset, valueSize).toArray(ValueLayout.JAVA_BYTE);
            int len = 0; while (len < b.length && b[len] != 0) len++;
            return (V) new String(b, 0, len, StandardCharsets.UTF_8);
        }
        return null;
    }

    @Override public int size() { return size; }
    @Override public void clear() { states.fill((byte) 0); size = 0; }
    @Override public void free() { 
        if (isAllocatorOwned) {
            allocator.free();
        }
    }
    @Override public void close() { free(); }
}
