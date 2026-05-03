package com.github.goguma9071.jvmplus;

import com.github.goguma9071.jvmplus.memory.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.nio.file.Paths;
import java.nio.channels.WritableByteChannel;
import java.nio.channels.ReadableByteChannel;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * Jvm Plus의 모든 기능을 짧고 직관적으로 사용하기 위한 문법적 설탕 클래스입니다.
 */
public final class JPhelper {
    private JPhelper() {}

    // --- [1] 기본 할당 (Manual/RAII) ---

    public static Pointer<Integer> ptr(int v) { return MemoryManager.allocateInt(v); }
    public static Pointer<Long> ptr(long v) { return MemoryManager.allocateLong(v); }
    public static Pointer<Double> ptr(double v) { return MemoryManager.allocateDouble(v); }
    public static Pointer<String> ptr(String v, int max) { return MemoryManager.allocateString(max, v); }

    public static <T> Var<T> var(T v) { return MemoryManager.allocateVar(v); }
    public static <T> Var<Pointer<T>> var(Pointer<T> v) { return (Var<Pointer<T>>) (Object) MemoryManager.allocateVar(v); }
    public static Var<String> var(String v, int max) { return MemoryManager.allocateStringVar(max, v); }

    public static <T extends Struct> T alloc(Class<T> type) {
        return MemoryManager.allocate(type);
    }

    /** 
     * [신규] 스레드 로컬 Flyweight 객체를 사용하여 할당을 수행합니다. (GC Overhead Zero)
     * 내부적으로 Java 껍데기 객체를 단 1개만 만들어 두고 주소만 갈아끼우며 재사용합니다.
     * 주의: 반환된 객체는 같은 스레드의 다음 allocFlyweight 호출 시 상태가 변경될 수 있습니다.
     */
    public static <T extends Struct> T allocFlyweight(Class<T> type) {
        return MemoryManager.allocateFlyweight(type);
    }

    /** 
     * [신규] 특정 주소(MemorySegment)를 구조체 타입으로 바라봅니다. (C++의 Pointer Cast와 동일)
     * 내부적으로 Flyweight를 사용하므로 할당 오버헤드가 없으며, 객체 지향 문법을 그대로 사용할 수 있습니다.
     */
    public static <T extends Struct> T ref(MemorySegment seg, Class<T> type) {
        return MemoryManager.rebindFlyweight(type, seg);
    }

    /** 가변 길이 오프힙 문자열 할당 */
    public static OffHeapString string(String v) {
        return MemoryManager.allocateDynamicString(v);
    }

    /** 할당과 동시에 초기화를 수행합니다. */
    public static <T extends Struct> T alloc(Class<T> type, Consumer<T> init) {
        T struct = alloc(type);
        init.accept(struct);
        return struct;
    }

    /** SoA(Structure of Arrays) 할당 설탕 */
    public static <T extends Struct> StructArray<T> allocSoA(Class<T> type, int count) {
        return MemoryManager.allocateSoA(type, count);
    }

    public static AutoCloseable suppress() {
        return MemoryManager.suppress();
    }

    public static void ignore(MemorySegment segment) { MemoryManager.ignore(segment); }
    public static void ignore(Struct struct) { MemoryManager.ignore(struct); }
    public static void ignore(StructArray<?> array) { 
        if (array instanceof com.github.goguma9071.jvmplus.memory.StructArrayView<?> v) MemoryManager.ignore(v.segment()); 
    }

    // --- [2] 특정 아레나 종속 ---

    public static Pointer<Integer> ptr(int v, Arena a) { return MemoryManager.allocateInt(v, a); }
    public static Pointer<Long> ptr(long v, Arena a) { return MemoryManager.allocateLong(v, a); }
    public static Pointer<Double> ptr(double v, Arena a) { return MemoryManager.allocateDouble(v, a); }
    public static Pointer<Float> ptr(float v, Arena a) { return MemoryManager.allocateFloat(v, a); }
    public static Pointer<Byte> ptr(byte v, Arena a) { return MemoryManager.allocateByte(v, a); }
    public static Pointer<Character> ptr(char v, Arena a) { return MemoryManager.allocateChar(v, a); }
    public static Pointer<Short> ptr(short v, Arena a) { return MemoryManager.allocateShort(v, a); }
    public static Pointer<String> ptr(String v, int max, Arena a) { return MemoryManager.allocateString(max, v, a); }

