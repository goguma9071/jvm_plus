package com.github.goguma9071.jvmplus;

import com.github.goguma9071.jvmplus.memory.MemoryManager;
import com.github.goguma9071.jvmplus.memory.Struct;
import com.github.goguma9071.jvmplus.memory.StructArray;

public class Main {
    
    @Struct.Type
    public interface Player extends Struct {
        @Struct.Field(order = 0) int hp();
        void hp(int v);

        @Struct.Field(order = 1) double x();
        void x(double v);

        @Struct.Field(order = 2) @Struct.UTF8(length = 16) String name();
        void name(String v);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== JPC Memory Safety & Unified API Demo ===");

        int count = 1_000_000;
        
        // 1. Try-with-resources를 통한 자동 메모리 소멸 시연 (AoS)
        System.out.println("Allocating AoS array with automatic destruction...");
        try (StructArray<Player> aosPlayers = MemoryManager.arrayView(Player.class, count)) {
            for (int i = 0; i < 10; i++) aosPlayers.get(i).x(1.23);
            System.out.println("AoS Sample Data: " + aosPlayers.get(0).x());
        } // 여기서 aosPlayers와 내부 메모리가 자동으로 해제됨
        System.out.println("AoS array destroyed.");

        // 2. Try-with-resources를 통한 자동 메모리 소멸 시연 (SoA)
        System.out.println("\nAllocating SoA array with automatic destruction...");
        try (StructArray<Player> soaPlayers = MemoryManager.allocateSoA(Player.class, count)) {
            for (int i = 0; i < count; i++) soaPlayers.get(i).x(1.23);
            
            // Warm-up & SIMD 연산
            System.out.println("Warming up SIMD...");
            for (int i = 0; i < 100; i++) soaPlayers.sumDouble("x");
            
            long start = System.nanoTime();
            double sum = soaPlayers.sumDouble("x");
            long end = System.nanoTime();
            
            System.out.println("SoA SIMD sumDouble(\"x\"): " + sum + " (" + (end - start) / 1_000_000.0 + " ms)");
        } // 여기서 soaPlayers와 내부 메모리가 자동으로 해제됨
        System.out.println("SoA array destroyed.");

        // 3. 해제 후 접근 테스트 (안정성 확인)
        System.out.println("\nTesting Use-after-free safety...");
        StructArray<Player> testArray = MemoryManager.allocateSoA(Player.class, 10);
        testArray.close();
        try {
            testArray.get(0).x(10.0);
        } catch (IllegalStateException e) {
            System.out.println("Caught expected safety exception: " + e.getMessage());
        }

        System.out.println("\n=== Demo Finished (Full Memory Safety Achieved) ===");
    }
}
