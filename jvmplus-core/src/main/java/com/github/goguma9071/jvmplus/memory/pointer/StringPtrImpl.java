package com.github.goguma9071.jvmplus.memory.pointer;

import com.github.goguma9071.jvmplus.memory.MemoryPool;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public final class StringPtrImpl extends AbstractTypedPointer<StringPtr> implements StringPtr {
    // 최대 길이(maxLength)가 곧 stride(보폭)가 됩니다.
    public StringPtrImpl(long address, int maxLength, MemoryPool pool, int maxLength1) {
        super(address, maxLength, pool);
        this.maxLength = maxLength1;

    }
    private MemorySegment segment;
    private final int maxLength;


    @Override
    public String get() {
        byte[] b = segment.toArray(ValueLayout.JAVA_BYTE);
        int len = 0;
        while (len < b.length && b[len] != 0) len++;
        return new String(b, 0, len, StandardCharsets.UTF_8);
    }

    @Override
    public void set(String val) {
        byte[] b = val.getBytes(StandardCharsets.UTF_8);
        int cl = Math.min(b.length, maxLength);
        MemorySegment.copy(MemorySegment.ofArray(b), 0, segment, 0, cl);
        if (cl < maxLength) segment.asSlice(cl, maxLength - cl).fill((byte) 0);
    }

    @Override
    public StringPtr create(long newAddress) {

        return new StringPtrImpl(newAddress, maxLength, pool, maxLength);
    }
}