    public static <T extends Struct> T alloc(Class<T> type, Arena arena) {
        return MemoryManager.allocate(type, arena);
    }

    /** 특정 아레나에 할당함과 동시에 초기화를 수행합니다. */
    public static <T extends Struct> T alloc(Class<T> type, Arena arena, Consumer<T> init) {
        T struct = alloc(type, arena);
        init.accept(struct);
        return struct;
    }

    public static <T extends Struct> T alloc(Class<T> type, Allocator allocator) {
        return MemoryManager.allocate(type, allocator);
    }

    // --- [2.1] Zero-Shell (No Java Object) 전용 할당 및 접근 ---

    /** 구조체 타입에 맞는 메모리 풀을 가져옵니다. */
    public static <T extends Struct> MemoryPool pool(Class<T> type) {
        return MemoryManager.getPool(type);
    }

    /** 자바 객체 생성 없이 원시 메모리 세그먼트만 할당합니다. (C의 malloc과 동일) */
    public static <T extends Struct> MemorySegment malloc(Class<T> type) {
        return MemoryManager.getPool(type).allocate();
    }

    /** 자바 객체 생성 없이 원시 메모리 주소(long)만 반환합니다. (진정한 C 스타일) */
    public static <T extends Struct> long malloc_addr(Class<T> type) {
        return MemoryManager.getPool(type).allocate().address();
    }

    /** 여러 개의 구조체 공간을 한꺼번에 할당합니다. (C의 malloc(size * count)와 동일) */
    public static <T extends Struct> MemorySegment malloc(Class<T> type, int count) {
        MemoryPool pool = MemoryManager.getPool(type);
        long size = pool.allocate().byteSize(); // 하나를 할당해 사이즈 확인 (캐싱됨)
        // 실제로는 MemoryPool에 bulk allocate가 필요할 수 있으나 우선은 Arena로 대응
        return Arena.ofShared().allocate(size * count, 8);
    }

    /** 특정 아레나에서 단일 i32(int) 메모리를 할당합니다. */
    public static MemorySegment malloc_i32(int v, Arena a) {
        MemorySegment s = a.allocate(ValueLayout.JAVA_INT);
        s.set(ValueLayout.JAVA_INT, 0, v);
        return s;
    }

    // 기본 타입용 정적 접근자 확장 (Zero-Shell, MemorySegment version)
    public static void set_i8(MemorySegment s, byte v) { s.set(ValueLayout.JAVA_BYTE, 0, v); }
    public static byte get_i8(MemorySegment s) { return s.get(ValueLayout.JAVA_BYTE, 0); }
    public static void set_i16(MemorySegment s, short v) { s.set(ValueLayout.JAVA_SHORT, 0, v); }
    public static short get_i16(MemorySegment s) { return s.get(ValueLayout.JAVA_SHORT, 0); }
    public static void set_i32(MemorySegment s, int v) { s.set(ValueLayout.JAVA_INT, 0, v); }
    public static int get_i32(MemorySegment s) { return s.get(ValueLayout.JAVA_INT, 0); }
    public static void set_i64(MemorySegment s, long v) { s.set(ValueLayout.JAVA_LONG, 0, v); }
    public static long get_i64(MemorySegment s) { return s.get(ValueLayout.JAVA_LONG, 0); }
    public static void set_f32(MemorySegment s, float v) { s.set(ValueLayout.JAVA_FLOAT, 0, v); }
    public static float get_f32(MemorySegment s) { return s.get(ValueLayout.JAVA_FLOAT, 0); }
    public static void set_f64(MemorySegment s, double v) { s.set(ValueLayout.JAVA_DOUBLE, 0, v); }
    public static double get_f64(MemorySegment s) { return s.get(ValueLayout.JAVA_DOUBLE, 0); }

