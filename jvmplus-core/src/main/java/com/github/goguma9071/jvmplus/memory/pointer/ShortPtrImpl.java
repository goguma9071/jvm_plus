package com.github.goguma9071.jvmplus.memory.pointer;

import com.github.goguma9071.jvmplus.memory.MemoryManager;
import com.github.goguma9071.jvmplus.memory.MemoryPool;

import java.lang.foreign.ValueLayout;

public class ShortPtrImpl extends AbstractTypedPointer<ShortPtr> implements ShortPtr {

    public ShortPtrImpl(long address, MemoryPool pool) {
        super(address, 2, pool);
    }

    public ShortPtrImpl(long address) {
        super(address, 2, null);
    }

    @Override
    public void set(short val) {
        MemoryManager.EVERYTHING.set(ValueLayout.JAVA_SHORT, address, val);

    }
    @Override
    public short get() {
        return MemoryManager.EVERYTHING.get(ValueLayout.JAVA_SHORT, address);

    }
    @Override
    public ShortPtrImpl create(long newAddress) {
        return new ShortPtrImpl(newAddress);
    }
}
