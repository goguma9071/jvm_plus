package com.github.goguma9071.jvmplus;

import com.github.goguma9071.jvmplus.memory.MemoryManager;
import com.github.goguma9071.jvmplus.memory.Struct;
import com.github.goguma9071.jvmplus.memory.Pointer;
import com.github.goguma9071.jvmplus.memory.StructArray;

public class Main {
    
    @Struct.Type
    public interface Node extends Struct {
        @Struct.Field(order = 0) int value();
        void value(int v);

        @Struct.Field(order = 1) Pointer<Node> next();
        void next(Pointer<Node> ptr);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== JPC Pointer Arithmetic & offset() Test ===");

        int count = 5;
        // 1. AoS 배열 할당 및 데이터 초기화
        try (StructArray<Node> nodes = MemoryManager.arrayView(Node.class, count)) {
            for (int i = 0; i < count; i++) {
                nodes.get(i).value(i * 100);
            }

            // 2. 포인터 기반 순회 테스트
            System.out.println("\n[Testing Pointer Arithmetic with offset()]");
            
            // 첫 번째 요소의 포인터를 얻어옵니다.
            Node firstNode = nodes.get(0);
            Pointer<Node> basePtr = firstNode.asPointer(); 
            
            System.out.println("Base Address (Node 0): " + basePtr.address());

            for (int i = 0; i < count; i++) {
                // Pointer.offset(i)를 사용하여 i번째 노드의 포인터를 계산합니다.
                // 이는 C++의 (basePtr + i)와 동일한 동작입니다.
                Pointer<Node> movedPtr = basePtr.offset(i); 
                Node derefed = movedPtr.deref();
                
                System.out.println("Offset " + i + " -> Address: " + movedPtr.address() + ", Value: " + derefed.value());
            }

            // 3. 노드 간 연결 테스트 (next 포인터 활용)
            System.out.println("\n[Linking Nodes using Pointers]");
            for (int i = 0; i < count - 1; i++) {
                nodes.get(i).next().set(nodes.get(i+1));
            }

            System.out.print("Linked List Traversal: ");
            Node curr = nodes.get(0);
            while (curr != null) {
                System.out.print(curr.value() + (curr.next().isNull() ? "" : " -> "));
                curr = curr.next().deref();
            }
            System.out.println();
        }

        System.out.println("\n=== Pointer Arithmetic Test Finished ===");
    }
}
