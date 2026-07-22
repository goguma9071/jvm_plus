package com.github.goguma9071.jvmplus.memory.pointer;

import com.github.goguma9071.jvmplus.memory.MemoryManager;
import com.github.goguma9071.jvmplus.memory.MemoryPool;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public final class StringPtrImpl extends AbstractTypedPointer<StringPtr> implements StringPtr {

    public StringPtrImpl(long address, int maxLength, MemoryPool pool) {
        super(address, maxLength, pool);
        // 부모 클래스가 address와 stride(maxLength)를 이미 저장했습니다.
        // 불필요한 segment 필드는 삭제했습니다.
    }

    @Override
    public String get() {
        // 1. EVERYTHING 세그먼트에서 내 주소와 길이(stride)만큼 뷰를 만듭니다. (할당 0ns)
        MemorySegment segment = MemoryManager.EVERYTHING.asSlice(address, stride);

        byte[] b = segment.toArray(ValueLayout.JAVA_BYTE);
        int len = 0;
        while (len < b.length && b[len] != 0) len++;
        return new String(b, 0, len, StandardCharsets.UTF_8);
    }

    @Override
    public void set(String val) {
        // 1. EVERYTHING 세그먼트에서 뷰를 만듭니다.
        MemorySegment segment = MemoryManager.EVERYTHING.asSlice(address, stride);

        byte[] b = val.getBytes(StandardCharsets.UTF_8);
        int cl = Math.min(b.length, stride); // maxLength 대신 부모의 stride 사용

        // 2. 안전하게 복사
        MemorySegment.copy(MemorySegment.ofArray(b), 0, segment, 0, cl);

        if (cl < stride) {
            segment.asSlice(cl, stride - cl).fill((byte) 0);
        }
    }

    @Override
    protected StringPtr create(long newAddress) {
        // offset() 연산 시 호출됨
        return new StringPtrImpl(newAddress, stride, pool);
    }
}