    // 기본 타입용 정적 접근자 (Zero-Shell, long address version)
    public static void set_i8(long a, byte v) { MemorySegment.ofAddress(a).reinterpret(1).set(ValueLayout.JAVA_BYTE, 0, v); }
    public static byte get_i8(long a) { return MemorySegment.ofAddress(a).reinterpret(1).get(ValueLayout.JAVA_BYTE, 0); }
    public static void set_i16(long a, short v) { MemorySegment.ofAddress(a).reinterpret(2).set(ValueLayout.JAVA_SHORT, 0, v); }
    public static short get_i16(long a) { return MemorySegment.ofAddress(a).reinterpret(2).get(ValueLayout.JAVA_SHORT, 0); }
    public static void set_i32(long a, int v) { MemorySegment.ofAddress(a).reinterpret(4).set(ValueLayout.JAVA_INT, 0, v); }
    public static int get_i32(long a) { return MemorySegment.ofAddress(a).reinterpret(4).get(ValueLayout.JAVA_INT, 0); }
    public static void set_i64(long a, long v) { MemorySegment.ofAddress(a).reinterpret(8).set(ValueLayout.JAVA_LONG, 0, v); }
    public static long get_i64(long a) { return MemorySegment.ofAddress(a).reinterpret(8).get(ValueLayout.JAVA_LONG, 0); }
    public static void set_f32(long a, float v) { MemorySegment.ofAddress(a).reinterpret(4).set(ValueLayout.JAVA_FLOAT, 0, v); }
    public static float get_f32(long a) { return MemorySegment.ofAddress(a).reinterpret(4).get(ValueLayout.JAVA_FLOAT, 0); }
    public static void set_f64(long a, double v) { MemorySegment.ofAddress(a).reinterpret(8).set(ValueLayout.JAVA_DOUBLE, 0, v); }
    public static double get_f64(long a) { return MemorySegment.ofAddress(a).reinterpret(8).get(ValueLayout.JAVA_DOUBLE, 0); }

    // 원자적 연산 지원 (Zero-Shell)
    public static boolean cas_i32(MemorySegment s, int exp, int val) {
        return ValueLayout.JAVA_INT.varHandle().compareAndSet(s, 0L, exp, val);
    }
    public static int add_i32(MemorySegment s, int delta) {
        return (int) ValueLayout.JAVA_INT.varHandle().getAndAdd(s, 0L, delta);
    }

    // --- [3] 콜백 (Upcall - Java to C Function Pointer) ---

    /** 자바 메서드 핸들을 C 함수 포인터로 변환합니다. */
    public static MemorySegment callback(MethodHandle target, FunctionDescriptor descriptor, Arena arena) {
        return MemoryManager.createCallback(target, descriptor, arena);
    }

    // --- [4] 파일 매핑 및 I/O ---

    public static <T extends Struct> StructArray<T> map(String path, long count, Class<T> type) {
        return MemoryManager.map(Paths.get(path), count, type);
    }

    public static void write(WritableByteChannel channel, Struct struct) throws IOException {
        MemoryManager.write(channel, struct);
    }

    public static void read(ReadableByteChannel channel, Struct struct) throws IOException {
        MemoryManager.read(channel, struct);
    }

    public static void write(WritableByteChannel channel, StructArray<?> array) throws IOException {
        MemoryManager.write(channel, array);
    }

    // --- [5] RAII 및 할당자 슈가링 ---

    public static <T> Pointer<T> auto(Pointer<T> p) { return p.auto(); }
    public static Arena scope() { return Arena.ofConfined(); }
    public static Allocator bump(long size) { return new BumpAllocator(size); }

    // --- [6] 공통 연산 ---

    public static <T> void ptr(Pointer<T> p, T v) { p.set(v); }
    public static <T> T val(Pointer<T> p) { return p.deref(); }

    public static RawBuffer raw(long size) { return MemoryManager.allocateRaw(size); }
    public static RawBuffer raw(long size, Arena a) { return MemoryManager.allocateRaw(size, a); }
    public static RawBuffer raw(long size, Allocator alc) { return MemoryManager.allocateRaw(size, alc); }

    public static <T extends Struct> StructVector<T> vector(Class<T> type) { return MemoryManager.createVector(type, 16); }
    public static <T extends Struct> StructVector<T> vector(Class<T> type, Allocator alc) { return MemoryManager.createVector(type, 16, alc); }
    public static <T extends Struct> StructVector<T> vector(Class<T> type, Arena a) { return MemoryManager.createVector(type, 16, new ArenaAllocator(a)); }

