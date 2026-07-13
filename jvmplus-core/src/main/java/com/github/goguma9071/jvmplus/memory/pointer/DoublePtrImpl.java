package com.github.goguma9071.jvmplus.memory.pointer;

import com.github.goguma9071.jvmplus.memory.MemoryManager;
import com.github.goguma9071.jvmplus.memory.MemoryPool;

import java.lang.foreign.ValueLayout;

public class DoublePtrImpl extends AbstractTypedPointer<DoublePtr> implements DoublePtr {

    public DoublePtrImpl(long address, MemoryPool pool) {
        super(address, 8, pool);
    }
    public DoublePtrImpl(long address) {
        super(address, 8, null);
    }
    @Override
    public void set(double val) {
        MemoryManager.EVERYTHING.set(ValueLayout.JAVA_DOUBLE, address, val);
    }
    @Override
    public double get() {
        return MemoryManager.EVERYTHING.get(ValueLayout.JAVA_DOUBLE, address);
    }
    @Override
    protected DoublePtrImpl create(long newAddress) {
        return new DoublePtrImpl(newAddress);
    }
}
