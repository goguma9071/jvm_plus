package com.github.goguma9071.jvmplus.memory.pointer;

import com.github.goguma9071.jvmplus.memory.MemoryManager;
import com.github.goguma9071.jvmplus.memory.MemoryPool;

import java.lang.foreign.ValueLayout;

public final class LongPtrImpl extends AbstractTypedPointer<LongPtr> implements LongPtr {

    public LongPtrImpl(long address, MemoryPool pool) {
        super(address, 8, pool);
    }

    //BumAllocator를 이용하여 할당을 할 경우
    public LongPtrImpl(long address) {
        super(address, 8, null);
    }

    // 부모가 offset()을 계산할 때 호출할 팩토리 메서드
    @Override
    protected LongPtrImpl create(long newAddress) {
        return new LongPtrImpl(newAddress); // (또는 Flyweight 패턴 적용)
    }

    // 극한의 속도를 내는 get/set (박싱 없음)
    @Override
    public long get() { return MemoryManager.EVERYTHING.get(ValueLayout.JAVA_LONG, address); }

    @Override
    public void set(long val) { MemoryManager.EVERYTHING.set(ValueLayout.JAVA_LONG, address, val); }


}
