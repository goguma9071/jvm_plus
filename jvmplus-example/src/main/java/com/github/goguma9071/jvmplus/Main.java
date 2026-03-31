package com.github.goguma9071.jvmplus;

import static com.github.goguma9071.jvmplus.JPhelper.*;
import com.github.goguma9071.jvmplus.memory.*;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

public class Main {

    @Struct.Type(defaultLib = "libc.so.6")
    public interface GameObject extends Struct {
        @Struct.Field(order = 1) int id(); GameObject id(int v);
        @Struct.UTF8(length = 16) @Struct.Field(order = 2) String name(); GameObject name(String n);
        @Struct.Atomic @Struct.Field(order = 2) int health(); GameObject health(int v);
        @Atomic @Struct.Field(order = 3) int score(); GameObject score(byte v);
        
        GameObject addAndGetHealth(int delta);
        
        @Struct.NativeCall(name = "printf")
        int printf(String fmt, String msg);

        @Struct.NativeCall(name = "qsort")
        void qsort(MemorySegment base, long nmemb, long size, MemorySegment compar);
    }

    // [비트 필드 테스트용 구조체]
    @Struct.Type
    public interface BitFieldStruct extends Struct {
        @Struct.BitField(bits = 1) @Struct.Field(order = 1) int flagA(); BitFieldStruct flagA(int v);
        @Struct.BitField(bits = 3) @Struct.Field(order = 2) int flagB(); BitFieldStruct flagB(int v);
        @Struct.BitField(bits = 4) @Struct.Field(order = 3) int flagC(); BitFieldStruct flagC(int v);
        @Struct.Field(order = 4) int normal(); BitFieldStruct normal(int v);
    }

    public static int compareInts(MemorySegment p1, MemorySegment p2) {
        int v1 = p1.reinterpret(4).get(ValueLayout.JAVA_INT, 0);
        int v2 = p2.reinterpret(4).get(ValueLayout.JAVA_INT, 0);
        return Integer.compare(v1, v2);
    }

    public static void main(String[] args) {
        System.out.println("========== JVM PLUS DEMO ==========\n");
        
        System.out.println("[1. Memory Layout & Functional Init]");
        try (GameObject g = alloc(GameObject.class, it -> it
                .id(1)
                .name("ULTRA_HERO")
                .health(100)
                .addAndGetHealth(50))) {
            
            JPdebug.inspect(g);
            System.out.println("Current Health: " + g.health());
        }

        System.out.println("\n[2. Bit-Field Support Test]");
        try (BitFieldStruct b = alloc(BitFieldStruct.class, it -> it
                .flagA(1)
                .flagB(5)
                .flagC(10)
                .normal(12345))) {
            
            JPdebug.inspect(b);
            System.out.println("flagA: " + b.flagA());
            System.out.println("flagB: " + b.flagB());
            System.out.println("flagC: " + b.flagC());
            System.out.println("normal: " + b.normal());
        }

        System.out.println("\n[3. Native Call with Sugar]");
        try (var pp = ptr("나는 빡빡이다", 20);
             var fptr = ptr(3.14f, scope())) {

            System.out.println("String Pointer: " + pp);
            System.out.println("Float Pointer: " + fptr);

            try (Arena a = scope()) {
                MemorySegment array = ints(a, 50, 10, 30, 20, 40);
                MethodHandle cmp = fn(Main.class, "compareInts", int.class, MemorySegment.class, MemorySegment.class);
                MemorySegment cb = callback(cmp, sig(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), a);

                try (var sorter = alloc(GameObject.class)) {
                    sorter.qsort(array, 5, 4, cb);
                }

                System.out.print("Sorted via C qsort: ");
                for(int i=0; i<5; i++) System.out.print(array.getAtIndex(ValueLayout.JAVA_INT, i) + " ");
                System.out.println();
                }
                }

                System.out.println("\n[4. C++ Style Pointer Variables & Double Pointers]");
                try (Var<Integer> a = var(42);
                Var<Pointer<Integer>> p = var(a.asPointer())) {

                Pointer<Pointer<Integer>> pp = p.asPointer(); // int** pp = &p;

                System.out.println("Variable a (int): " + a);
                System.out.println("Pointer p (int*): " + p.get() + " (points to a)");
                System.out.println("Double Pointer pp (int**): " + pp + " (points to p)");

                System.out.println("Dereference once (*pp): " + pp.deref());
                System.out.println("Dereference twice (**pp): " + pp.deref().deref());

                // 포인터를 통한 값 수정 테스트
                pp.deref().set(999);
                System.out.println("Modified a via pp: " + a.get());
                }

                System.out.println("\n========== ALL SYSTEMS NOMINAL ==========");
                }}
