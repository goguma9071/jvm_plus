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
 * [부트스트래핑 완료] 모든 레지스트리와 추적 데이터가 오프힙 구조체로 관리됩니다.
 */
public class MemoryManager {

    // [부트스트래핑] 자바 객체(Pool, Handle)를 오프힙 ID와 매핑하기 위한 레지스트리
    private static final HandleRegistry<MemoryPool> POOL_REGISTRY = new HandleRegistry<>();
    private static final HandleRegistry<MethodHandle> CONSTRUCTOR_REGISTRY = new HandleRegistry<>();

    // [부트스트래핑] 클래스 이름(String) -> 핸들 ID(Integer) 맵 (오프힙)
    private static OffHeapHashMap<String, Integer> POOL_MAP;
    private static OffHeapHashMap<String, Integer> CONSTRUCTOR_MAP;

    private static final int DEFAULT_POOL_CAPACITY = 10000;

    // 누수 탐지용 실시간 추적 맵 (오프힙)
    private static OffHeapHashMap<Long, AllocationTrace> ALLOCATIONS;
    private static boolean isBootstrapping = true;

    private static final MemoryPool INT_POOL = new MemoryPool(ValueLayout.JAVA_INT, 1000);
    private static final MemoryPool LONG_POOL = new MemoryPool(ValueLayout.JAVA_LONG, 1000);
    private static final MemoryPool DOUBLE_POOL = new MemoryPool(ValueLayout.JAVA_DOUBLE, 1000);

