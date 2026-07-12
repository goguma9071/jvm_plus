package com.github.goguma9071.jvmplus.memory.pointer;

public interface LongPtr extends TypedPointer<LongPtr>{

    long get();
    void set(long val);
}
