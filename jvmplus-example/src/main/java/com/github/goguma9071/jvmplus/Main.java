package com.github.goguma9071.jvmplus;

import static com.github.goguma9071.jvmplus.JPhelper.*;
import com.github.goguma9071.jvmplus.memory.*;
import java.util.Date;

public class Main {

    @Struct.Type
    public interface GameObject extends Struct {
        @Struct.Field(order = 1) int id();
        GameObject id(int v);

        @Struct.Field(order = 2) int health();
        GameObject health(int v);

        // [신규] 모든 자바 객체를 저장할 수 있는 핸들 필드
        @Struct.Field(order = 3) Object metadata();
        GameObject metadata(Object obj);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("========== JVM PLUS UNIVERSAL BRIDGE TEST ==========");

        try (var _s = suppress()) {
            
            // --- [1] Object Handle Test ---
            System.out.println("[1. Object Handle Test]");
            var g1 = alloc(GameObject.class);
            
            // 일반 자바 객체(Date)를 오프힙 구조체에 저장
            Date now = new Date();
            g1.metadata(now);
            System.out.println("Stored Java Object (Date): " + now);

            // 다시 꺼내기
            Object retrieved = g1.metadata();
            System.out.println("Retrieved Object Type: " + retrieved.getClass().getName());
            System.out.println("Retrieved Object Value: " + retrieved);
            
            if (now == retrieved) {
                System.out.println("-> Success: Reference integrity maintained.");
            }

            // --- [2] Heap-to-OffHeap Bridge Test (Incorporate) ---
            System.out.println("\n[2. Heap-to-OffHeap Bridge Test]");
            
            // 힙에 있는 일반 바이트 배열
            byte[] heapArray = new byte[100]; 
            
            // 이 힙 데이터를 오프힙 구조체 뷰로 "편입" (Zero-copy)
            GameObject bridged = incorporate(heapArray, GameObject.class);
            
            // 구조체를 통해 값을 쓰면 힙 배열의 바이트가 직접 바뀜
            bridged.id(777).health(100);
            
            System.out.println("Value set via Struct View: ID=" + bridged.id() + ", HP=" + bridged.health());
            
            // 힙 배열을 직접 확인 (첫 4바이트가 ID 777의 바이트여야 함)
            int idFromHeap = ((heapArray[3] & 0xFF) << 24) | ((heapArray[2] & 0xFF) << 16) | 
                             ((heapArray[1] & 0xFF) << 8) | (heapArray[0] & 0xFF);
            System.out.println("Raw Value from Heap Array[0-3]: " + idFromHeap);

            if (idFromHeap == 777) {
                System.out.println("-> Success: Zero-copy bridge verified.");
            }
        }

        System.out.println("\n========== ALL SYSTEMS NOMINAL ==========");
    }
}
