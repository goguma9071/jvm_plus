package com.github.goguma9071.jvmplus;

import com.github.goguma9071.jvmplus.memory.MemoryManager;
import com.github.goguma9071.jvmplus.memory.Struct;
import com.github.goguma9071.jvmplus.memory.StructArrayView;

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
        System.out.println("=== JPC AoS vs SoA SIMD Demo ===");

        int count = 1_000_000; // 100만 명의 대규모 데이터
        System.out.println("Preparing " + count + " players...");

        // --- 1. AoS (Array of Structs) 테스트 ---
        StructArrayView<Player> aosPlayers = MemoryManager.arrayView(Player.class, count);
        for (int i = 0; i < count; i++) {
            aosPlayers.get(i).x(1.23);
        }

        System.out.println("\n[AoS Performance]");
        long start = System.nanoTime();
        double sumAosNormal = 0;
        for (Player p : aosPlayers) sumAosNormal += p.x();
        long end = System.nanoTime();
        System.out.println("AoS Normal Loop: " + sumAosNormal + " (" + (end - start) / 1_000_000.0 + " ms)");

        long xOffset = Class.forName("com.github.goguma9071.jvmplus.Main_PlayerImpl").getField("X_OFFSET").getLong(null);
        start = System.nanoTime();
        double sumAosSimd = aosPlayers.sumDoubleField(xOffset);
        end = System.nanoTime();
        System.out.println("AoS SIMD (Gather): " + sumAosSimd + " (" + (end - start) / 1_000_000.0 + " ms)");


        // --- 2. SoA (Struct of Arrays) 테스트 ---
        // PlayerSoAImpl로 캐스팅하여 SoA 전용 메소드(at, sumX) 사용
        // 실제로는 인터페이스 확장을 통해 더 깔끔하게 처리 가능
        Object soaObj = MemoryManager.allocateSoA(Player.class, count);
        com.github.goguma9071.jvmplus.Main_PlayerSoAImpl soaPlayers = (com.github.goguma9071.jvmplus.Main_PlayerSoAImpl) soaObj;
        
        for (int i = 0; i < count; i++) {
            soaPlayers.at(i).x(1.23);
        }

        System.out.println("\n[SoA Performance]");
        start = System.nanoTime();
        double sumSoaNormal = 0;
        for (int i = 0; i < count; i++) sumSoaNormal += soaPlayers.at(i).x();
        end = System.nanoTime();
        System.out.println("SoA Normal Loop: " + sumSoaNormal + " (" + (end - start) / 1_000_000.0 + " ms)");

        start = System.nanoTime();
        double sumSoaSimd = soaPlayers.sumX(); // 컴파일러가 생성한 초고속 SIMD 메소드
        end = System.nanoTime();
        System.out.println("SoA SIMD (Sequential): " + sumSoaSimd + " (" + (end - start) / 1_000_000.0 + " ms)");

        System.out.println("\n=== Demo Finished ===");
    }
}
