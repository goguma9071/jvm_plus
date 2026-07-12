package com.github.goguma9071.jvmplus.memory.pointer;

public interface StringPtr extends TypedPointer<StringPtr>{

    String get();
    void set(String val);

}
