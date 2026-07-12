package com.github.goguma9071.jvmplus.memory.pointer;

import com.github.goguma9071.jvmplus.memory.MemoryManager;
import com.github.goguma9071.jvmplus.memory.MemoryPool;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

public abstract class AbstractTypedPointer<P extends TypedPointer<P>> implements TypedPointer<P> {
    protected long address;
    protected final int stride;
    protected MemoryPool pool;

    protected AbstractTypedPointer(long address, int stride, MemoryPool pool) {
        this.address = address;
        this.stride = stride;
        this.pool = pool;
    }
    // 3. auto(): 기존 메모리를 Arena.ofAuto()로 복사하고 원본 해제
    @Override
    public P auto() {
        if (pool == null) return (P) this; // 이미 GC 관리 중이거나 풀이 없으면 무시

        // EVERYTHING 세그먼트를 이용한 초고속 메모리 복사
        MemorySegment original = MemoryManager.EVERYTHING.asSlice(address, stride);
        MemorySegment autoSeg = Arena.ofAuto().allocate(stride);
        MemorySegment.copy(original, 0, autoSeg, 0, stride);

        pool.free(original); // 예전 메모리 반납

        this.address = autoSeg.address(); // 내 주소를 새 주소로 덮어쓰기
        this.pool = null; // 이제부터 GC가 관리하므로 풀 참조 제거
        return (P) this;
    }

    @Override
    public void free() {
        if (pool != null) {
            pool.free(MemoryManager.EVERYTHING.asSlice(address, stride));
            pool = null;
        }
    }

    @Override
    public long address() { return address; }

    @Override
    public long distanceTo(P other) {
        return (this.address - other.address()) / stride;
    }

    // 자식 클래스에게 '나와 똑같은 타입의 새 객체를 만들어라'고 위임 (또는 Rebase)
    protected abstract P create(long newAddress);

    @Override
    public P offset(long count) {
        return create(this.address + (count * stride));
    }
}


