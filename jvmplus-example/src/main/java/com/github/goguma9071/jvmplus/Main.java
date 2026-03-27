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
        System.out.println("=== JPC Unified API Demo (AoS & SoA) ===");

        int count = 1_000_000;
        System.out.println("Preparing " + count + " players...");

        // 1. AoS (Array of Structs) 할당 - 통합 인터페이스 사용
        StructArray<Player> aosPlayers = MemoryManager.arrayView(Player.class, count);
        for (int i = 0; i < count; i++) aosPlayers.get(i).x(1.23);

        // 2. SoA (Struct of Arrays) 할당 - 통합 인터페이스 사용
        StructArray<Player> soaPlayers = MemoryManager.allocateSoA(Player.class, count);
        for (int i = 0; i < count; i++) soaPlayers.get(i).x(1.23);

        // 3. Warm-up
        System.out.println("Warming up JIT compiler...");
        for (int i = 0; i < 100; i++) {
            aosPlayers.sumDouble("x");
            soaPlayers.sumDouble("x");
        }

        // 4. 성능 측정 (완벽하게 일관된 API 호출)
        System.out.println("\n[Performance Comparison]");
        
        long start = System.nanoTime();
        double sumAos = aosPlayers.sumDouble("x");
        long end = System.nanoTime();
        System.out.println("AoS sumDouble(\"x\"): " + sumAos + " (" + (end - start) / 1_000_000.0 + " ms)");

        start = System.nanoTime();
        double sumSoa = soaPlayers.sumDouble("x");
        end = System.nanoTime();
        System.out.println("SoA sumDouble(\"x\"): " + sumSoa + " (" + (end - start) / 1_000_000.0 + " ms)");

        System.out.println("\n=== Demo Finished (API Asymmetry Resolved) ===");
    }
}
