package com.github.goguma9071.jvmplus.memory;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 메모리가 할당된 시점의 위치 정보를 오프힙에 기록하는 구조체입니다.
 */
@Struct.Type
public interface AllocationTrace extends Struct {
    @Struct.Field(order = 1) long address();
    AllocationTrace address(long addr);

    @Struct.Field(order = 2) long size();
    AllocationTrace size(long sz);

    @Struct.UTF8(length = 256)
    @Struct.Field(order = 3) String stackTrace();
    AllocationTrace stackTrace(String trace);

    /** 현재 호출 스택을 캡처하여 구조체에 기록합니다. */
    default void capture(long addr, long sz) {
        address(addr);
        size(sz);
        String trace = Arrays.stream(Thread.currentThread().getStackTrace())
            .skip(2) 
            .filter(e -> !e.getClassName().contains("MemoryManager") && 
                         !e.getClassName().contains("JPhelper") &&
                         !e.getClassName().contains("Impl"))
            .limit(3) 
            .map(StackTraceElement::toString)
            .collect(Collectors.joining("\n    at "));
        stackTrace(trace);
    }

    /** 리포트 출력을 위한 헬퍼 */
    default String format() {
        return String.format("Addr: 0x%X, Size: %d bytes\n    at %s", address(), size(), stackTrace());
    }
}
