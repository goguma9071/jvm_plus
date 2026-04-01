package com.github.goguma9071.jvmplus.memory;

/**
 * StructVector의 상태 정보를 오프힙에 저장하기 위한 헤더 구조체입니다.
 */
@Struct.Type
public interface VectorHeader extends Struct {
    @Struct.Field(order = 1) int size();
    VectorHeader size(int s);

    @Struct.Field(order = 2) int capacity();
    VectorHeader capacity(int c);

    @Struct.Field(order = 3) long elementSize();
    VectorHeader elementSize(long sz);

    @Struct.Field(order = 4) long alignment();
    VectorHeader alignment(long al);
}
