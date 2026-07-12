package com.github.goguma9071.jvmplus.memory.pointer;

public interface DoublePtr extends TypedPointer<DoublePtr> {

    double get();
    void set(double val);

}
