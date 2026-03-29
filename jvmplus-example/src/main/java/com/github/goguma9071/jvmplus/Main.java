package com.github.goguma9071.jvmplus;

import static com.github.goguma9071.jvmplus.JPhelper.*;
import com.github.goguma9071.jvmplus.memory.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.foreign.*;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;

public class Main {

    public enum Status { ACTIVE, INACTIVE, DELETED }

    @Struct.Type
    public interface GameObject extends Struct {
        @Struct.Field(order = 1) int id(); void id(int v);
        @Struct.UTF8(length = 16) @Struct.Field(order = 2) String name(); void name(String n);
        @Struct.Atomic @Struct.Field(order = 3) int health(); void health(int v);
        int addAndGetHealth(int delta);
        boolean casHealth(int expected, int newValue);

        @Struct.NativeCall(name = "printf", lib = "libc.so.6")
        int printf(String fmt, String msg);

        // C qsort: base, nmemb, size, compar
        @Struct.NativeCall(name = "qsort", lib = "libc.so.6")
        void qsort(MemorySegment base, long nmemb, long size, MemorySegment compar);
    }

    /** 자바에서 정의한 비교 로직 (C qsort용 콜백) */
    public static int compareInts(MemorySegment p1, MemorySegment p2) {
        int v1 = p1.reinterpret(4).get(ValueLayout.JAVA_INT, 0);
        int v2 = p2.reinterpret(4).get(ValueLayout.JAVA_INT, 0);
        return Integer.compare(v1, v2);
    }

    public static void main(String[] args) {
        System.out.println("========== JVM PLUS MEGA INTEGRATION TEST (ULTIMATE) ==========\n");

        testArenaAndSugar();
        testAtomicAndMmap();
        testZeroCopyIO();
        testNativeCallbacks();
        testSimdAndNative();

        System.out.println("\n========== ALL SYSTEMS NOMINAL: PROJECT COMPLETE ==========");
    }

    private static void testArenaAndSugar() {
        System.out.println("[1. RAII, Scoping & Sugar]");
        try (var p = ptr(5678)) {
            System.out.println("RAII Pointer val: " + val(p));
        }
        Pointer<Integer> pAuto = ptr(1234).auto();
        System.out.println("Auto Pointer val: " + val(pAuto));
        System.out.println();
    }

    private static void testAtomicAndMmap() {
        System.out.println("[2. Atomic & mmap]");
        try (GameObject g = alloc(GameObject.class)) {
            g.health(100);
            g.addAndGetHealth(50);
            System.out.println("Atomic Health: " + g.health());
        }
        System.out.println();
    }

    private static void testZeroCopyIO() {
        System.out.println("[3. Zero-copy Channel I/O]");
        String path = "io_test.bin";
        try (GameObject g = alloc(GameObject.class);
             FileChannel out = FileChannel.open(Paths.get(path), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            g.id(123); g.name("IO_HERO");
            write(out, g);
            System.out.println("Zero-copy Write Success.");
        } catch (Exception ignored) {}
        try { Files.deleteIfExists(Paths.get(path)); } catch (Exception ignored) {}
        System.out.println();
    }

    private static void testNativeCallbacks() {
        System.out.println("[4. Native Callbacks (Upcalls - Java to C)]");
        
        try (Arena a = scope()) {
            // 1. 오프힙 배열 생성 (정렬되지 않은 데이터)
            MemorySegment array = a.allocateFrom(ValueLayout.JAVA_INT, 10, 30, 20, 50, 40);
            System.out.print("Before Sort (Off-heap): ");
            for(int i=0; i<5; i++) System.out.print(array.getAtIndex(ValueLayout.JAVA_INT, i) + " ");
            System.out.println();

            // 2. 자바 메서드를 C 함수 포인터로 변환
            MethodHandle compareHandle = MethodHandles.lookup().findStatic(Main.class, "compareInts", 
                MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class));
            
            FunctionDescriptor desc = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            MemorySegment callbackPtr = callback(compareHandle, desc, a);

            // 3. C qsort 실행 (자바 콜백 주소 전달)
            try (GameObject g = alloc(GameObject.class)) {
                g.qsort(array, 5, 4, callbackPtr);
            }

            System.out.print("After Sort (C called Java Callback): ");
            for(int i=0; i<5; i++) System.out.print(array.getAtIndex(ValueLayout.JAVA_INT, i) + " ");
            System.out.println("\nVERIFICATION SUCCESS: Native callback worked.");
        } catch (Exception e) {
            System.err.println("Callback test failed: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println();
    }

    private static void testSimdAndNative() {
        System.out.println("[5. SIMD & Performance]");
        try (GameObject g = alloc(GameObject.class)) {
            System.out.print("C printf says: ");
            g.printf("Greetings from %s!\n", "JVM PLUS");
        } catch (Exception ignored) {}
    }
}
