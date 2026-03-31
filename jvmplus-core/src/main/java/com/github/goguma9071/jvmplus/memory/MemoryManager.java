package com.github.goguma9071.jvmplus.memory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.foreign.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM Plus의 핵심 메모리 관리자.
 * 고성능 할당(MethodHandle), 누수 탐지, mmap, Zero-copy I/O를 지원합니다.
 */
public class MemoryManager {

    private static final Map<Class<?>, MemoryPool> POOLS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, MethodHandle> CONSTRUCTOR_HANDLES = new ConcurrentHashMap<>();
    private static final int DEFAULT_POOL_CAPACITY = 10000;

    // 누수 탐지용 실시간 추적 맵
    private static final Map<Long, AllocationTrace> ALLOCATIONS = new ConcurrentHashMap<>();

    private static final MemoryPool INT_POOL = new MemoryPool(ValueLayout.JAVA_INT, 1000);
    private static final MemoryPool LONG_POOL = new MemoryPool(ValueLayout.JAVA_LONG, 1000);
    private static final MemoryPool DOUBLE_POOL = new MemoryPool(ValueLayout.JAVA_DOUBLE, 1000);

    static {
        // 프로그램 종료 시 해제되지 않은 메모리 리포트
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!ALLOCATIONS.isEmpty()) {
                System.err.println("\n[JPC LEAK DETECTOR] WARNING: Memory leaks detected!");
                ALLOCATIONS.values().forEach(trace -> System.err.println("  > " + trace));
                System.err.println("[JPC LEAK DETECTOR] Total leaked segments: " + ALLOCATIONS.size() + "\n");
            }
        }));
    }

    public static void track(MemorySegment segment) {
        if (segment == null || segment.address() == 0) return;
        ALLOCATIONS.put(segment.address(), AllocationTrace.capture(segment.address(), segment.byteSize()));
    }

    public static void untrack(MemorySegment segment) {
        if (segment == null) return;
        ALLOCATIONS.remove(segment.address());
    }

    /** C++의 블록 스코프와 유사한 RAII 문법 설탕 */
    public static void scoped(java.util.function.Consumer<Arena> action) {
        try (Arena arena = Arena.ofShared()) {
            action.accept(arena);
        }
    }

    public static <T> T scopedReturn(java.util.function.Function<Arena, T> action) {
        try (Arena arena = Arena.ofShared()) {
            return action.apply(arena);
        }
    }

    /** MethodHandle 기반 초고속 할당 (Proxy 제거됨) */
    @SuppressWarnings("unchecked")
    public static <T extends Struct> T allocate(Class<T> type) {
        try {
            MethodHandle handle = getConstructorHandle(type);
            MemoryPool pool = POOLS.computeIfAbsent(type, k -> {
                try {
                    String implName = type.getName().replace('$', '_') + "Impl";
                    GroupLayout layout = (GroupLayout) Class.forName(implName).getField("LAYOUT").get(null);
                    return new MemoryPool(layout, DEFAULT_POOL_CAPACITY);
                } catch (Exception e) { throw new RuntimeException("Generated Impl not found. Build first.", e); }
            });
            MemorySegment seg = pool.allocate();
            track(seg);
            return (T) handle.invoke(seg, pool);
        } catch (Throwable e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException("Allocation failed", e);
        }
    }

    public static <T extends Struct> T allocate(Class<T> type, Arena arena) {
        try {
            MethodHandle handle = getConstructorHandle(type);
            String implName = type.getName().replace('$', '_') + "Impl";
            GroupLayout layout = (GroupLayout) Class.forName(implName).getField("LAYOUT").get(null);
            MemorySegment seg = arena.allocate(layout.byteSize(), layout.byteAlignment());
            track(seg);
            return (T) handle.invoke(seg, null);
        } catch (Throwable e) { throw new RuntimeException(e); }
    }

    public static <T extends Struct> T allocate(Class<T> type, Allocator allocator) {
        try {
            MethodHandle handle = getConstructorHandle(type);
            String implName = type.getName().replace('$', '_') + "Impl";
            GroupLayout layout = (GroupLayout) Class.forName(implName).getField("LAYOUT").get(null);
            MemorySegment seg = allocator.allocate(layout.byteSize(), layout.byteAlignment());
            track(seg);
            return (T) handle.invoke(seg, null);
        } catch (Throwable e) { throw new RuntimeException(e); }
    }

    private static MethodHandle getConstructorHandle(Class<?> type) throws Exception {
        MethodHandle cached = CONSTRUCTOR_HANDLES.get(type);
        if (cached != null) return cached;
        String implName = type.getName().replace('$', '_') + "Impl";
        MethodHandle handle = MethodHandles.publicLookup().findConstructor(
            Class.forName(implName), MethodType.methodType(void.class, MemorySegment.class, MemoryPool.class)
        );
        CONSTRUCTOR_HANDLES.put(type, handle);
        return handle;
    }

    @SuppressWarnings("unchecked")
    public static <T extends Struct> StructArray<T> allocateSoA(Class<T> type, int count) {
        try {
            String implClassName = type.getName().replace('$', '_') + "SoAImpl";
            return (StructArray<T>) Class.forName(implClassName).getConstructor(int.class).newInstance(count);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @SuppressWarnings("unchecked")
    public static <T extends Struct> StructArray<T> map(Path path, long count, Class<T> type) {
        try {
            String implName = type.getName().replace('$', '_') + "Impl";
            GroupLayout layout = (GroupLayout) Class.forName(implName).getField("LAYOUT").get(null);
            FileChannel ch = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
            Arena a = Arena.ofShared();
            MemorySegment seg = ch.map(FileChannel.MapMode.READ_WRITE, 0, layout.byteSize() * count, a);
            track(seg);
            return new StructArrayView<>(seg, layout.byteSize(), (int)count, allocate(type), a);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static MemorySegment createCallback(MethodHandle target, FunctionDescriptor descriptor, Arena arena) {
        MemorySegment stub = Linker.nativeLinker().upcallStub(target, descriptor, arena);
        track(stub);
        return stub;
    }

    public static Object invoke(long address, FunctionDescriptor descriptor, Object... args) {
        try {
            MethodHandle handle = Linker.nativeLinker().downcallHandle(MemorySegment.ofAddress(address), descriptor);
            return handle.invokeWithArguments(args);
        } catch (Throwable e) { throw new RuntimeException(e); }
    }

    public static void write(WritableByteChannel ch, Struct s) throws IOException { ch.write(s.segment().asByteBuffer()); }
    public static void read(ReadableByteChannel ch, Struct s) throws IOException { ch.read(s.segment().asByteBuffer()); }
    public static void write(WritableByteChannel ch, StructArray<?> arr) throws IOException {
        if (arr instanceof StructArrayView<?> v) ch.write(v.segment().asByteBuffer());
        else throw new UnsupportedOperationException();
    }

    public static <T extends Struct> MemoryPool getPool(Class<T> type) {
        allocate(type).free(); 
        return POOLS.get(type);
    }

    @SuppressWarnings("unchecked")
    public static <T extends Struct> T createEmptyStruct(Class<T> type) {
        try {
            return (T) getConstructorHandle(type).invoke(null, null);
        } catch (Throwable e) { throw new RuntimeException(e); }
    }

    // --- 단일 변수 할당 ---

    public static Pointer<Integer> allocateInt(int val) {
        MemorySegment seg = INT_POOL.allocate();
        seg.set(ValueLayout.JAVA_INT, 0, val);
        track(seg);
        return new PrimitivePointer<>(seg, ValueLayout.JAVA_INT, Integer.class, INT_POOL);
    }
    public static Pointer<Integer> allocateInt(int val, Arena a) {
        MemorySegment seg = a.allocate(ValueLayout.JAVA_INT);
        seg.set(ValueLayout.JAVA_INT, 0, val);
        track(seg);
        return new PrimitivePointer<>(seg, ValueLayout.JAVA_INT, Integer.class, null);
    }

    public static Pointer<Long> allocateLong(long val) {
        MemorySegment seg = LONG_POOL.allocate();
        seg.set(ValueLayout.JAVA_LONG, 0, val);
        track(seg);
        return new PrimitivePointer<>(seg, ValueLayout.JAVA_LONG, Long.class, LONG_POOL);
    }
    public static Pointer<Long> allocateLong(long val, Arena a) {
        MemorySegment seg = a.allocate(ValueLayout.JAVA_LONG);
        seg.set(ValueLayout.JAVA_LONG, 0, val);
        track(seg);
        return new PrimitivePointer<>(seg, ValueLayout.JAVA_LONG, Long.class, null);
    }

    public static Pointer<Double> allocateDouble(double val) {
        MemorySegment seg = DOUBLE_POOL.allocate();
        seg.set(ValueLayout.JAVA_DOUBLE, 0, val);
        track(seg);
        return new PrimitivePointer<>(seg, ValueLayout.JAVA_DOUBLE, Double.class, DOUBLE_POOL);
    }
    public static Pointer<Double> allocateDouble(double val, Arena a) {
        MemorySegment seg = a.allocate(ValueLayout.JAVA_DOUBLE);
        seg.set(ValueLayout.JAVA_DOUBLE, 0, val);
        track(seg);
        return new PrimitivePointer<>(seg, ValueLayout.JAVA_DOUBLE, Double.class, null);
    }

    public static Pointer<Float> allocateFloat(float val, Arena a) {
        MemorySegment seg = a.allocate(ValueLayout.JAVA_FLOAT);
        seg.set(ValueLayout.JAVA_FLOAT, 0, val);
        track(seg);
        return new PrimitivePointer<>(seg, ValueLayout.JAVA_FLOAT, Float.class, null);
    }

    public static Pointer<Byte> allocateByte(byte val, Arena a) {
        MemorySegment seg = a.allocate(ValueLayout.JAVA_BYTE);
        seg.set(ValueLayout.JAVA_BYTE, 0, val);
        track(seg);
        return new PrimitivePointer<>(seg, ValueLayout.JAVA_BYTE, Byte.class, null);
    }

    public static Pointer<Character> allocateChar(char val, Arena a) {
        MemorySegment seg = a.allocate(ValueLayout.JAVA_CHAR);
        seg.set(ValueLayout.JAVA_CHAR, 0, val);
        track(seg);
        return new PrimitivePointer<>(seg, ValueLayout.JAVA_CHAR, Character.class, null);
    }

    public static Pointer<Short> allocateShort(short val, Arena a) {
        MemorySegment seg = a.allocate(ValueLayout.JAVA_SHORT);
        seg.set(ValueLayout.JAVA_SHORT, 0, val);
        track(seg);
        return new PrimitivePointer<>(seg, ValueLayout.JAVA_SHORT, Short.class, null);
    }

    public static Pointer<String> allocateString(int max, String val) {
        Arena a = Arena.ofShared();
        MemorySegment seg = a.allocate((long) max, 1);
        track(seg);
        StringPointer ptr = new StringPointer(seg, max, a);
        ptr.set(val);
        return ptr;
    }
    public static Pointer<String> allocateString(int max, String val, Arena a) {
        MemorySegment seg = a.allocate((long) max, 1);
        track(seg);
        StringPointer ptr = new StringPointer(seg, max, null);
        ptr.set(val);
        return ptr;
    }

    public static RawBuffer allocateRaw(long sz) {
        Arena a = Arena.ofShared();
        MemorySegment seg = a.allocate(sz, 8);
        track(seg);
        return new RawBuffer() {
            @Override public MemorySegment segment() { return seg; }
            @Override public void free() { a.close(); untrack(seg); }
        };
    }
    public static RawBuffer allocateRaw(long sz, Arena a) {
        MemorySegment seg = a.allocate(sz, 8);
        track(seg);
        return new RawBuffer() {
            @Override public MemorySegment segment() { return seg; }
            @Override public void free() { untrack(seg); }
        };
    }
    public static RawBuffer allocateRaw(long sz, Allocator alc) {
        MemorySegment seg = alc.allocate(sz, 8);
        track(seg);
        return new RawBuffer() {
            @Override public MemorySegment segment() { return seg; }
            @Override public void free() { alc.free(seg); untrack(seg); }
        };
    }

    public static <T extends Struct> StructArray<T> arrayView(Class<T> type, int count) {
        try {
            String implName = type.getName().replace('$', '_') + "Impl";
            GroupLayout layout = (GroupLayout) Class.forName(implName).getField("LAYOUT").get(null);
            Arena arena = Arena.ofShared();
            MemorySegment bulk = arena.allocate(layout.byteSize() * (long)count, layout.byteAlignment());
            track(bulk);
            return new StructArrayView<>(bulk, layout.byteSize(), count, createEmptyStruct(type), arena);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @SuppressWarnings("unchecked")
    public static <T extends Struct> T[] allocateArray(Class<T> type, int count) {
        try {
            String implName = type.getName().replace('$', '_') + "Impl";
            GroupLayout layout = (GroupLayout) Class.forName(implName).getField("LAYOUT").get(null);
            Arena a = Arena.ofShared();
            MemorySegment bulk = a.allocate(layout.byteSize() * (long)count, layout.byteAlignment());
            track(bulk);
            T[] array = (T[]) java.lang.reflect.Array.newInstance(type, count);
            for (int i = 0; i < count; i++) {
                array[i] = (T) getConstructorHandle(type).invoke(bulk.asSlice(i * layout.byteSize(), layout.byteSize()), null);
            }
            return array;
        } catch (Throwable e) { throw new RuntimeException(e); }
    }

    public static <T extends Struct> StructVector<T> createVector(Class<T> type, int cap) { return new StructVectorImpl<>(type, cap, 0); }
    public static <T extends Struct> StructVector<T> createVector(Class<T> type, int cap, Allocator alc) { return new StructVectorImpl<>(type, cap, 0, alc); }
    public static <T> StructVector<T> createPrimitiveVector(Class<T> type, int cap) { return new StructVectorImpl<>(type, cap, 0); }
    public static <T> StructVector<T> createPrimitiveVector(Class<T> type, int cap, Allocator alc) { return new StructVectorImpl<>(type, cap, 0, alc); }
    public static StructVector<String> createStringVector(int cap, int sz) { return new StructVectorImpl<>(String.class, cap, sz); }
    public static StructVector<String> createStringVector(int cap, int sz, Allocator alc) { return new StructVectorImpl<>(String.class, cap, sz, alc); }
    public static <K, V> OffHeapHashMap<K, V> createHashMap(Class<K> k, Class<V> v, int cap, int klen, int vlen) { return new OffHeapHashMapImpl<>(k, v, cap, klen, vlen); }
    public static <K, V> OffHeapHashMap<K, V> createHashMap(Class<K> k, Class<V> v, int cap, int klen, int vlen, Allocator alc) { return new OffHeapHashMapImpl<>(k, v, cap, klen, vlen, alc); }

    public static void free(Struct struct) {
        if (struct == null) return;
        MemoryPool pool = struct.getPool();
        MemorySegment segment = struct.segment();
        if (pool != null) pool.free(segment);
        untrack(segment);
    }

    private static class PrimitivePointer<T> implements Pointer<T> {
        private MemorySegment segment;
        private final ValueLayout layout;
        private final Class<T> type;
        private MemoryPool pool;

        PrimitivePointer(MemorySegment segment, ValueLayout layout, Class<T> type, MemoryPool pool) {
            this.segment = segment; this.layout = layout; this.type = type; this.pool = pool;
        }

        @Override @SuppressWarnings("unchecked")
        public T deref() {
            if (type == Integer.class) return (T) (Integer) segment.get(ValueLayout.JAVA_INT, 0);
            if (type == Long.class) return (T) (Long) segment.get(ValueLayout.JAVA_LONG, 0);
            if (type == Double.class) return (T) (Double) segment.get(ValueLayout.JAVA_DOUBLE, 0);
            if (type == Float.class) return (T) (Float) segment.get(ValueLayout.JAVA_FLOAT, 0);
            if (type == Byte.class) return (T) (Byte) segment.get(ValueLayout.JAVA_BYTE, 0);
            if (type == Character.class) return (T) (Character) segment.get(ValueLayout.JAVA_CHAR, 0);
            if (type == Short.class) return (T) (Short) segment.get(ValueLayout.JAVA_SHORT, 0);
            throw new UnsupportedOperationException();
        }

        @Override public void set(T v) {
            if (type == Integer.class) segment.set(ValueLayout.JAVA_INT, 0, (Integer) v);
            else if (type == Long.class) segment.set(ValueLayout.JAVA_LONG, 0, (Long) v);
            else if (type == Double.class) segment.set(ValueLayout.JAVA_DOUBLE, 0, (Double) v);
            else if (type == Float.class) segment.set(ValueLayout.JAVA_FLOAT, 0, (Float) v);
            else if (type == Byte.class) segment.set(ValueLayout.JAVA_BYTE, 0, (Byte) v);
            else if (type == Character.class) segment.set(ValueLayout.JAVA_CHAR, 0, (Character) v);
            else if (type == Short.class) segment.set(ValueLayout.JAVA_SHORT, 0, (Short) v);
        }
        
        @Override public long address() { return segment.address(); }
        @Override public <U> Pointer<U> cast(Class<U> t) { return createAddressPointer(address(), t); }
        @Override public long distanceTo(Pointer<T> other) { return (this.address() - other.address()) / layout.byteSize(); }
        @Override public Pointer<T> offset(long c) { 
            long newAddr = segment.address() + c * layout.byteSize();
            return new PrimitivePointer<>(MemorySegment.ofAddress(newAddr).reinterpret(layout.byteSize()), layout, type, null);
        }
        @Override public Class<T> targetType() { return type; }
        @Override public Pointer<T> auto() {
            if (pool == null) return this;
            MemorySegment autoSeg = Arena.ofAuto().allocate(layout);
            MemorySegment.copy(segment, 0, autoSeg, 0, layout.byteSize());
            pool.free(segment);
            untrack(segment);
            this.segment = autoSeg;
            this.pool = null;
            return this;
        }
        @Override public Object invoke(FunctionDescriptor d, Object... a) { return MemoryManager.invoke(address(), d, a); }
        @Override public void free() { if (pool != null) { pool.free(segment); pool = null; } untrack(segment); }

        @Override public String toString() {
            return String.valueOf(deref());
        }
    }

    private static class StringPointer implements Pointer<String> {
        private MemorySegment segment;
        private final int maxLength;
        private Arena arena;

        StringPointer(MemorySegment segment, int maxLength, Arena arena) {
            this.segment = segment; this.maxLength = maxLength; this.arena = arena;
        }

        @Override public String deref() {
            byte[] b = segment.toArray(ValueLayout.JAVA_BYTE);
            int len = 0; while (len < b.length && b[len] != 0) len++;
            return new String(b, 0, len, StandardCharsets.UTF_8);
        }

        @Override public void set(String v) {
            byte[] b = v.getBytes(StandardCharsets.UTF_8);
            int cl = Math.min(b.length, maxLength);
            MemorySegment.copy(MemorySegment.ofArray(b), 0, segment, 0, cl);
            if (cl < maxLength) segment.asSlice(cl, maxLength - cl).fill((byte) 0);
        }

        @Override public long address() { return segment.address(); }
        @Override public <U> Pointer<U> cast(Class<U> t) { return createAddressPointer(address(), t); }
        @Override public long distanceTo(Pointer<String> other) { return (this.address() - other.address()) / maxLength; }
        @Override public Pointer<String> offset(long c) { 
            long newAddr = segment.address() + c * maxLength;
            return new StringPointer(MemorySegment.ofAddress(newAddr).reinterpret(maxLength), maxLength, null);
        }
        @Override public Class<String> targetType() { return String.class; }
        @Override public Pointer<String> auto() {
            if (arena == null) {
                MemorySegment autoSeg = Arena.ofAuto().allocate(maxLength, 1);
                MemorySegment.copy(segment, 0, autoSeg, 0, maxLength);
                this.segment = autoSeg;
            }
            return this;
        }
        @Override public Object invoke(FunctionDescriptor d, Object... a) { return MemoryManager.invoke(address(), d, a); }
        @Override public void free() { if (arena != null) { arena.close(); } else { untrack(segment); } }

        @Override public String toString() {
            return deref();
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> Pointer<T> createAddressPointer(long addr, Class<T> type) {
        return createAddressPointer(addr, type, Arena.global());
    }

    @SuppressWarnings("unchecked")
    public static <T> Pointer<T> createAddressPointer(long addr, Class<T> type, Arena arena) {
        if (Struct.class.isAssignableFrom(type)) {
            try {
                String implName = type.getName().replace('$', '_') + "Impl";
                GroupLayout layout = (GroupLayout) Class.forName(implName).getField("LAYOUT").get(null);
                Struct obj = (Struct) createEmptyStruct((Class<? extends Struct>) type);
                obj.rebase(MemorySegment.ofAddress(addr).reinterpret(layout.byteSize(), arena, s -> {}));
                return (Pointer<T>) obj.asPointer();
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        ValueLayout layout = type == Integer.class ? ValueLayout.JAVA_INT : type == Long.class ? ValueLayout.JAVA_LONG : type == Double.class ? ValueLayout.JAVA_DOUBLE : 
                           type == Float.class ? ValueLayout.JAVA_FLOAT : type == Byte.class ? ValueLayout.JAVA_BYTE : type == Character.class ? ValueLayout.JAVA_CHAR : 
                           type == Short.class ? ValueLayout.JAVA_SHORT : null;
        if (layout != null) {
            return new PrimitivePointer<>(MemorySegment.ofAddress(addr).reinterpret(layout.byteSize(), arena, s -> {}), layout, type, null);
        }
        throw new UnsupportedOperationException();
    }
}
