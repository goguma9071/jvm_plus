package com.github.goguma9071.jvmplus;

import com.github.goguma9071.jvmplus.memory.MemoryManager;
import com.github.goguma9071.jvmplus.memory.MemoryPool;
import com.github.goguma9071.jvmplus.memory.Pointer;
import com.github.goguma9071.jvmplus.memory.Struct;
import java.lang.foreign.Arena;

public class Main {

    @Struct.Type
    public interface Node extends Struct {
        @Struct.Field(order = 1)
        int value();
        void value(int v);

        @Struct.Field(order = 2)
        Pointer<Node> next();
        void next(Pointer<Node> n);

        @Struct.Static
        @Struct.Field(order = 3)
        int globalCounter();
        void globalCounter(int v);
    }

    public static void main(String[] args) {
        System.out.println("=== JPC Advanced Collections Test ===\n");

        testStructVectorAdvanced();
        testOffHeapHashMap();

        System.out.println("=== All Tests Finished ===");
    }

    private static void testStructVectorAdvanced() {
        System.out.println("[1. Testing StructVector Advanced (Remove & Sort)]");
        try (com.github.goguma9071.jvmplus.memory.StructVector<Integer> vec = MemoryManager.createPrimitiveVector(Integer.class, 10)) {
            vec.add(50);
            vec.add(10);
            vec.add(30);
            vec.add(20);
            vec.add(40);

            System.out.print("Initial: ");
            for (int v : vec) System.out.print(v + " ");
            System.out.println();

            // 1. 특정 인덱스 삭제 (인덱스 2의 '30' 삭제)
            vec.remove(2);
            System.out.print("After remove(2): ");
            for (int v : vec) System.out.print(v + " ");
            System.out.println();

            // 2. 오프힙 정렬
            vec.sort(Integer::compareTo);
            System.out.print("After sort: ");
            for (int v : vec) System.out.print(v + " ");
            System.out.println("\n");
        }
    }

    private static void testOffHeapHashMap() {
        System.out.println("[2. Testing OffHeapHashMap]");
        // Key: String(16 bytes), Value: Integer
        try (com.github.goguma9071.jvmplus.memory.OffHeapHashMap<String, Integer> map = 
                 MemoryManager.createHashMap(String.class, Integer.class, 10, 16, 0)) {
            
            map.put("Alice", 100);
            map.put("Bob", 200);
            map.put("Charlie", 300);

            System.out.println("Alice's Score: " + map.get("Alice"));
            System.out.println("Bob's Score: " + map.get("Bob"));
            
            map.put("Alice", 555); // 업데이트
            System.out.println("Alice's New Score: " + map.get("Alice"));

            map.remove("Bob");
            System.out.println("Bob's Score after remove: " + map.get("Bob"));
            System.out.println("Map size: " + map.size());
            System.out.println();
        }
    }
}
