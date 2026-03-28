package com.github.goguma9071.jvmplus;

import com.github.goguma9071.jvmplus.memory.*;
import java.lang.foreign.Arena;

/**
 * Jvm Plus의 모든 기능을 짧고 직관적으로 사용하기 위한 문법적 설탕(Syntactic Sugar) 클래스입니다.
 * static import 하여 사용하기를 권장합니다.
 */
public final class JPhelper {
    private JPhelper() {}

    // --- 포인터 및 변수 할당 (new / &) ---

    /** int* p = new int(v) */
    public static Pointer<Integer> ptr(int v) {
        return MemoryManager.allocateInt(v);
    }

    /** long* p = new long(v) */
    public static Pointer<Long> ptr(long v) {
        return MemoryManager.allocateLong(v);
    }

    /** double* p = new double(v) */
    public static Pointer<Double> ptr(double v) {
        return MemoryManager.allocateDouble(v);
    }

    /** char* p = new char[maxLen]; strcpy(p, v); */
    public static Pointer<String> ptr(String v, int maxLen) {
        return MemoryManager.allocateString(maxLen, v);
    }

    /** *p = v (포인터 대입 헬퍼) */
    public static <T> void ptr(Pointer<T> p, T v) {
        p.set(v);
    }

    /** *p (포인터 역참조 헬퍼) */
    public static <T> T val(Pointer<T> p) {
        return p.deref();
    }

    // --- 구조체 및 Raw 메모리 할당 ---

    /** Type* p = new Type() */
    public static <T extends Struct> T alloc(Class<T> type) {
        return MemoryManager.allocate(type);
    }

    /** Type* p = new (arena) Type() (스코프 할당) */
    public static <T extends Struct> T alloc(Class<T> type, Arena arena) {
        return MemoryManager.allocate(type, arena);
    }

    /** void* p = malloc(size) */
    public static RawBuffer raw(long size) {
        return MemoryManager.allocateRaw(size);
    }

    // --- 컬렉션 (std::vector, std::unordered_map) ---

    /** std::vector<T> (구조체용) */
    public static <T extends Struct> StructVector<T> vector(Class<T> type) {
        return MemoryManager.createVector(type, 16);
    }

    /** std::vector<Primitive> (기본 타입용) */
    public static <T> StructVector<T> pvector(Class<T> type) {
        return MemoryManager.createPrimitiveVector(type, 16);
    }

    /** std::unordered_map<K, V> */
    public static <K, V> OffHeapHashMap<K, V> hashmap(Class<K> k, Class<V> v) {
        return MemoryManager.createHashMap(k, v, 16, 32, 32);
    }

    // --- 포인터 연산 및 형변환 ---

    /** reinterpret_cast<U*>(p) */
    public static <T, U> Pointer<U> CAST(Pointer<T> p, Class<U> targetType) {
        return p.cast(targetType);
    }

    /** ptr + count */
    public static <T> Pointer<T> add(Pointer<T> p, long count) {
        return p.offset(count);
    }

    /** ptr1 - ptr2 (거리 계산) */
    public static <T> long diff(Pointer<T> p1, Pointer<T> p2) {
        return p1.distanceTo(p2);
    }

    // --- 메모리 풀 제어 (관리자 도구) ---

    /** 특정 클래스의 메모리를 미리 확보 */
    public static <T extends Struct> void prealloc(Class<T> type, int count) {
        MemoryManager.getPool(type).preallocate(count);
    }

    /** 특정 클래스의 모든 메모리 풀 초기화 */
    public static <T extends Struct> void clear(Class<T> type) {
        MemoryManager.getPool(type).clear();
    }
}
