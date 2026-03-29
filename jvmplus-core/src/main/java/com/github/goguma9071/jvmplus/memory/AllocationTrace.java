package com.github.goguma9071.jvmplus.memory;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 메모리가 할당된 시점의 위치 정보를 기록합니다.
 */
public record AllocationTrace(
    long address,
    long size,
    String stackTrace
) {
    public static AllocationTrace capture(long address, long size) {
        String trace = Arrays.stream(Thread.currentThread().getStackTrace())
            .skip(2) // capture()와 호출 메서드 제외
            .filter(e -> !e.getClassName().contains("MemoryManager") && !e.getClassName().contains("JPhelper"))
            .limit(5) // 핵심 호출지 5줄만 기록
            .map(StackTraceElement::toString)
            .collect(Collectors.joining("\n    at "));
        
        return new AllocationTrace(address, size, trace);
    }

    @Override
    public String toString() {
        return String.format("Addr: 0x%X, Size: %d bytes\n    at %s", address, size, stackTrace);
    }
}
