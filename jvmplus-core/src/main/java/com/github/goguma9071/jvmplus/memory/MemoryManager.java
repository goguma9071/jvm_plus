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

    public static <T extends Struct> StructArrayView<T> arrayView(Class<T> type, int count) {
        try {
            String implClassName = type.getName().replace('$', '_') + "Impl";
            Class<?> implClass = Class.forName(implClassName);
            java.lang.foreign.GroupLayout layout = (java.lang.foreign.GroupLayout) implClass.getField("LAYOUT").get(null);
            
            MemorySegment bulkSegment = Arena.ofAuto().allocate(layout.byteSize() * count, layout.byteAlignment());
            T flyweight = allocate(type);
            
            return new StructArrayView<>(bulkSegment, layout.byteSize(), count, flyweight);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create struct array view", e);
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
