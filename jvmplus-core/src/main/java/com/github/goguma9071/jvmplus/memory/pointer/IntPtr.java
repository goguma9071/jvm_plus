package com.github.goguma9071.jvmplus.memory.pointer;

public interface IntPtr extends TypedPointer<IntPtr> {

    int get();
    void set(int val);

}

