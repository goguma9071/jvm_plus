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

    public static void main(String[] args) throws Exception {
        System.out.println("========== JVM PLUS ADVANCED BENCHMARK ==========");

        final int COUNT = 1_000_000;
        
        // --- [2] AoS vs SoA Sum Benchmark ---
        System.out.println("\n[2. Computation Benchmark: 10M Elements (Beyond Cache)]");
        final int LARGE_COUNT = 10_000_000; 
        
        try (var _s = suppress(); 
             var offArray = array(GameObject.class, LARGE_COUNT);
             var soaArray = allocSoA(GameObject.class, LARGE_COUNT)) {
            
            System.out.print("Initializing 10M elements with variable values... ");
            for(int i=0; i<LARGE_COUNT; i++) {
                offArray.getFlyweight(i).health(i % 100);
                soaArray.get(i).health(i % 100);
            }
            System.out.println("Done.");

            // 2.1 AoS Sum
            long startAosSum = System.nanoTime();
            long aosTotal = 0;
            for(int i=0; i<LARGE_COUNT; i++) {
                aosTotal += offArray.getFlyweight(i).health();
            }
            long endAosSum = System.nanoTime();
            System.out.printf("AoS Sum Time: %.2f ms (Total: %d)\n", (endAosSum - startAosSum) / 1_000_000.0, aosTotal);

            // 2.2 SoA SIMD Sum
            System.out.print("Intensive JIT Warming up... ");
            for(int i=0; i<1000; i++) soaArray.sumLong("health");
            System.out.println("Done.");

            long startSoASum = System.nanoTime();
            long soaTotal = soaArray.sumLong("health"); 
            long endSoASum = System.nanoTime();
            System.out.printf("SoA SIMD Sum Time: %.2f ms (Total: %d)\n", (endSoASum - startSoASum) / 1_000_000.0, soaTotal);
            
            if (aosTotal != soaTotal) throw new RuntimeException("Validation Failed!");
        }

        // --- [3] 기능 테스트 (추적 활성화 상태) ---
        System.out.println("\n[3. Feature Test]");
        try (OffHeapString s = allocateDynamicString("JVM Plus")) {
            System.out.println("Dynamic String: " + s);
        }

        System.out.println("\n========== ALL SYSTEMS NOMINAL ==========");
    }
}