    // [부트스트래핑용] AllocationTrace 레이아웃 수동 정의
    private static final GroupLayout TRACE_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("address"),
        ValueLayout.JAVA_LONG.withName("size"),
        MemoryLayout.sequenceLayout(256, ValueLayout.JAVA_BYTE).withName("stackTrace")
    );

    static {
        // 1. 기초 오프힙 맵들을 먼저 생성 (이 과정은 철저히 추적 제외)
        isBootstrapping = true; 
        POOL_MAP = new OffHeapHashMapImpl<>(String.class, Integer.class, 100, 64, 4);
        CONSTRUCTOR_MAP = new OffHeapHashMapImpl<>(String.class, Integer.class, 100, 64, 4);
        
        long traceSize = TRACE_LAYOUT.byteSize(); 
        ALLOCATIONS = new OffHeapHashMapImpl<>(Long.class, AllocationTrace.class, 1000, 8L, traceSize);
        isBootstrapping = false;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // 종료 시점에는 추적 중단
            isBootstrapping = true; 
            if (ALLOCATIONS != null && ALLOCATIONS.size() > 0) {
                System.err.println("\n" + "=".repeat(50));
                System.err.println("[JPC LEAK DETECTOR] WARNING: Memory leaks detected!");
                System.err.println("-".repeat(50));
                
                ALLOCATIONS.forEachRaw((addr, vSeg) -> {
                    AllocationTrace trace = createManualTraceView(vSeg, null);
                    System.err.println("  > Address: 0x" + Long.toHexString(trace.address()).toUpperCase());
                    System.err.println("    Size:    " + trace.size() + " bytes");
                    System.err.println("    Loc:     " + trace.stackTrace());
                    System.err.println();
                });
                
                System.err.println("[JPC LEAK DETECTOR] Total leaked segments: " + ALLOCATIONS.size());
                System.err.println("=".repeat(50) + "\n");
            }
            if (POOL_MAP != null) POOL_MAP.free();
            if (CONSTRUCTOR_MAP != null) CONSTRUCTOR_MAP.free();
            if (ALLOCATIONS != null) ALLOCATIONS.free();
        }));
    }

    public static void track(MemorySegment segment) {
        if (isBootstrapping || segment == null || segment.address() == 0) return;
        
        long addr = segment.address();
        isBootstrapping = true; 
        try {
            Integer handleId = POOL_MAP.get(AllocationTrace.class.getName());
            MemoryPool tracePool;
            if (handleId == null) {
                tracePool = new MemoryPool(TRACE_LAYOUT, 1000);
                POOL_MAP.put(AllocationTrace.class.getName(), POOL_REGISTRY.register(tracePool));
            } else {
                tracePool = POOL_REGISTRY.get(handleId);
            }

            MemorySegment traceSeg = tracePool.allocate();
            AllocationTrace trace = createManualTraceView(traceSeg, tracePool);
            
            // 실제 호출 스택 캡처 (현재 track 호출자 찾기)
            String stack = Arrays.stream(Thread.currentThread().getStackTrace())
                .skip(2)
                .filter(e -> !e.getClassName().contains("MemoryManager") && 
                             !e.getClassName().contains("JPhelper") &&
                             !e.getClassName().contains("Impl") &&
                             !e.getClassName().contains("MemoryPool"))
                .limit(1)
                .map(StackTraceElement::toString)
                .findFirst().orElse("unknown");

            trace.address(addr);
            trace.size(segment.byteSize());
            trace.stackTrace(stack);
            
            ALLOCATIONS.put(addr, trace);
            trace.free();
        } finally {
            isBootstrapping = false;
        }
    }

    private static AllocationTrace createManualTraceView(MemorySegment s, MemoryPool p) {
        return new AllocationTrace() {
            private MemorySegment seg = s;
            @Override public long address() { return seg.get(ValueLayout.JAVA_LONG, 0); }
            @Override public AllocationTrace address(long a) { seg.set(ValueLayout.JAVA_LONG, 0, a); return this; }
            @Override public long size() { return seg.get(ValueLayout.JAVA_LONG, 8); }
            @Override public AllocationTrace size(long sz) { seg.set(ValueLayout.JAVA_LONG, 8, sz); return this; }
            @Override public String stackTrace() { return ""; }
            @Override public AllocationTrace stackTrace(String t) { 
                byte[] b = t.getBytes(StandardCharsets.UTF_8);
                int len = Math.min(b.length, 256);
                MemorySegment.copy(MemorySegment.ofArray(b), 0, seg, 16, len);
                return this;
            }
            @Override public MemorySegment segment() { return seg; }
            @Override public MemoryPool getPool() { return p; }
            @Override public void rebase(MemorySegment ns) { this.seg = ns; }
            @Override public <T extends Struct> Pointer<T> asPointer() { return null; }
            @Override public void free() { p.free(seg); }
        };
    }

    public static void untrack(MemorySegment segment) {
        if (isBootstrapping || segment == null || ALLOCATIONS == null) return;
        ALLOCATIONS.remove(segment.address());
    }

    public static OffHeapString allocateDynamicString(String initialValue) {
        OffHeapString s = allocate(OffHeapString.class);
        s.set(initialValue);
        return s;
    }

    private static class DynamicStringView implements OffHeapString {
        private MemorySegment seg;
        private MemoryPool pool;
        private Arena stringArena; // 개별 문자열 데이터를 담는 아레나

        DynamicStringView(MemorySegment seg, MemoryPool pool) {
            this.seg = seg;
            this.pool = pool;
        }

        @Override public Pointer<Byte> data() { 
            long addr = seg.get(ValueLayout.JAVA_LONG, 0);
            return addr == 0 ? null : createAddressPointer(addr, Byte.class);
        }
        @Override public OffHeapString data(Pointer<Byte> p) { seg.set(ValueLayout.ADDRESS, 0, MemorySegment.ofAddress(p.address())); return this; }
        @Override public long length() { return seg.get(ValueLayout.JAVA_LONG, 8); }
        @Override public OffHeapString length(long len) { seg.set(ValueLayout.JAVA_LONG, 8, len); return this; }
        @Override public long capacity() { return seg.get(ValueLayout.JAVA_LONG, 16); }
        @Override public OffHeapString capacity(long cap) { seg.set(ValueLayout.JAVA_LONG, 16, cap); return this; }

        @Override public void set(String value) {
            byte[] b = value.getBytes(StandardCharsets.UTF_8);
            long needed = b.length + 1; // Null-terminator
            long currentCap = capacity();

            if (needed > currentCap) {
                // 기존 데이터 해제 (있다면)
                if (stringArena != null) stringArena.close();
                
                stringArena = Arena.ofShared();
                MemorySegment newSeg = stringArena.allocate(needed, 1);
                track(newSeg);
                
                data(createAddressPointer(newSeg.address(), Byte.class));
                capacity(needed);
            }
            
            MemorySegment dataSeg = MemorySegment.ofAddress(data().address()).reinterpret(needed);
            MemorySegment.copy(MemorySegment.ofArray(b), 0, dataSeg, 0, b.length);
            dataSeg.set(ValueLayout.JAVA_BYTE, b.length, (byte) 0);
            length(b.length);
        }

        @Override public String toString() {
            Pointer<Byte> p = data();
            if (p == null) return "";
            MemorySegment dataSeg = MemorySegment.ofAddress(p.address()).reinterpret(length() + 1);
            return new String(dataSeg.asSlice(0, length()).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
        }

        @Override public long address() { return seg.address(); }
        @Override public MemorySegment segment() { return seg; }
        @Override public MemoryPool getPool() { return pool; }
        @Override public void rebase(MemorySegment ns) { this.seg = ns; }
        @Override public <T extends Struct> Pointer<T> asPointer() { return null; }
        @Override public void free() {
            if (stringArena != null) stringArena.close();
            if (pool != null) pool.free(seg);
            untrack(seg);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T extends Struct> T allocate(Class<T> type) {
        try {
            // [부트스트래핑] OffHeapString에 대한 수동 View 생성 지원
            if (type == OffHeapString.class) {
                String className = type.getName();
                Integer poolHandleId = POOL_MAP.get(className);
                MemoryPool pool;
                if (poolHandleId == null) {
                    // OffHeapString 레이아웃: data(8) + length(8) + capacity(8) = 24바이트
                    GroupLayout layout = MemoryLayout.structLayout(
                        ValueLayout.ADDRESS.withName("data"),
                        ValueLayout.JAVA_LONG.withName("length"),
                        ValueLayout.JAVA_LONG.withName("capacity")
                    );
                    pool = new MemoryPool(layout, 1000);
                    POOL_MAP.put(className, POOL_REGISTRY.register(pool));
                } else {
                    pool = POOL_REGISTRY.get(poolHandleId);
                }
                return (T) new DynamicStringView(pool.allocate(), pool);
            }

            MethodHandle handle = getConstructorHandle(type);
            String className = type.getName();
            Integer poolHandleId = POOL_MAP.get(className);
            MemoryPool pool;
            
            if (poolHandleId == null) {
                String implName = className.replace('$', '_') + "Impl";
                GroupLayout layout = (GroupLayout) Class.forName(implName).getField("LAYOUT").get(null);
                pool = new MemoryPool(layout, DEFAULT_POOL_CAPACITY);
                POOL_MAP.put(className, POOL_REGISTRY.register(pool));
            } else {
                pool = POOL_REGISTRY.get(poolHandleId);
            }

            MemorySegment seg = pool.allocate();
            track(seg);
            return (T) handle.invoke(seg, pool);
        } catch (Throwable e) {
            throw new RuntimeException("Allocation failed for " + type.getName(), e);
        }
    }

    private static MethodHandle getConstructorHandle(Class<?> type) throws Exception {
        String className = type.getName();
        Integer handleId = CONSTRUCTOR_MAP.get(className);
        if (handleId != null) return CONSTRUCTOR_REGISTRY.get(handleId);

        String implName = className.replace('$', '_') + "Impl";
        MethodHandle handle = MethodHandles.publicLookup().findConstructor(
            Class.forName(implName), MethodType.methodType(void.class, MemorySegment.class, MemoryPool.class)
        );
        CONSTRUCTOR_MAP.put(className, CONSTRUCTOR_REGISTRY.register(handle));
        return handle;
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

    public static <T extends Struct> StructArray<T> allocateSoA(Class<T> type, int count) {
        try {
            String implClassName = type.getName().replace('$', '_') + "SoAImpl";
            return (StructArray<T>) Class.forName(implClassName).getConstructor(int.class).newInstance(count);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

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
        String className = type.getName();
        Integer id = POOL_MAP.get(className);
        return id != null ? POOL_REGISTRY.get(id) : null;
    }

    @SuppressWarnings("unchecked")
    public static <T extends Struct> T createEmptyStruct(Class<T> type) {
        try {
            return (T) getConstructorHandle(type).invoke(null, null);
        } catch (Throwable e) { throw new RuntimeException(e); }
    }

    public static Pointer<Integer> allocateInt(int val) {
        MemorySegment seg = INT_POOL.allocate();
        seg.set(ValueLayout.JAVA_INT, 0, val);
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

    @SuppressWarnings("unchecked")
    public static <T> Var<T> allocateVar(T initialValue) {
        Pointer<T> ptr;
        if (initialValue instanceof Integer v) ptr = (Pointer<T>) allocateInt(v);
        else if (initialValue instanceof Long v) ptr = (Pointer<T>) allocateLong(v);
        else if (initialValue instanceof Double v) ptr = (Pointer<T>) allocateDouble(v);
        else if (initialValue instanceof Pointer<?> v) {
            MemorySegment seg = LONG_POOL.allocate();
            seg.set(ValueLayout.ADDRESS, 0, MemorySegment.ofAddress(v.address()));
            ptr = (Pointer<T>) new PrimitivePointer<>(seg, ValueLayout.ADDRESS, v.getClass(), v.targetType(), LONG_POOL);
        }
        else throw new UnsupportedOperationException("Unsupported type for Var: " + initialValue.getClass());
        
        return new OffHeapVar<>(ptr);
    }

    public static Var<String> allocateStringVar(int max, String initialValue) {
        return new OffHeapVar<>(allocateString(max, initialValue));
    }

    private static class OffHeapVar<T> implements Var<T> {
        private final Pointer<T> ptr;
        OffHeapVar(Pointer<T> ptr) { this.ptr = ptr; }
        @Override public T get() { return ptr.deref(); }
        @Override public void set(T value) { ptr.set(value); }
        @Override public Pointer<T> asPointer() { return ptr; }
        @Override public void free() { ptr.free(); }
        @Override public String toString() { return String.valueOf(get()); }
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
        private final Class<?> componentType;
        private MemoryPool pool;

        PrimitivePointer(MemorySegment segment, ValueLayout layout, Class<T> type, MemoryPool pool) {
            this(segment, layout, type, null, pool);
        }

        PrimitivePointer(MemorySegment segment, ValueLayout layout, Class<T> type, Class<?> componentType, MemoryPool pool) {
            this.segment = segment; this.layout = layout; this.type = type; 
            this.componentType = componentType; this.pool = pool;
        }

        @Override @SuppressWarnings("unchecked")
        public T deref() {
            if (type != null && Pointer.class.isAssignableFrom(type)) {
                long addr = segment.get(ValueLayout.ADDRESS, 0).address();
                if (addr == 0) return null;
                Class<?> target = componentType != null ? componentType : Object.class;
                return (T) createAddressPointer(addr, (Class) target);
            }
            if (type == Integer.class) return (T) (Integer) segment.get(ValueLayout.JAVA_INT, 0);
            if (type == Long.class) return (T) (Long) segment.get(ValueLayout.JAVA_LONG, 0);
            if (type == Double.class) return (T) (Double) segment.get(ValueLayout.JAVA_DOUBLE, 0);
            if (type == Float.class) return (T) (Float) segment.get(ValueLayout.JAVA_FLOAT, 0);
            if (type == Byte.class) return (T) (Byte) segment.get(ValueLayout.JAVA_BYTE, 0);
            if (type == Character.class) return (T) (Character) segment.get(ValueLayout.JAVA_CHAR, 0);
            if (type == Short.class) return (T) (Short) segment.get(ValueLayout.JAVA_SHORT, 0);
            throw new UnsupportedOperationException("Cannot dereference type: " + type);
        }

        @Override public void set(T v) {
            if (v instanceof Pointer<?> p) {
                segment.set(ValueLayout.ADDRESS, 0, MemorySegment.ofAddress(p.address()));
            }
            else if (type == Integer.class) segment.set(ValueLayout.JAVA_INT, 0, (Integer) v);
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
            return new PrimitivePointer<>(MemorySegment.ofAddress(newAddr).reinterpret(layout.byteSize()), layout, type, componentType, null);
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
        @Override public String toString() { return "ptr@0x" + Long.toHexString(address()).toUpperCase(); }
    }

    private static class StringPointer implements Pointer<String> {
        private MemorySegment segment;
        private final int maxLength;
        private Arena arena;
        StringPointer(MemorySegment segment, int maxLength, Arena arena) { this.segment = segment; this.maxLength = maxLength; this.arena = arena; }
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
        @Override public void free() { 
            untrack(segment);
            if (arena != null) { arena.close(); }
        }
        @Override public String toString() { return "ptr@0x" + Long.toHexString(address()).toUpperCase(); }
    }

    @SuppressWarnings("unchecked")
    public static <T> Pointer<T> createAddressPointer(long addr, Class<T> type) { return createAddressPointer(addr, type, Arena.global()); }

    @SuppressWarnings("unchecked")
    public static <T> Pointer<T> createAddressPointer(long addr, Class<T> type, Arena arena) {
        if (type != null && Pointer.class.isAssignableFrom(type)) {
            MemorySegment seg = MemorySegment.ofAddress(addr).reinterpret(8, arena, s -> {});
            return (Pointer<T>) new PrimitivePointer<>(seg, ValueLayout.ADDRESS, Long.class, null);
        }
        if (type != null && Struct.class.isAssignableFrom(type)) {
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
