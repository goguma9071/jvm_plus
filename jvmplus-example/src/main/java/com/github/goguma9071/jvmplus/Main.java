package com.github.goguma9071.jvmplus;

import static com.github.goguma9071.jvmplus.JPhelper.*;
import com.github.goguma9071.jvmplus.memory.*;
import java.lang.foreign.*;

public class Main {

    @Struct.Type
    public interface GameObject extends Struct {
        @Struct.Field(order = 1) int id();
        GameObject id(int v);
        @Struct.Field(order = 2) int health();
        GameObject health(int v);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("========== JVM PLUS PURE ALLOCATION BENCHMARK ==========");

        final int COUNT = 1_000_000;
        final int LARGE_COUNT = 10_000_000; 
        
        try (var _s = suppress()) {
            
            // --- [1] Pure Bulk Allocation (1M Elements) ---
            System.out.println("[1. Pure Allocation Performance (1M Elements)]");
            
            long startAos = System.nanoTime();
            try (var offArray = array(GameObject.class, COUNT)) {
                long endAos = System.nanoTime();
                System.out.printf("AoS Pure Bulk Allocation: %.4f ms\n", (endAos - startAos) / 1_000_000.0);
            }

            long startSoa = System.nanoTime();
            try (var soaArray = allocSoA(GameObject.class, COUNT)) {
                long endSoa = System.nanoTime();
                System.out.printf("SoA Pure Bulk Allocation: %.4f ms\n", (endSoa - startSoa) / 1_000_000.0);
            }

            // --- [2] Pure Bulk Allocation (10M Elements) ---
            System.out.println("\n[2. Extreme Scale Allocation (10M Elements)]");
            
            long startAosLarge = System.nanoTime();
            try (var offArray = array(GameObject.class, LARGE_COUNT)) {
                long endAosLarge = System.nanoTime();
                System.out.printf("AoS Extreme Allocation: %.4f ms\n", (endAosLarge - startAosLarge) / 1_000_000.0);
            }

            long startSoaLarge = System.nanoTime();
            try (var soaArray = allocSoA(GameObject.class, LARGE_COUNT)) {
                long endSoaLarge = System.nanoTime();
                System.out.printf("SoA Extreme Allocation: %.4f ms\n", (endSoaLarge - startSoaLarge) / 1_000_000.0);
            }
        }

        System.out.println("\nNote: Allocation is near-instant as it only reserves address space.");
        System.out.println("========== ALL SYSTEMS NOMINAL ==========");
    }
}
