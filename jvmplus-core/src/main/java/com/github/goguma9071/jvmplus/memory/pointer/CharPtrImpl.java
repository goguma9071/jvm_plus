package com.github.goguma9071.jvmplus.memory.pointer;

import com.github.goguma9071.jvmplus.memory.MemoryManager;
import com.github.goguma9071.jvmplus.memory.MemoryPool;

import java.lang.foreign.ValueLayout;

public class CharPtrImpl extends AbstractTypedPointer<CharPtr> implements CharPtr {

    public CharPtrImpl(long address, MemoryPool pool) {
        super(address, 2, pool);

    }
    public CharPtrImpl(long address) {
        super(address, 2, null);
    }
    @Override
    public void set(char val) {
        MemoryManager.EVERYTHING.set(ValueLayout.JAVA_CHAR, address, val);

    }
    @Override
    public char get() {
        return MemoryManager.EVERYTHING.get(ValueLayout.JAVA_CHAR, address);

    }
    @Override
    public CharPtrImpl create(long newAddress) {
        return new CharPtrImpl(newAddress);
    }
}
