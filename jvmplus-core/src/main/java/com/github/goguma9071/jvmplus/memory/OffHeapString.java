package com.github.goguma9071.jvmplus.memory;

/**
 * C++의 std::string과 유사하게 동작하는 오프힙 가변 길이 문자열 구조체입니다.
 */
@Struct.Type
public interface OffHeapString extends Struct {
    /** 문자열 데이터가 저장된 오프힙 주소 */
    @Struct.Field(order = 1) Pointer<Byte> data();
    OffHeapString data(Pointer<Byte> p);

    /** 현재 문자열의 길이 (바이트 단위) */
    @Struct.Field(order = 2) long length();
    OffHeapString length(long len);

    /** 할당된 메모리의 총 용량 */
    @Struct.Field(order = 3) long capacity();
    OffHeapString capacity(long cap);

    /** 자바 문자열을 이 오프힙 공간에 복사합니다. 공간 부족 시 자동 확장됩니다. */
    void set(String value);

    /** 현재 저장된 내용을 자바 문자열로 반환합니다. */
    String toString();
}
