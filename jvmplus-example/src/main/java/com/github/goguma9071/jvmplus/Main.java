package com.github.goguma9071.jvmplus;

import static com.github.goguma9071.jvmplus.JPhelper.*; // JPhelper 정적 임포트
import com.github.goguma9071.jvmplus.memory.*;

public class Main {

    @Struct.Type
    public interface Player extends Struct {
        @Struct.Field(order = 1)
        int id();
        void id(int v);

        @Struct.Field(order = 2)
        int score();
        void score(int v);
    }

    public static void main(String[] args) {
        System.out.println("=== JVM Plus Syntactic Sugar Showcase ===\n");

        testVariableHelpers();
        testCollectionHelpers();
        testMemoryControlHelpers();

        System.out.println("=== All Sugar Tests Finished ===");
    }

    private static void testVariableHelpers() {
        System.out.println("[1. Variable & Pointer Helpers]");
        
        // 할당: int* hp = new int(100)
        Pointer<Integer> hp = ptr(100);
        System.out.println("Initial hp value: " + val(hp));

        // 대입: *hp = 500
        ptr(hp, 500); 
        System.out.println("After ptr(hp, 500): " + val(hp));

        // Raw 메모리: void* buf = malloc(64)
        RawBuffer buf = raw(64);
        buf.setLong(0, 0x1234567890ABCDEFL);
        System.out.printf("RawBuffer Long: 0x%X\n", buf.getLong(0));
        System.out.println();
    }

    private static void testCollectionHelpers() {
        System.out.println("[2. Collection Helpers]");
        
        // std::vector<Player>
        try (StructVector<Player> players = vector(Player.class)) {
            Player p1 = alloc(Player.class);
            p1.id(1); p1.score(1000);
            players.add(p1);
            
            System.out.println("Vector size: " + players.size());
            System.out.println("Player 0 ID: " + players.get(0).id());
        }

        // std::unordered_map<String, Integer>
        try (OffHeapHashMap<String, Integer> scores = hashmap(String.class, Integer.class)) {
            scores.put("Alice", 95);
            System.out.println("Alice's score: " + scores.get("Alice"));
        }
        System.out.println();
    }

    private static void testMemoryControlHelpers() {
        System.out.println("[3. Memory Management Helpers]");
        
        // 미리 1000개 자리 확보
        prealloc(Player.class, 1000);
        System.out.println("Preallocated 1000 slots for Player.");

        // 풀 비우기
        clear(Player.class);
        System.out.println("Cleared Player pool.");
        System.out.println();
    }
}
