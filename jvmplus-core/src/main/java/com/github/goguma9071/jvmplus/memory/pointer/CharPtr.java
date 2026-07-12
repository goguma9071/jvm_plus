package com.github.goguma9071.jvmplus.memory.pointer;

public interface CharPtr extends TypedPointer<CharPtr> {

    char get();
    void set(char val);

}
