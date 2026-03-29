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

    public static <T extends Struct> T alloc(Class<T> type) {
        return MemoryManager.allocate(type);
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

    // --- [2] 특정 아레나 종속 ---

    public static Pointer<Integer> ptr(int v, Arena a) { return MemoryManager.allocateInt(v, a); }
    public static Pointer<Long> ptr(long v, Arena a) { return MemoryManager.allocateLong(v, a); }
    public static Pointer<Double> ptr(double v, Arena a) { return MemoryManager.allocateDouble(v, a); }
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

    // --- [7] 포인터 연산 ---

    public static <T, U> Pointer<U> CAST(Pointer<T> p, Class<U> targetType) { return p.cast(targetType); }
    public static <T> Pointer<T> add(Pointer<T> p, long count) { return p.offset(count); }

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
