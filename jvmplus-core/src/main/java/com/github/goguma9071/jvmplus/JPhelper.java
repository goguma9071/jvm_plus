package com.github.goguma9071.jvmplus;

import com.github.goguma9071.jvmplus.memory.*;
import java.lang.foreign.Arena;
import java.nio.file.Paths;

/**
 * Jvm Plus의 모든 기능을 짧고 직관적으로 사용하기 위한 문법적 설탕(Syntactic Sugar) 클래스입니다.
 */
public final class JPhelper {
    private JPhelper() {}

    // --- [1] 기본 할당 (Manual/RAII - GC 독립적) ---

    public static Pointer<Integer> ptr(int v) { return MemoryManager.allocateInt(v); }
    public static Pointer<Long> ptr(long v) { return MemoryManager.allocateLong(v); }
    public static Pointer<Double> ptr(double v) { return MemoryManager.allocateDouble(v); }
    public static Pointer<String> ptr(String v, int max) { return MemoryManager.allocateString(max, v); }

    public static <T extends Struct> T alloc(Class<T> type) {
        return MemoryManager.allocate(type);
    }

    // --- [2] 특정 아레나 종속 (Arena Scoped) ---

    public static Pointer<Integer> ptr(int v, Arena a) { return MemoryManager.allocateInt(v, a); }
    public static Pointer<Long> ptr(long v, Arena a) { return MemoryManager.allocateLong(v, a); }
    public static Pointer<Double> ptr(double v, Arena a) { return MemoryManager.allocateDouble(v, a); }
    public static Pointer<String> ptr(String v, int max, Arena a) { return MemoryManager.allocateString(max, v, a); }

    public static <T extends Struct> T alloc(Class<T> type, Arena arena) {
        return MemoryManager.allocate(type, arena);
    }

    // --- [3] 파일 매핑 (mmap - Persistence) ---

    /** 파일을 메모리에 매핑하여 구조체 배열로 반환합니다. */
    public static <T extends Struct> StructArray<T> map(String path, long count, Class<T> type) {
        return MemoryManager.map(Paths.get(path), count, type);
    }

    // --- [4] GC 관리 모드로 전환 (.auto) ---

    public static <T> Pointer<T> auto(Pointer<T> p) { return p.auto(); }

    /** 아레나 슈가링 */
    public static Arena scope() { return Arena.ofConfined(); }
    public static Allocator bump(long size) { return new BumpAllocator(size); }

    // --- 공통 연산 ---

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

    public static <T, U> Pointer<U> CAST(Pointer<T> p, Class<U> targetType) { return p.cast(targetType); }
    public static <T> Pointer<T> add(Pointer<T> p, long count) { return p.offset(count); }
}
