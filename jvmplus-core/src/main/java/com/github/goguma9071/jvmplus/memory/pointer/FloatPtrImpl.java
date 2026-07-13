package com.github.goguma9071.jvmplus.memory.pointer;

import com.github.goguma9071.jvmplus.memory.MemoryManager;
import com.github.goguma9071.jvmplus.memory.MemoryPool;

import java.lang.foreign.ValueLayout;

public class FloatPtrImpl extends AbstractTypedPointer<FloatPtr> implements FloatPtr {

    public FloatPtrImpl(long address, MemoryPool pool) {
        super(address, 4, pool);
    }
    public FloatPtrImpl(long address) {
        super(address, 4, null);
    }
    @Override
    public void set(float val) {
        MemoryManager.EVERYTHING.set(ValueLayout.JAVA_FLOAT, address, val);
    }
    @Override
    public float get() {
        return MemoryManager.EVERYTHING.get(ValueLayout.JAVA_FLOAT, address);
    }
    @Override
    public FloatPtrImpl create(long newAddress) {
        return new FloatPtrImpl(newAddress);
    }

}
