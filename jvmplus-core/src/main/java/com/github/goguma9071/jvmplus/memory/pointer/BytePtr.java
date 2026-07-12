package com.github.goguma9071.jvmplus.memory.pointer;

public interface BytePtr extends TypedPointer<BytePtr>{

    byte get();
    void set(byte val);

}
