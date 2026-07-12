package com.github.goguma9071.jvmplus.memory.pointer;


public interface TypedPointer<P extends TypedPointer<P>> extends BasePointer {

    P offset(long count);
    long distanceTo(P other);

    // 3. auto(): 기존 메모리를 Arena.ofAuto()로 복사하고 원본 해제
    P auto();
}
