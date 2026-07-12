package com.github.goguma9071.jvmplus.memory.pointer;

import com.github.goguma9071.jvmplus.JPhelper;
import com.github.goguma9071.jvmplus.memory.MemoryManager;

import java.lang.foreign.FunctionDescriptor;

public interface BasePointer extends AutoCloseable {

    long address();

    default boolean isNull() {
        return address() == 0;
    }

    default boolean isSame(BasePointer other) {
        return other != null && address() == other.address();
    }

    // 1. invoke: 주소만 알면 FFM API로 네이티브 함수 호출 가능
    default Object invoke(FunctionDescriptor d, Object... a) {
        return MemoryManager.invoke(address(), d, a);
    }
    default boolean isBefore(BasePointer other) {
        return other != null && address() < other.address();
    }
    default boolean isAfter(BasePointer other) {
        return other != null && address() > other.address();
    }

    default <U extends BasePointer> U cast(Class<U> targetType) {
        return null;
    }

    void free();

    /** @deprecated for help try-with-resources. Use free() instead. */
    @Override
    @Deprecated
    default void close() { free(); }

}
