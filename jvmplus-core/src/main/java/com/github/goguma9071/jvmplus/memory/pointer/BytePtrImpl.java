package com.github.goguma9071.jvmplus.memory.pointer;

import com.github.goguma9071.jvmplus.memory.MemoryManager;
import com.github.goguma9071.jvmplus.memory.MemoryPool;

import java.lang.foreign.ValueLayout;

public class BytePtrImpl extends AbstractTypedPointer<BytePtr> implements BytePtr{

    public BytePtrImpl(long address, MemoryPool pool) {
        super(address, 1,  pool);
    }

    public BytePtrImpl(long address) {

        super(address, 1, null);
    }

    @Override
    public void set(byte val) {
        MemoryManager.EVERYTHING.set(ValueLayout.JAVA_BYTE, address, val);

    }
    @Override
    public byte get() {
        return MemoryManager.EVERYTHING.get(ValueLayout.JAVA_BYTE, address);

    }
    @Override
    public BytePtrImpl create(long newAddress) {
        return new BytePtrImpl(newAddress);
    }
}
