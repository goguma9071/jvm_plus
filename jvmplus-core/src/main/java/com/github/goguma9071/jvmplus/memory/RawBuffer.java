package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * 오프힙 메모리의 특정 영역을 직관적으로 조작하기 위한 버퍼 클래스입니다.
 * FFM API의 복잡함을 숨기고 C++ 스타일의 접근을 제공합니다.
 */
public interface RawBuffer {
    /** 관리하는 실제 메모리 세그먼트를 반환합니다. */
    MemorySegment segment();

    /** 특정 오프셋에 int(4바이트) 값을 씁니다. */
    default void setInt(long offset, int value) {
        segment().set(ValueLayout.JAVA_INT, offset, value);
    }

    /** 특정 오프셋에서 int(4바이트) 값을 읽습니다. */
    default int getInt(long offset) {
        return segment().get(ValueLayout.JAVA_INT, offset);
    }

    /** 특정 오프셋에 long(8바이트) 값을 씁니다. */
    default void setLong(long offset, long value) {
        segment().set(ValueLayout.JAVA_LONG, offset, value);
    }

    /** 특정 오프셋에서 long(8바이트) 값을 읽습니다. */
    default long getLong(long offset) {
        return segment().get(ValueLayout.JAVA_LONG, offset);
    }

    /** 특정 오프셋에 double(8바이트) 값을 씁니다. */
    default void setDouble(long offset, double value) {
        segment().set(ValueLayout.JAVA_DOUBLE, offset, value);
    }

    /** 특정 오프셋에서 double(8바이트) 값을 읽습니다. */
    default double getDouble(long offset) {
        return segment().get(ValueLayout.JAVA_DOUBLE, offset);
    }

    /** 바이트 배열의 데이터를 이 버퍼로 복사합니다. (C의 memcpy 유사) */
    default void copyFrom(byte[] src, int srcPos, long destOffset, int length) {
        MemorySegment.copy(MemorySegment.ofArray(src), srcPos, segment(), destOffset, length);
    }

    /** 이 영역의 모든 데이터를 특정 값으로 채웁니다. (C의 memset 유사) */
    default void fill(byte value) {
        segment().fill(value);
    }

    /** 이 버퍼의 크기를 반환합니다. */
    default long size() {
        return segment().byteSize();
    }
}
