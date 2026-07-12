package com.github.goguma9071.jvmplus.memory.pointer;

// 8바이트 메모리 주소(포인터)를 담고 있는 포인터
public interface AddressPtr<T extends BasePointer> extends TypedPointer<AddressPtr<T>> {
    T get(); // 주소를 읽어 대상 포인터 객체 반환
    void set(T ptr);
}