    public static <T> StructVector<T> pvector(Class<T> type) { return MemoryManager.createPrimitiveVector(type, 16); }
    public static <T> StructVector<T> pvector(Class<T> type, Allocator alc) { return MemoryManager.createPrimitiveVector(type, 16, alc); }
    public static <T> StructVector<T> pvector(Class<T> type, Arena a) { return MemoryManager.createPrimitiveVector(type, 16, new ArenaAllocator(a)); }

    public static <K, V> OffHeapHashMap<K, V> hashmap(Class<K> k, Class<V> v) { return MemoryManager.createHashMap(k, v, 16, 32, 32); }
    public static <K, V> OffHeapHashMap<K, V> hashmap(Class<K> k, Class<V> v, Allocator alc) { return MemoryManager.createHashMap(k, v, 16, 32, 32, alc); }
    public static <K, V> OffHeapHashMap<K, V> hashmap(Class<K> k, Class<V> v, Arena a) { return MemoryManager.createHashMap(k, v, 16, 32, 32, new ArenaAllocator(a)); }

    /** 기존 메모리를 구조체 배열 뷰로 래핑 */
    public static <T extends Struct> StructArray<T> array(Class<T> type, int count) {
        return MemoryManager.arrayView(type, count);
    }

    /** SoA(Structure of Arrays) 할당 설탕 */
    public static <T extends Struct> StructArray<T> soa(int count, Class<T> type) {
        return MemoryManager.allocateSoA(type, count);
    }

    /** 자바 힙의 바이트 배열을 복사 없이 오프힙 구조체 뷰로 편입 */
    public static <T extends Struct> T incorporate(byte[] data, Class<T> type) {
        return MemoryManager.incorporate(data, type);
    }

    /** 자바 힙의 롱 배열을 복사 없이 오프힙 구조체 뷰로 편입 (8바이트 정렬 보장) */
    public static <T extends Struct> T incorporate(long[] data, Class<T> type) {
        return MemoryManager.incorporate(data, type);
    }

    // --- [7] 포인터 연산 ---

    public static <T, U> Pointer<U> CAST(Pointer<T> p, Class<U> targetType) { return p.cast(targetType); }
    public static <T> Pointer<T> add(Pointer<T> p, long count) { return p.offset(count); }
    public static <T> Pointer<T> sub(Pointer<T> p, long count) { return p.offset(-count); }
    public static boolean isNull(Pointer<?> p) { return p == null || p.isNull(); }

    /** 포인터를 원시 버퍼로 변환 */
    public static RawBuffer asRaw(Pointer<?> p, long size) { return p.asRaw(size); }

    /** 포인터를 구조체 배열로 변환 */
    public static <U extends Struct> StructArray<U> asArray(Pointer<?> p, Class<U> type, int count) {
        return p.asArray(type, count);
    }

    /** 포인터의 포인터 (Double Pointer) 생성 */
    public static <T> Pointer<Pointer<T>> pptr(Pointer<T> p) {
        return MemoryManager.allocateLong(p.address()).cast(null); // 실제로는 cast 로직 보강 필요
    }

    // --- [8] 네이티브 호출 보조 (Sugar) ---

    /** static 메서드를 MethodHandle로 즉시 변환 */
    public static MethodHandle fn(Class<?> cls, String name, Class<?> ret, Class<?>... args) {
        try {
            return MethodHandles.lookup().findStatic(cls, name, MethodType.methodType(ret, args));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** FunctionDescriptor 생성 단축 */
    public static FunctionDescriptor sig(MemoryLayout ret, MemoryLayout... args) {
        return ret == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(ret, args);
    }

    /** C-스타일 정수 배열 할당 */
    public static MemorySegment ints(Arena a, int... vals) {
        return a.allocateFrom(ValueLayout.JAVA_INT, vals);
    }

    /** C-스타일 롱 배열 할당 */
    public static MemorySegment longs(Arena a, long... vals) {
        return a.allocateFrom(ValueLayout.JAVA_LONG, vals);
    }

    /** C-스타일 더블 배열 할당 */
    public static MemorySegment doubles(Arena a, double... vals) {
        return a.allocateFrom(ValueLayout.JAVA_DOUBLE, vals);
    }
}
