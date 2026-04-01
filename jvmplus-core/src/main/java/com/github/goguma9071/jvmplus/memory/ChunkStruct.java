package com.github.goguma9071.jvmplus.memory;

/**
 * MemoryPool 내부에서 사용하는 개별 메모리 블록(Chunk) 정보를 담는 구조체입니다.
 */
@Struct.Type
public interface ChunkStruct extends Struct {
    @Struct.Field(order = 1) long address();
    ChunkStruct address(long addr);

    @Struct.Field(order = 2) long capacity();
    ChunkStruct capacity(long cap);

    @Struct.Atomic
    @Struct.Field(order = 3) long nextIndex();
    ChunkStruct nextIndex(long idx);
}
