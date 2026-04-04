package com.github.goguma9071.jvmplus;

import static com.github.goguma9071.jvmplus.JPhelper.*;
import com.github.goguma9071.jvmplus.memory.*;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Paths;

public class Main {

    @Struct.Type
    public interface GameObject extends Struct {
        @Struct.Field(order = 1) int id();
        GameObject id(int v);

        @Struct.Atomic @Struct.Field(order = 2) int health();
        GameObject health(int v);

        @Struct.UTF8(length = 16) @Struct.Field(order = 3) String name();
        GameObject name(String n);

        @Struct.Atomic @Struct.Field(order = 4) int score();
        GameObject score(int v);

        @NativeCall(name = "qsort", lib = "libc.so.6")
        void qsort(MemorySegment ptr, long count, long size, MemorySegment cmp);
    }

    public static int compareInts(MemorySegment a, MemorySegment b) {
        return Integer.compare(a.get(ValueLayout.JAVA_INT, 0), b.get(ValueLayout.JAVA_INT, 0));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("========== JVM PLUS ADVANCED BENCHMARK ==========");

        final int COUNT = 1_000_000;
        
        // --- [1] 예열 (Warm-up) ---
        System.out.print("Warming up JIT... ");
        for(int i=0; i<100_000; i++) {
            try (var g = alloc(GameObject.class)) {
                g.health(i).score(i);
            }
        }
        System.out.println("Done.");

        // --- [2] 할당 성능 측정 (Allocation Benchmark) ---
        System.gc(); Thread.sleep(500);
        long startHeap = System.nanoTime();
        class HeapObj { int h; int s; HeapObj(int h, int s){this.h=h; this.s=s;} }
        HeapObj[] heapArray = new HeapObj[COUNT];
        for(int i=0; i<COUNT; i++) heapArray[i] = new HeapObj(100, 0);
        long endHeap = System.nanoTime();
        System.out.printf("Java Heap (1M obj) Allocation: %.2f ms\n", (endHeap - startHeap) / 1_000_000.0);

        System.gc(); Thread.sleep(500);
        long startOff = System.nanoTime();
        try (var offArray = array(GameObject.class, COUNT)) {
            for(int i=0; i<COUNT; i++) {
                GameObject g = offArray.getFlyweight(i);
                g.health(100).score(0);
            }
            long endOff = System.nanoTime();
            System.out.printf("Off-Heap (1M struct) Allocation & Init: %.2f ms\n", (endOff - startOff) / 1_000_000.0);

            // --- [3] 수치 연산 성능 측정 (Computation: AoS vs SoA) ---
            System.out.println("\n[3. Computation Benchmark: Summing Health]");
            
            // 3.1 AoS Sum
            long startAosSum = System.nanoTime();
            long aosTotal = 0;
            for(int i=0; i<COUNT; i++) {
                aosTotal += offArray.getFlyweight(i).health();
            }
            long endAosSum = System.nanoTime();
            System.out.printf("AoS Sum Time: %.2f ms (Total: %d)\n", (endAosSum - startAosSum) / 1_000_000.0, aosTotal);

            // 3.2 SoA Sum (SIMD 최적화 지점)
            try (var soaArray = allocSoA(GameObject.class, COUNT)) {
                for(int i=0; i<COUNT; i++) soaArray.get(i).health(100);
                
                long startSoASum = System.nanoTime();
                // SoA는 필드 데이터가 연속적이므로 sumDouble/sumLong 등 엔진 레벨 최적화 호출 가능
                long soaTotal = soaArray.sumLong("health"); 
                long endSoASum = System.nanoTime();
                System.out.printf("SoA Sum (with Engine optimization) Time: %.2f ms (Total: %d)\n", (endSoASum - startSoASum) / 1_000_000.0, soaTotal);
            }
        }

        // --- [4] mmap (Persistent Memory) Test ---
        System.out.println("\n[4. mmap Test]");
        java.nio.file.Path path = Paths.get("data.bin");
        try (var _s = suppress(); var mapped = map(path.toString(), 100, GameObject.class)) {
            ignore(mapped);
            mapped.get(0).id(123).name("MappedObj");
            System.out.println("Data saved to mmap: " + mapped.get(0).name());
        }
        // 다시 로드하여 확인
        try (var _s = suppress(); var remapped = map(path.toString(), 100, GameObject.class)) {
            ignore(remapped);
            System.out.println("Data reloaded from mmap: " + remapped.get(0).name());
        }
        java.nio.file.Files.deleteIfExists(path);

        // --- [5] Advanced C++ Features ---
        System.out.println("\n[5. Advanced C++ Features]");
        try (var s = string("Dynamic Off-Heap String")) {
            ignore(s);
            System.out.println("String: " + s);
            s.set("Reallocated to a much longer string without issues.");
            System.out.println("String: " + s);
        }

        try (Var<Integer> a = var(10);
             Var<Pointer<Integer>> ptrVar = var(a.asPointer())) {
            ptrVar.get().set(20);
            System.out.println("Value changed via double pointer: " + a.get());
        }

        System.out.println("\n========== ALL SYSTEMS NOMINAL ==========");
    }
}
