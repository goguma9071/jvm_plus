package com.github.goguma9071.jvmplus.memory.pointer;

import com.github.goguma9071.jvmplus.memory.MemoryManager;
import com.github.goguma9071.jvmplus.memory.MemoryPool;

import java.lang.foreign.ValueLayout;


public final class IntPtrImpl extends AbstractTypedPointer<IntPtr> implements IntPtr {

    // int의 크기인 4를 부모에게 전달
    public IntPtrImpl(long address, MemoryPool pool) {
        super(address, 4, pool);
    }

    //BumAllocator를 이용하여 할당을 할 경우
    public IntPtrImpl(long address) {
        super(address, 4, null);
    }

    // 부모가 offset()을 계산할 때 호출할 팩토리 메서드
    @Override
    protected IntPtrImpl create(long newAddress) {
        return new IntPtrImpl(newAddress); // (또는 Flyweight 패턴 적용)
    }

    // 극한의 속도를 내는 get/set (박싱 없음)
    @Override
    public int get() { return MemoryManager.EVERYTHING.get(ValueLayout.JAVA_INT, address); }

    @Override
    public void set(int val) { MemoryManager.EVERYTHING.set(ValueLayout.JAVA_INT, address, val); }

}
