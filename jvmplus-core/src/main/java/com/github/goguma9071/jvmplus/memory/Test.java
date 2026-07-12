package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * BumpAllocator를 사용하여 임시 데이터를 할당하고 관리하는 테스트 코드.
 * Bump Allocator는 스코프(Scope)가 명확한, 짧은 생명주기의 데이터 처리에 최적화되어 있습니다.
 */
/** public class Test implements AutoCloseable {

    // BumpAllocator는 일반적으로 자원을 직접 관리하므로 AutoCloseable을 구현하여 try-with-resources 사용이 권장됩니다.
    private final BumpAllocator alloc;

    /**
     * 생성자에서 메모리 블록을 미리 확보합니다. (1MB 할당 가정)
     */
/**
    public Test() {
        // 1024 * 1024 bytes = 1MB의 임시 버퍼를 할당한다고 가정하고 초기화합니다.
        this.alloc = new BumpAllocator(1024 * 1024);
    }
/**

    public void runTest() {
        System.out.println("--- [Bump Allocator Test Start] ---");

        // 1. 초기 데이터 할당 (예: 16 바이트)
        System.out.print("Phase 1: Initial Allocation...");
        MemorySegment segmentA = alloc.allocate(ValueLayout.JAVA_LONG);
        segmentA.set(ValueLayout.JAVA_LONG, 0, 12345L);
        System.out.println(" Done. Address: " + Long.toHexString(segmentA.address()));

        // 2. 다른 타입의 데이터 할당 (예: String 길이 32 바이트)
        MemorySegment segmentB = alloc.allocate(ValueLayout.JAVA_BYTE * 32);
        System.out.print("Phase 2: Second Allocation...");
        // 가상의 문자열을 메모리에 복사한다고 가정
        segmentB.asSlice(0, 32).set(ValueLayout.JAVA_BYTE, 0, (byte)'H');
        segmentB.asSlice(0, 32).set(ValueLayout.JAVA_BYTE, 1, (byte)'i');
        segmentB.asSlice(0, 32).set(ValueLayout.JAVA_BYTE, 2, (byte)'!');
        System.out.println(" Done. Address: " + Long.toHexString(segmentB.address()));

        // 3. 세 번째 데이터 할당 및 사용
        MemorySegment segmentC = alloc.allocate(ValueLayout.JAVA_DOUBLE);
        segmentC.set(ValueLayout.JAVA_DOUBLE, 0, 3.14159);
        System.out.println("Phase 3: Final Allocation...");

        // ***********************************************
        // Bump Allocator의 핵심 동작: 개별 해제는 불가능하며, 전체를 한 번에 해제해야 합니다.
        // 이 블록을 벗어날 때 (혹은 명시적으로 free() 호출 시) 모든 자원이 정리됩니다.
        // ***********************************************

        System.out.println("\n--- [Bump Allocator Test End] ---");
        System.out.println("모든 할당된 메모리 블록을 한 번에 해제합니다.");
    }

    /**
     * 자원 관리의 안전성을 보장하기 위해 AutoCloseable을 구현하고,
     * finally 블록이나 try-with-resources를 사용하도록 유도하는 것이 좋습니다.

    @Override
    public void close() {
        // 실제 환경에서는 여기서 alloc.free()와 같은 메소드를 호출해야 합니다.
        System.out.println("Test Resources Cleaned Up.");
    }

    /**
     * 예시 실행 코드 (Main 메서드는 실제 테스트 환경에 따라 달라질 수 있습니다.)

    public static void main(String[] args) {
        // try-with-resources를 사용하여 자원 해제 흐름을 명시합니다.
        try (Test test = new Test()) {
            test.runTest();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

/*
 * [주의] 이 코드가 성공적으로 동작하려면, 외부에서 BumpAllocator 클래스와 그 메소드(allocate(), free() 등)를 정의해야 합니다.
 */


