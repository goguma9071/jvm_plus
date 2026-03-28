package com.github.goguma9071.jvmplus;

import com.github.goguma9071.jvmplus.memory.MemoryManager;
import com.github.goguma9071.jvmplus.memory.Pointer;
import com.github.goguma9071.jvmplus.memory.Struct;

public class Main {

    // 1. C 표준 라이브러리와 연동하는 인터페이스
    @Struct.Type
    public interface LibC extends Struct {
        @Struct.NativeCall(name = "printf", lib = "libc.so.6")
        int printf(String format, String message);

        @Struct.NativeCall(name = "strlen", lib = "libc.so.6")
        long strlen(String str);
    }

    public enum Status { ONLINE, OFFLINE }

    @Struct.Type
    public interface User extends Struct {
        @Struct.Field(order = 1)
        int id();
        void id(int v);

        @Struct.Enum(byteSize = 1)
        @Struct.Field(order = 2)
        Status status();
        void status(Status s);
    }

    public static void main(String[] args) {
        System.out.println("=== JPC Final Integration: Native & Low-Level ===\n");

        testNativeCall();
        testEnumAndCast();

        System.out.println("=== Project JPC Successfully Completed ===");
    }

    private static void testNativeCall() {
        System.out.println("[1. Testing Native C Function Calls]");
        try (LibC libc = MemoryManager.allocate(LibC.class)) {
            // C의 printf 직접 호출 (자바에서 C 라이브러리 함수 호출!)
            libc.printf("Hello from C printf! JPC is calling. Message: %s\n", "SUCCESS");
            
            long len = libc.strlen("Counting length in C...");
            System.out.println("C strlen result: " + len);
            System.out.println();
        } catch (Exception e) {
            System.out.println("Native call failed (Check library path): " + e.getMessage());
        }
    }

    private static void testEnumAndCast() {
        System.out.println("[2. Testing Enum and Pointer Cast]");
        try (User u = MemoryManager.allocate(User.class)) {
            u.id(1001);
            u.status(Status.ONLINE);
            
            System.out.println("User ID: " + u.id() + ", Status: " + u.status());
            
            // Pointer Cast 테스트
            Pointer<Integer> idPtr = u.asPointer().cast(Integer.class);
            System.out.println("Reinterpreted ID via Pointer: " + idPtr.deref());
            System.out.println();
        }
    }
}
