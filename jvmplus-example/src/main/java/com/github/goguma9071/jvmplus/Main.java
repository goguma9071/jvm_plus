package com.github.goguma9071.jvmplus;

import static com.github.goguma9071.jvmplus.JPhelper.*;
import com.github.goguma9071.jvmplus.memory.*;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

public class Main {

    @Struct.Type(defaultLib = "libc.so.6") // [1] 구조체 레벨에서 기본 라이브러리 지정!
    public interface GameObject extends Struct {
        @Struct.Field(order = 1) int id(); GameObject id(int v); // [2] Fluent Setter (반환타입 변경)
        @Struct.UTF8(length = 16) @Struct.Field(order = 2) String name(); GameObject name(String n);
        @Struct.Atomic @Struct.Field(order = 3) int health(); GameObject health(int v);
        
        GameObject addAndGetHealth(int delta); // 원자적 연산도 체이닝 지원
        
        @Struct.NativeCall(name = "printf") // lib 생략 가능!
        int printf(String fmt, String msg);

        @Struct.NativeCall(name = "qsort")
        void qsort(MemorySegment base, long nmemb, long size, MemorySegment compar);
    }

    public static int compareInts(MemorySegment p1, MemorySegment p2) {
        int v1 = p1.reinterpret(4).get(ValueLayout.JAVA_INT, 0);
        int v2 = p2.reinterpret(4).get(ValueLayout.JAVA_INT, 0);
        return Integer.compare(v1, v2);
    }

    public static void main(String[] args) {
        System.out.println("========== JVM PLUS ULTRA MODERN DEMO ==========\n");
        
        // [3] 람다 초기화 + Fluent API의 조화!
        try (GameObject g = alloc(GameObject.class, it -> it
                .id(1)
                .name("ULTRA_HERO")
                .health(100)
                .addAndGetHealth(50))) {
            
            JPdebug.inspect(g);
            System.out.println("Current Health: " + g.health());
        }

        System.out.println("\n[4. Native Call with Sugar]");
        try (Arena a = scope()) {
            MemorySegment array = ints(a, 50, 10, 30, 20, 40); // ints() 설탕
            MethodHandle cmp = fn(Main.class, "compareInts", int.class, MemorySegment.class, MemorySegment.class); // fn() 설탕
            MemorySegment cb = callback(cmp, sig(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), a); // sig() 설탕
            
            alloc(GameObject.class).qsort(array, 5, 4, cb);
            
            System.out.print("Sorted via C qsort: ");
            for(int i=0; i<5; i++) System.out.print(array.getAtIndex(ValueLayout.JAVA_INT, i) + " ");
            System.out.println();
        }

        System.out.println("\n========== ALL SYSTEMS NOMINAL ==========");
    }
}
