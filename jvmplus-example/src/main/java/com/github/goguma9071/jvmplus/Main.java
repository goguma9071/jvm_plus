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

        // 1. 모든 Node 인스턴스가 공유하는 정적 필드 (C++의 static 멤버)
        @Struct.Static
        @Struct.Field(order = 3)
        int globalCounter();
        void globalCounter(int v);
    }

    public static void main(String[] args) {
        System.out.println("=== JPC Class-Level & Primitive Variable Test ===\n");

        testStaticFields();
        testPoolControl();
        testScopedAllocation();
        testPrimitiveVariables();
        testStructVector();

        System.out.println("=== All Tests Finished ===");
    }

    private static void testStructVector() {
        System.out.println("[6. Testing StructVector (Dynamic Array)]");
        // 초기 용량 2로 생성
        try (com.github.goguma9071.jvmplus.memory.StructVector<Node> vector = MemoryManager.createVector(Node.class, 2)) {
            System.out.println("Initial Capacity: " + vector.capacity());

            // 1. 데이터 추가
            Node temp = MemoryManager.allocate(Node.class);
            
            temp.value(10);
            vector.add(temp);
            
            temp.value(20);
            vector.add(temp);
            
            System.out.println("Size after 2 adds: " + vector.size());

            // 2. 용량 초과 추가 (자동 확장 트리거)
            temp.value(30);
            vector.add(temp);
            
            System.out.println("Size after 3rd add: " + vector.size());
            System.out.println("Capacity after expansion: " + vector.capacity());

            // 3. 데이터 검증 (Iterable 테스트)
            System.out.print("Vector Contents: ");
            for (Node n : vector) {
                System.out.print(n.value() + " ");
            }
            System.out.println("\n");
            
            temp.close();
        }
    }

    private static void testStaticFields() {
        System.out.println("[1. Testing Static Fields (@Struct.Static)]");
        try (Node n1 = MemoryManager.allocate(Node.class);
             Node n2 = MemoryManager.allocate(Node.class)) {
            
            n1.globalCounter(100);
            System.out.println("n1.globalCounter: " + n1.globalCounter());
            System.out.println("n2.globalCounter (should be 100): " + n2.globalCounter());
            
            n2.globalCounter(500);
            System.out.println("n1.globalCounter (should be 500): " + n1.globalCounter());
            System.out.println();
        }
    }

    private static void testPoolControl() {
        System.out.println("[2. Testing Explicit Pool Control]");
        MemoryPool pool = MemoryManager.getPool(Node.class);
        
        System.out.println("Preallocating 5 slots...");
        pool.preallocate(5); // 내부적으로 범프 포인터를 미리 이동
        
        try (Node n = MemoryManager.allocate(Node.class)) {
            System.out.println("First node address after preallocate: " + n.address());
            
            System.out.println("Clearing the pool...");
            pool.clear(); // 모든 인스턴스 무효화 및 초기화
            
            try (Node n2 = MemoryManager.allocate(Node.class)) {
                System.out.println("Node address after clear (should be reset): " + n2.address());
            }
        }
        System.out.println();
    }

    private static void testScopedAllocation() {
        System.out.println("[3. Testing Scoped Allocation (Region-based)]");
        long addr;
        try (Arena stageArena = Arena.ofConfined()) {
            Node n = MemoryManager.allocate(Node.class, stageArena);
            n.value(777);
            addr = n.address();
            System.out.println("Scoped Node Value: " + n.value() + " at " + addr);
            // stageArena가 닫히면 n의 메모리도 즉시 해제됨 (Pool에 반환되지 않음)
        }
        System.out.println("Stage Arena closed. Memory at " + addr + " is now logically free.");
        System.out.println();
    }

    private static void testPrimitiveVariables() {
        System.out.println("[4. Testing Single Primitive Variables]");
        
        Pointer<Integer> intPtr = MemoryManager.allocateInt(10);
        System.out.println("Initial Int Value: " + intPtr.deref());
        
        intPtr.set(999);
        System.out.println("Modified Int Value: " + intPtr.deref());
        
        Pointer<Double> doublePtr = MemoryManager.allocateDouble(3.141592);
        System.out.println("Initial Double Value: " + doublePtr.deref());
        
        System.out.println("Int Pointer Address: " + intPtr.address());
        System.out.println();

        // 5. 단일 String 변수 테스트
        System.out.println("[5. Testing Single String Variables]");
        Pointer<String> strPtr = MemoryManager.allocateString(32, "Hello Off-Heap!");
        System.out.println("Initial String: " + strPtr.deref());

        strPtr.set("JPC is Awesome!");
        System.out.println("Modified String: " + strPtr.deref());
        System.out.println("String Pointer Address: " + strPtr.address());
        System.out.println();
    }
}
