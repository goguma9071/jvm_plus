package com.github.goguma9071.jvmplus.memory;

import java.lang.invoke.VarHandle;
import java.lang.foreign.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class MemoryManager {

    private static final Map<Class<?>, StructMetadata> METADATA_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, MemoryPool> POOLS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Constructor<?>> CONSTRUCTOR_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, java.lang.foreign.GroupLayout> LAYOUT_CACHE = new ConcurrentHashMap<>();
    private static final int DEFAULT_POOL_CAPACITY = 10000;

    private static final MemoryPool INT_POOL = new MemoryPool(ValueLayout.JAVA_INT, 1000);
    private static final MemoryPool LONG_POOL = new MemoryPool(ValueLayout.JAVA_LONG, 1000);
    private static final MemoryPool DOUBLE_POOL = new MemoryPool(ValueLayout.JAVA_DOUBLE, 1000);

    public static VarHandle getHandle(Class<?> type, String fieldName) {
        StructMetadata metadata = METADATA_CACHE.computeIfAbsent(type, MemoryManager::analyzeStruct);
        return metadata.handles.get(fieldName);
    }

    @SuppressWarnings("unchecked")
    public static <T extends Struct> T allocate(Class<T> type) {
        try {
            Constructor<?> constructor = CONSTRUCTOR_CACHE.get(type);
            java.lang.foreign.GroupLayout layout = LAYOUT_CACHE.get(type);
            
            if (constructor == null) {
                String implClassName = type.getName().replace('$', '_') + "Impl";
                Class<?> implClass = Class.forName(implClassName);
                layout = (java.lang.foreign.GroupLayout) implClass.getField("LAYOUT").get(null);
                constructor = implClass.getConstructor(MemorySegment.class, MemoryPool.class);
                
                CONSTRUCTOR_CACHE.put(type, constructor);
                LAYOUT_CACHE.put(type, layout);
            }
            
            final java.lang.foreign.GroupLayout finalLayout = layout;
            MemoryPool pool = POOLS.computeIfAbsent(type, k -> new MemoryPool(finalLayout, DEFAULT_POOL_CAPACITY));
            
            return (T) constructor.newInstance(pool.allocate(), pool);
        } catch (Exception e) {
            return allocateProxy(type);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T extends Struct> StructArray<T> allocateSoA(Class<T> type, int count) {
        try {
            String implClassName = type.getName().replace('$', '_') + "SoAImpl";
            Class<?> implClass = Class.forName(implClassName);
            Constructor<?> constructor = implClass.getConstructor(int.class);
            return (StructArray<T>) constructor.newInstance(count);
        } catch (Exception e) {
            throw new RuntimeException("Failed to allocate SoA structure", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T extends Struct> StructArray<T> map(Path path, long count, Class<T> type) {
        try {
            String implClassName = type.getName().replace('$', '_') + "Impl";
            Class<?> implClass = Class.forName(implClassName);
            java.lang.foreign.GroupLayout layout = (java.lang.foreign.GroupLayout) implClass.getField("LAYOUT").get(null);
            long totalSize = layout.byteSize() * count;

            Arena arena = Arena.ofShared();
            FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
            MemorySegment mappedSegment = channel.map(FileChannel.MapMode.READ_WRITE, 0, totalSize, arena);
            
            T flyweight = allocate(type);
            return new StructArrayView<>(mappedSegment, layout.byteSize(), (int)count, flyweight, arena);
        } catch (Exception e) {
            throw new RuntimeException("Failed to map file to memory", e);
        }
    }

    public static <T extends Struct> MemoryPool getPool(Class<T> type) {
        allocate(type).close(); 
        return POOLS.get(type);
    }

    @SuppressWarnings("unchecked")
    public static <T extends Struct> T allocate(Class<T> type, Arena arena) {
        try {
            String implClassName = type.getName().replace('$', '_') + "Impl";
            Class<?> implClass = Class.forName(implClassName);
            java.lang.foreign.GroupLayout layout = (java.lang.foreign.GroupLayout) implClass.getField("LAYOUT").get(null);
            Constructor<?> constructor = implClass.getConstructor(MemorySegment.class, MemoryPool.class);
            
            MemorySegment segment = arena.allocate(layout.byteSize(), layout.byteAlignment());
            return (T) constructor.newInstance(segment, null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to allocate in scoped arena", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T extends Struct> T allocate(Class<T> type, Allocator allocator) {
        try {
            String implClassName = type.getName().replace('$', '_') + "Impl";
            Class<?> implClass = Class.forName(implClassName);
            java.lang.foreign.GroupLayout layout = (java.lang.foreign.GroupLayout) implClass.getField("LAYOUT").get(null);
            Constructor<?> constructor = implClass.getConstructor(MemorySegment.class, MemoryPool.class);
            
            MemorySegment segment = allocator.allocate(layout.byteSize(), layout.byteAlignment());
            return (T) constructor.newInstance(segment, null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to allocate via allocator", e);
        }
    }

    public static <T extends Struct> StructArray<T> arrayView(Class<T> type, int count) {
        try {
            String implClassName = type.getName().replace('$', '_') + "Impl";
            Class<?> implClass = Class.forName(implClassName);
            java.lang.foreign.GroupLayout layout = (java.lang.foreign.GroupLayout) implClass.getField("LAYOUT").get(null);
            
            Arena arena = Arena.ofShared();
            MemorySegment bulkSegment = arena.allocate(layout.byteSize() * count, layout.byteAlignment());
            T flyweight = allocate(type);
            
            return new StructArrayView<>(bulkSegment, layout.byteSize(), count, flyweight, arena);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create struct array view", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T extends Struct> T[] allocateArray(Class<T> type, int count) {
        try {
            String implClassName = type.getName().replace('$', '_') + "Impl";
            Class<?> implClass = Class.forName(implClassName);
            java.lang.foreign.GroupLayout layout = (java.lang.foreign.GroupLayout) implClass.getField("LAYOUT").get(null);
            Constructor<?> constructor = implClass.getConstructor(MemorySegment.class, MemoryPool.class);
            
            MemorySegment bulkSegment = Arena.ofAuto().allocate(layout.byteSize() * count, layout.byteAlignment());
            
            T[] array = (T[]) java.lang.reflect.Array.newInstance(type, count);
            for (int i = 0; i < count; i++) {
                MemorySegment slice = bulkSegment.asSlice(i * layout.byteSize(), layout.byteSize());
                array[i] = (T) constructor.newInstance(slice, null);
            }
            return array;
        } catch (Exception e) {
            throw new RuntimeException("Failed to allocate struct array", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T extends Struct> T createEmptyStruct(Class<T> type) {
        try {
            String implClassName = type.getName().replace('$', '_') + "Impl";
            Class<?> implClass = Class.forName(implClassName);
            Constructor<?> constructor = implClass.getConstructor(MemorySegment.class, MemoryPool.class);
            return (T) constructor.newInstance(null, null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create empty struct instance: " + type.getName(), e);
        }
    }

    // --- 단일 변수 할당 (초기값 세팅 로직 복구) ---

    public static Pointer<Integer> allocateInt(int val) {
        MemorySegment segment = INT_POOL.allocate();
        segment.set(ValueLayout.JAVA_INT, 0, val); // 초기값 기록
        return new PrimitivePointer<>(segment, ValueLayout.JAVA_INT, Integer.class, INT_POOL);
    }
    public static Pointer<Integer> allocateInt(int val, Arena arena) {
        MemorySegment segment = arena.allocate(ValueLayout.JAVA_INT);
        segment.set(ValueLayout.JAVA_INT, 0, val);
        return new PrimitivePointer<>(segment, ValueLayout.JAVA_INT, Integer.class, null);
    }

    public static Pointer<Long> allocateLong(long val) {
        MemorySegment segment = LONG_POOL.allocate();
        segment.set(ValueLayout.JAVA_LONG, 0, val);
        return new PrimitivePointer<>(segment, ValueLayout.JAVA_LONG, Long.class, LONG_POOL);
    }
    public static Pointer<Long> allocateLong(long val, Arena arena) {
        MemorySegment segment = arena.allocate(ValueLayout.JAVA_LONG);
        segment.set(ValueLayout.JAVA_LONG, 0, val);
        return new PrimitivePointer<>(segment, ValueLayout.JAVA_LONG, Long.class, null);
    }

    public static Pointer<Double> allocateDouble(double val) {
        MemorySegment segment = DOUBLE_POOL.allocate();
        segment.set(ValueLayout.JAVA_DOUBLE, 0, val);
        return new PrimitivePointer<>(segment, ValueLayout.JAVA_DOUBLE, Double.class, DOUBLE_POOL);
    }
    public static Pointer<Double> allocateDouble(double val, Arena arena) {
        MemorySegment segment = arena.allocate(ValueLayout.JAVA_DOUBLE);
        segment.set(ValueLayout.JAVA_DOUBLE, 0, val);
        return new PrimitivePointer<>(segment, ValueLayout.JAVA_DOUBLE, Double.class, null);
    }

    public static Pointer<String> allocateString(int max, String val) {
        MemorySegment segment = Arena.ofAuto().allocate((long) max, 1);
        StringPointer ptr = new StringPointer(segment, max, null);
        ptr.set(val);
        return ptr;
    }
    public static Pointer<String> allocateString(int max, String val, Arena arena) {
        MemorySegment segment = arena.allocate((long) max, 1);
        StringPointer ptr = new StringPointer(segment, max, null);
        ptr.set(val);
        return ptr;
    }

    public static RawBuffer allocateRaw(long size) {
        MemorySegment segment = Arena.ofAuto().allocate(size, 8);
        return new RawBuffer() { @Override public MemorySegment segment() { return segment; } };
    }
    public static RawBuffer allocateRaw(long size, Arena arena) {
        MemorySegment segment = arena.allocate(size, 8);
        return new RawBuffer() { @Override public MemorySegment segment() { return segment; } };
    }
    public static RawBuffer allocateRaw(long size, Allocator allocator) {
        MemorySegment segment = allocator.allocate(size, 8);
        return new RawBuffer() { @Override public MemorySegment segment() { return segment; } };
    }

    // --- 컬렉션 팩토리 ---

    public static <T extends Struct> StructVector<T> createVector(Class<T> type, int initialCapacity) {
        return new StructVectorImpl<>(type, initialCapacity, 0);
    }

    public static <T extends Struct> StructVector<T> createVector(Class<T> type, int initialCapacity, Allocator allocator) {
        return new StructVectorImpl<>(type, initialCapacity, 0, allocator);
    }

    public static <T> StructVector<T> createPrimitiveVector(Class<T> type, int initialCapacity) {
        return new StructVectorImpl<>(type, initialCapacity, 0);
    }

    public static <T> StructVector<T> createPrimitiveVector(Class<T> type, int initialCapacity, Allocator allocator) {
        return new StructVectorImpl<>(type, initialCapacity, 0, allocator);
    }

    public static StructVector<String> createStringVector(int initialCapacity, int elementSize) {
        return new StructVectorImpl<>(String.class, initialCapacity, elementSize);
    }

    public static StructVector<String> createStringVector(int initialCapacity, int elementSize, Allocator allocator) {
        return new StructVectorImpl<>(String.class, initialCapacity, elementSize, allocator);
    }

    public static <K, V> OffHeapHashMap<K, V> createHashMap(Class<K> keyType, Class<V> valueType, int initialCapacity, int keyLen, int valLen) {
        return new OffHeapHashMapImpl<>(keyType, valueType, initialCapacity, keyLen, valLen);
    }

    public static <K, V> OffHeapHashMap<K, V> createHashMap(Class<K> keyType, Class<V> valueType, int initialCapacity, int keyLen, int valLen, Allocator allocator) {
        return new OffHeapHashMapImpl<>(keyType, valueType, initialCapacity, keyLen, valLen, allocator);
    }

    @SuppressWarnings("unchecked")
    public static <T> Pointer<T> createAddressPointer(long address, Class<T> type) {
        if (Struct.class.isAssignableFrom(type)) {
            try {
                String implName = type.getName().replace('$', '_') + "Impl";
                java.lang.foreign.GroupLayout layout = (java.lang.foreign.GroupLayout) Class.forName(implName).getField("LAYOUT").get(null);
                Struct obj = (Struct) createEmptyStruct((Class<? extends Struct>) type);
                obj.rebase(MemorySegment.ofAddress(address).reinterpret(layout.byteSize()));
                return (Pointer<T>) obj.asPointer();
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        
        ValueLayout layout = null;
        if (type == Integer.class) layout = ValueLayout.JAVA_INT;
        else if (type == Long.class) layout = ValueLayout.JAVA_LONG;
        else if (type == Double.class) layout = ValueLayout.JAVA_DOUBLE;
        else if (type == Float.class) layout = ValueLayout.JAVA_FLOAT;
        
        if (layout != null) {
            MemorySegment seg = MemorySegment.ofAddress(address).reinterpret(layout.byteSize());
            return new PrimitivePointer<>(seg, layout, type, null);
        }
        throw new UnsupportedOperationException("Unsupported cast target type: " + type);
    }

    private static <T extends Struct> T allocateProxy(Class<T> type) {
        StructMetadata metadata = METADATA_CACHE.computeIfAbsent(type, MemoryManager::analyzeStruct);
        MemoryPool pool = POOLS.computeIfAbsent(type, k -> new MemoryPool(metadata.layout, DEFAULT_POOL_CAPACITY));
        MemorySegment segment = pool.allocate();

        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                new StructInvocationHandler(segment, metadata, pool)
        );
    }

    public static void free(Struct struct) {
        try {
            Field poolField = struct.getClass().getDeclaredField("pool");
            Field segmentField = struct.getClass().getDeclaredField("segment");
            poolField.setAccessible(true);
            segmentField.setAccessible(true);
            
            MemoryPool pool = (MemoryPool) poolField.get(struct);
            MemorySegment segment = (MemorySegment) segmentField.get(struct);
            if (pool != null) {
                pool.free(segment);
            }
            return;
        } catch (Exception ignored) {}

        if (Proxy.isProxyClass(struct.getClass())) {
            InvocationHandler handler = Proxy.getInvocationHandler(struct);
            if (handler instanceof StructInvocationHandler structHandler) {
                structHandler.pool.free(structHandler.segment);
                return;
            }
        }
    }

    private static StructMetadata analyzeStruct(Class<?> type) {
        List<Method> methods = Arrays.stream(type.getMethods())
                .filter(m -> m.isAnnotationPresent(Struct.Field.class))
                .sorted(Comparator.comparingInt(m -> m.getAnnotation(Struct.Field.class).order()))
                .collect(Collectors.toList());

        Map<String, Class<?>> fieldTypes = new HashMap<>();
        List<MemoryLayout> layoutElements = new ArrayList<>();
        
        for (Method m : methods) {
            if (m.getParameterCount() == 0) {
                String name = m.getName();
                Class<?> returnType = m.getReturnType();
                fieldTypes.put(name, returnType);
                layoutElements.add(ValueLayout.JAVA_BYTE.withName(name));
            }
        }

        GroupLayout layout = MemoryLayout.structLayout(layoutElements.toArray(new MemoryLayout[0]));
        Map<String, VarHandle> handles = new HashMap<>();
        for (String name : fieldTypes.keySet()) {
            handles.put(name, layout.varHandle(MemoryLayout.PathElement.groupElement(name)));
        }

        return new StructMetadata(layout, handles);
    }

    private static class StructMetadata {
        final GroupLayout layout;
        final Map<String, VarHandle> handles;

        StructMetadata(GroupLayout layout, Map<String, VarHandle> handles) {
            this.layout = layout;
            this.handles = handles;
        }
    }

    private static class StructInvocationHandler implements InvocationHandler {
        final MemorySegment segment;
        final StructMetadata metadata;
        final MemoryPool pool;

        StructInvocationHandler(MemorySegment segment, StructMetadata metadata, MemoryPool pool) {
            this.segment = segment;
            this.metadata = metadata;
            this.pool = pool;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("address")) return segment.address();
            if (name.equals("close")) {
                if (pool != null) pool.free(segment);
                return null;
            }
            if (method.getDeclaringClass() == Object.class) return method.invoke(this, args);

            boolean isSetter = method.getParameterCount() > 0;
            VarHandle handle = metadata.handles.get(name);
            if (handle == null) throw new UnsupportedOperationException("Unknown method: " + name);

            if (isSetter) {
                handle.set(segment, 0L, args[0]);
                return null;
            } else {
                return handle.get(segment, 0L);
            }
        }
    }

    private static class PrimitivePointer<T> implements Pointer<T> {
        private MemorySegment segment;
        private final ValueLayout layout;
        private final Class<T> type;
        private MemoryPool pool;

        PrimitivePointer(MemorySegment segment, ValueLayout layout, Class<T> type, MemoryPool pool) {
            this.segment = segment;
            this.layout = layout;
            this.type = type;
            this.pool = pool;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T deref() {
            if (type == Integer.class) return (T) (Integer) segment.get(ValueLayout.JAVA_INT, 0);
            if (type == Long.class) return (T) (Long) segment.get(ValueLayout.JAVA_LONG, 0);
            if (type == Double.class) return (T) (Double) segment.get(ValueLayout.JAVA_DOUBLE, 0);
            if (type == Float.class) return (T) (Float) segment.get(ValueLayout.JAVA_FLOAT, 0);
            throw new UnsupportedOperationException();
        }

        @Override
        public void set(T value) {
            if (type == Integer.class) segment.set(ValueLayout.JAVA_INT, 0, (Integer) value);
            else if (type == Long.class) segment.set(ValueLayout.JAVA_LONG, 0, (Long) value);
            else if (type == Double.class) segment.set(ValueLayout.JAVA_DOUBLE, 0, (Double) value);
            else if (type == Float.class) segment.set(ValueLayout.JAVA_FLOAT, 0, (Float) value);
        }
        
        @Override public long address() { return segment.address(); }
        @Override public <U> Pointer<U> cast(Class<U> targetType) { return createAddressPointer(address(), targetType); }
        @Override public long distanceTo(Pointer<T> other) { return (this.address() - other.address()) / layout.byteSize(); }
        @Override public Pointer<T> offset(long count) { 
            long newAddr = segment.address() + count * layout.byteSize();
            return new PrimitivePointer<>(MemorySegment.ofAddress(newAddr).reinterpret(layout.byteSize()), layout, type, null);
        }
        @Override public Class<T> targetType() { return type; }

        @Override
        public Pointer<T> auto() {
            if (pool == null) return this;
            MemorySegment autoSeg = Arena.ofAuto().allocate(layout);
            MemorySegment.copy(segment, 0, autoSeg, 0, layout.byteSize());
            pool.free(segment);
            this.segment = autoSeg;
            this.pool = null;
            return this;
        }

        @Override
        public void close() {
            if (pool != null) {
                pool.free(segment);
                pool = null;
            }
        }
    }

    private static class StringPointer implements Pointer<String> {
        private MemorySegment segment;
        private final int maxLength;
        private Arena arena;

        StringPointer(MemorySegment segment, int maxLength, Arena arena) {
            this.segment = segment;
            this.maxLength = maxLength;
            this.arena = arena;
        }

        @Override
        public String deref() {
            byte[] b = segment.toArray(ValueLayout.JAVA_BYTE);
            int len = 0; while (len < b.length && b[len] != 0) len++;
            return new String(b, 0, len, StandardCharsets.UTF_8);
        }

        @Override
        public void set(String value) {
            byte[] b = value.getBytes(StandardCharsets.UTF_8);
            int copyLen = Math.min(b.length, maxLength);
            MemorySegment.copy(MemorySegment.ofArray(b), 0, segment, 0, copyLen);
            if (copyLen < maxLength) segment.asSlice(copyLen, maxLength - copyLen).fill((byte) 0);
        }

        @Override public long address() { return segment.address(); }
        @Override public <U> Pointer<U> cast(Class<U> targetType) { return createAddressPointer(address(), targetType); }
        @Override public long distanceTo(Pointer<String> other) { return (this.address() - other.address()) / maxLength; }
        @Override public Pointer<String> offset(long count) { 
            long newAddr = segment.address() + count * maxLength;
            return new StringPointer(MemorySegment.ofAddress(newAddr).reinterpret(maxLength), maxLength, null);
        }
        @Override public Class<String> targetType() { return String.class; }

        @Override
        public Pointer<String> auto() {
            if (arena == null) {
                MemorySegment autoSeg = Arena.ofAuto().allocate(maxLength, 1);
                MemorySegment.copy(segment, 0, autoSeg, 0, maxLength);
                this.segment = autoSeg;
            }
            return this;
        }

        @Override
        public void close() {
            if (arena != null) {
                arena.close();
                arena = null;
            }
        }
    }
}
