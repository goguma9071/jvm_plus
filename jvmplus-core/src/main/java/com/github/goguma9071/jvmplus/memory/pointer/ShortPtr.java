package com.github.goguma9071.jvmplus.memory.pointer;

public interface ShortPtr extends TypedPointer<ShortPtr> {
    short get();
    void set(short val);
}
