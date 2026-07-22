package com.github.goguma9071.jvmplus.memory.pointer;

import com.github.goguma9071.jvmplus.memory.MemoryManager;
import java.lang.foreign.ValueLayout;

public final class IntPtrImpl extends AbstractTypedPointer<IntPtr> implements IntPtr {

    // 객체 필드는 오직 address 하나뿐입니다.
    // pool 참조조차 제거했습니다! (해제 로직이 바뀌었기 때문)
    public IntPtrImpl(long address) {
        super(address, 4, null); // 4 = int byte size
    }

    // 1. 값 쓰기 (EVERYTHING 세그먼트를 사용한 최고속 접근)
    @Override
    public int get() {
        return MemoryManager.EVERYTHING.get(ValueLayout.JAVA_INT, address);
    }

    @Override
    public void set(int val) {
        MemoryManager.EVERYTHING.set(ValueLayout.JAVA_INT, address, val);
    }

    // 2. 포인터 연산 팩토리
    @Override
    protected IntPtr create(long newAddress) {
        return new IntPtrImpl(newAddress);
    }

    // 3. 해제 로직 (스레드 충돌 방지 최적화 적용)
    @Override
    public void free() {
        // 현재 이 코드를 실행하는 스레드의 주머니(Local Cache)에 반납합니다.
        // 스레드 교차 반납(Cross-thread free) 문제 완벽 해결!
        MemoryManager.freeIntAddress(this.address);
    }
}
