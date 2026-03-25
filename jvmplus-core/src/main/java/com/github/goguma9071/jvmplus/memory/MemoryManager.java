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
    private static final int DEFAULT_POOL_CAPACITY = 1000;

    /**
     * 구현체에서 사용할 VarHandle 조회용 메소드 (미니 컴파일러 지원)
     */
    public static VarHandle getHandle(Class<?> type, String fieldName) {
        StructMetadata metadata = METADATA_CACHE.computeIfAbsent(type, MemoryManager::analyzeStruct);
        return metadata.handles.get(fieldName);
    }

    @SuppressWarnings("unchecked")
    public static <T extends Struct> T allocate(Class<T> type) {
        // 1. 컴파일러가 생성한 구현체(Impl)가 있는지 먼저 확인 (Lombok 방식 최적화)
        try {
            Class<?> implClass = Class.forName(type.getName() + "Impl");
            StructMetadata metadata = METADATA_CACHE.computeIfAbsent(type, MemoryManager::analyzeStruct);
            MemoryPool pool = POOLS.computeIfAbsent(type, k -> new MemoryPool(metadata.layout, DEFAULT_POOL_CAPACITY));
            
            Constructor<?> constructor = implClass.getConstructor(MemorySegment.class, MemoryPool.class);
            return (T) constructor.newInstance(pool.allocate(), pool);
        } catch (Exception e) {
            // 2. 구현체가 없으면 기존의 느린 Proxy 방식으로 동작 (하위 호환)
            return allocateProxy(type);
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
        // 1. 컴파일러 생성 구현체인 경우
        try {
            Field poolField = struct.getClass().getDeclaredField("pool");
            Field segmentField = struct.getClass().getDeclaredField("segment");
            poolField.setAccessible(true);
            segmentField.setAccessible(true);
            
            MemoryPool pool = (MemoryPool) poolField.get(struct);
            MemorySegment segment = (MemorySegment) segmentField.get(struct);
            pool.free(segment);
            return;
        } catch (Exception ignored) {}

        // 2. Proxy인 경우
        if (Proxy.isProxyClass(struct.getClass())) {
            InvocationHandler handler = Proxy.getInvocationHandler(struct);
            if (handler instanceof StructInvocationHandler structHandler) {
                structHandler.pool.free(structHandler.segment);
                return;
            }
        }
    }

    private static StructMetadata analyzeStruct(Class<?> type) {
        List<Method> methods = Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Struct.Field.class))
                .sorted(Comparator.comparingInt(m -> m.getAnnotation(Struct.Field.class).order()))
                .collect(Collectors.toList());

        Map<String, Class<?>> fieldTypes = new HashMap<>();
        List<MemoryLayout> layoutElements = new ArrayList<>();
        long currentOffset = 0;
        
        for (Method m : methods) {
            if (m.getParameterCount() == 0) { // Getter
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
