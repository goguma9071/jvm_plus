package com.github.goguma9071.jvmplus.memory;

import java.lang.invoke.VarHandle;
import java.lang.foreign.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class MemoryManager {

    private static final Map<Class<?>, StructMetadata> METADATA_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, MemoryPool> POOLS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Constructor<?>> CONSTRUCTOR_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, java.lang.foreign.GroupLayout> LAYOUT_CACHE = new ConcurrentHashMap<>();
    private static final int DEFAULT_POOL_CAPACITY = 1000;

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

    /**
     * SIMD 연산에 최적화된 SoA (Struct of Arrays) 구조로 대량의 데이터를 할당합니다.
     */
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

    /**
     * 특정 클래스 전용 메모리 풀을 반환합니다.
     * 이를 통해 preallocate()나 clear() 같은 세부 제어가 가능합니다.
     */
    public static <T extends Struct> MemoryPool getPool(Class<T> type) {
        // 레이아웃 정보를 얻기 위해 한 번 할당 시도 (이미 생성되어 있다면 바로 반환)
        allocate(type).close(); 
        return POOLS.get(type);
    }

    /**
     * 특정 Arena 스코프 내에서 구조체를 할당합니다.
     * Arena가 닫히면 여기서 할당된 메모리도 함께 해제됩니다.
     */
    @SuppressWarnings("unchecked")
    public static <T extends Struct> T allocate(Class<T> type, Arena arena) {
        try {
            String implClassName = type.getName().replace('$', '_') + "Impl";
            Class<?> implClass = Class.forName(implClassName);
            java.lang.foreign.GroupLayout layout = (java.lang.foreign.GroupLayout) implClass.getField("LAYOUT").get(null);
            Constructor<?> constructor = implClass.getConstructor(MemorySegment.class, MemoryPool.class);
            
            MemorySegment segment = arena.allocate(layout.byteSize(), layout.byteAlignment());
            return (T) constructor.newInstance(segment, null); // Pool 없이 개별 Arena 할당
        } catch (Exception e) {
            throw new RuntimeException("Failed to allocate in scoped arena", e);
        }
    }

    public static <T extends Struct> StructArray<T> arrayView(Class<T> type, int count) {
        try {
            String implClassName = type.getName().replace('$', '_') + "Impl";
            Class<?> implClass = Class.forName(implClassName);
            java.lang.foreign.GroupLayout layout = (java.lang.foreign.GroupLayout) implClass.getField("LAYOUT").get(null);
            
            // 결정적 해제를 위해 Arena 생성
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
            return (T) constructor.newInstance(null, null); // Pass nulls for segment and pool
        } catch (Exception e) {
            throw new RuntimeException("Failed to create empty struct instance: " + type.getName(), e);
        }
    }

    // --- 단일 변수(Primitive Variable) 오프힙 할당 기능 ---

    /**
     * 오프힙에 단일 int 변수를 할당하고 포인터를 반환합니다.
     */
    public static Pointer<Integer> allocateInt(int initialValue) {
        MemorySegment seg = Arena.ofAuto().allocate(ValueLayout.JAVA_INT);
        seg.set(ValueLayout.JAVA_INT, 0, initialValue);
        return new PrimitivePointer<>(seg, ValueLayout.JAVA_INT, Integer.class);
    }

    /**
     * 오프힙에 단일 long 변수를 할당하고 포인터를 반환합니다.
     */
    public static Pointer<Long> allocateLong(long initialValue) {
        MemorySegment seg = Arena.ofAuto().allocate(ValueLayout.JAVA_LONG);
        seg.set(ValueLayout.JAVA_LONG, 0, initialValue);
        return new PrimitivePointer<>(seg, ValueLayout.JAVA_LONG, Long.class);
    }

    /**
     * 오프힙에 단일 double 변수를 할당하고 포인터를 반환합니다.
     */
    public static Pointer<Double> allocateDouble(double initialValue) {
        MemorySegment seg = Arena.ofAuto().allocate(ValueLayout.JAVA_DOUBLE);
        seg.set(ValueLayout.JAVA_DOUBLE, 0, initialValue);
        return new PrimitivePointer<>(seg, ValueLayout.JAVA_DOUBLE, Double.class);
    }

    /**
     * 오프힙에 고정 길이 문자열 버퍼를 할당합니다.
     * @param maxLength 최대 바이트 길이
     * @param initialValue 초기값
     */
    public static Pointer<String> allocateString(int maxLength, String initialValue) {
        MemorySegment seg = Arena.ofAuto().allocate((long) maxLength, 1);
        StringPointer ptr = new StringPointer(seg, maxLength);
        ptr.set(initialValue);
        return ptr;
    }

    /**
     * 문자열 전용 포인터 구현체 (UTF-8)
     */
    private static class StringPointer implements Pointer<String> {
        private final MemorySegment segment;
        private final int maxLength;

        StringPointer(MemorySegment segment, int maxLength) {
            this.segment = segment;
            this.maxLength = maxLength;
        }

        @Override
        public String deref() {
            byte[] b = segment.toArray(ValueLayout.JAVA_BYTE);
            int len = 0;
            while (len < b.length && b[len] != 0) len++;
            return new String(b, 0, len, java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public void set(String value) {
            byte[] b = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int copyLen = Math.min(b.length, maxLength);
            MemorySegment.copy(MemorySegment.ofArray(b), 0, segment, 0, copyLen);
            if (copyLen < maxLength) {
                segment.asSlice(copyLen, maxLength - copyLen).fill((byte) 0);
            }
        }

        @Override public long address() { return segment.address(); }
        @Override public Pointer<String> offset(long count) { 
            throw new UnsupportedOperationException("String pointer offset not supported yet"); 
        }
        @Override public Class<String> targetType() { return String.class; }
    }

    /**
     * 기본 타입을 위한 포인터 구현체
     */
    private static class PrimitivePointer<T> implements Pointer<T> {
        private final MemorySegment segment;
        private final ValueLayout layout;
        private final Class<T> type;

        PrimitivePointer(MemorySegment segment, ValueLayout layout, Class<T> type) {
            this.segment = segment;
            this.layout = layout;
            this.type = type;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T deref() {
            if (type == Integer.class) return (T) (Integer) segment.get(ValueLayout.JAVA_INT, 0);
            if (type == Long.class) return (T) (Long) segment.get(ValueLayout.JAVA_LONG, 0);
            if (type == Double.class) return (T) (Double) segment.get(ValueLayout.JAVA_DOUBLE, 0);
            throw new UnsupportedOperationException("Unsupported type: " + type);
        }

        @Override
        public void set(T value) {
            if (type == Integer.class) segment.set(ValueLayout.JAVA_INT, 0, (Integer) value);
            else if (type == Long.class) segment.set(ValueLayout.JAVA_LONG, 0, (Long) value);
            else if (type == Double.class) segment.set(ValueLayout.JAVA_DOUBLE, 0, (Double) value);
            else throw new UnsupportedOperationException("Unsupported type: " + type);
        }
        
        @Override public long address() { return segment.address(); }
        @Override public Pointer<T> offset(long count) { 
            long newAddr = segment.address() + count * layout.byteSize();
            return new PrimitivePointer<>(MemorySegment.ofAddress(newAddr).reinterpret(layout.byteSize()), layout, type);
        }
        @Override public Class<T> targetType() { return type; }
    }

    /**
     * 오프힙에 동적으로 크기가 확장되는 가변 길이 배열(Vector)을 생성합니다.
     */
    public static <T extends Struct> StructVector<T> createVector(Class<T> type, int initialCapacity) {
        return new StructVectorImpl<>(type, initialCapacity, 0);
    }

    /**
     * 오프힙에 동적으로 크기가 확장되는 기본 타입 배열(Vector)을 생성합니다.
     */
    public static <T> StructVector<T> createPrimitiveVector(Class<T> type, int initialCapacity) {
        return new StructVectorImpl<>(type, initialCapacity, 0);
    }

    /**
     * 오프힙에 동적으로 크기가 확장되는 가변 길이 문자열 배열(Vector)을 생성합니다.
     * @param elementSize 각 문자열 요소의 최대 바이트 크기
     */
    public static StructVector<String> createStringVector(int initialCapacity, int elementSize) {
        return new StructVectorImpl<>(String.class, initialCapacity, elementSize);
    }

    /**
     * 오프힙에 데이터를 저장하는 고성능 해시맵을 생성합니다.
     * @param keyLen 키가 문자열일 경우 최대 바이트 길이 (그 외엔 0)
     * @param valLen 값이 문자열일 경우 최대 바이트 길이 (그 외엔 0)
     */
    public static <K, V> OffHeapHashMap<K, V> createHashMap(Class<K> keyType, Class<V> valueType, int initialCapacity, int keyLen, int valLen) {
        return new OffHeapHashMapImpl<>(keyType, valueType, initialCapacity, keyLen, valLen);
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
        long currentOffset = 0;
        
        for (Method m : methods) {
            if (m.getParameterCount() == 0) {
                String name = m.getName();
                Class<?> returnType = m.getReturnType();
                fieldTypes.put(name, returnType);
                
                ValueLayout fieldLayout = typeToLayout(returnType).withName(name);
                long alignment = fieldLayout.byteAlignment();
                
                if (currentOffset % alignment != 0) {
                    long padding = alignment - (currentOffset % alignment);
                    layoutElements.add(MemoryLayout.paddingLayout(padding));
                    currentOffset += padding;
                }
                
                layoutElements.add(fieldLayout);
                currentOffset += fieldLayout.byteSize();
            }
        }

        GroupLayout layout = MemoryLayout.structLayout(layoutElements.toArray(new MemoryLayout[0]));
        Map<String, VarHandle> handles = new HashMap<>();
        for (String name : fieldTypes.keySet()) {
            handles.put(name, layout.varHandle(MemoryLayout.PathElement.groupElement(name)));
        }

        return new StructMetadata(layout, handles);
    }

    private static ValueLayout typeToLayout(Class<?> type) {
        if (type == int.class) return ValueLayout.JAVA_INT;
        if (type == long.class) return ValueLayout.JAVA_LONG;
        if (type == double.class) return ValueLayout.JAVA_DOUBLE;
        if (type == float.class) return ValueLayout.JAVA_FLOAT;
        if (type == byte.class) return ValueLayout.JAVA_BYTE;
        throw new IllegalArgumentException("Unsupported type: " + type);
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
                pool.free(segment);
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
}
