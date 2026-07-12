package com.github.goguma9071.jvmplus.memory.pointer;

import com.github.goguma9071.jvmplus.JPhelper;
import com.github.goguma9071.jvmplus.memory.MemoryManager;
import com.github.goguma9071.jvmplus.memory.MemoryPool;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public final class AddressPtrImpl<T extends BasePointer> extends AbstractTypedPointer<AddressPtr<T>> implements AddressPtr<T> {
    private final Class<T> targetType; // 가리킬 대상의 타입 (예: IntPtr.class)

    public AddressPtrImpl(long address, Class<T> targetType, MemoryPool pool) {
        super(address, 8, pool); // 포인터 크기는 항상 8바이트
        this.targetType = targetType;
    }

    @Override
    public T get() {
        long targetAddress = MemoryManager.EVERYTHING.get(ValueLayout.ADDRESS, address).address();
        if (targetAddress == 0) return null;
        // 읽어온 주소를 타겟 포인터 객체로 포장해서 반환
        //return JPhelper.createPointer(targetAddress, targetType); ?
        return new AddressPtrImpl(targetAddress, targetType, pool);
    }

    @Override
    public void set(T ptr) {
        long targetAddr = (ptr == null) ? 0 : ptr.address();
        MemoryManager.EVERYTHING.set(ValueLayout.ADDRESS, address, MemorySegment.ofAddress(targetAddr));
    }

    @Override
    protected AddressPtr<T> create(long newAddress) {
        return null;
    }
    // ... create() 구현 ...
}

