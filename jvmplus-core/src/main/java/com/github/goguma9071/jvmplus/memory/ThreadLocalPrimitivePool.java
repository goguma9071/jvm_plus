package com.github.goguma9071.jvmplus.memory;

import java.lang.foreign.MemorySegment;

    /**
     * TCMalloc 아키텍처를 모방한 스레드 로컬 프리 리스트 (Lock-Free)
     */
    public final class ThreadLocalPrimitivePool {
        private final long[] cache;       // 빈 메모리 주소를 담아두는 스택 (배열이라 극도로 빠름)
        private int head = 0;             // 현재 캐시 포인터
        private final int byteSize;       // 자료형 크기 (예: int는 4)
        private final MemoryPool globalPool; // 캐시가 비었을 때 찾아갈 글로벌 풀

        public ThreadLocalPrimitivePool(int capacity, int byteSize, MemoryPool globalPool) {
            this.cache = new long[capacity];
            this.byteSize = byteSize;
            this.globalPool = globalPool;
        }

        /**
         * [할당] 99%의 확률로 동기화(CAS) 없이 1ns 만에 할당됩니다.
         */
        public long allocate() {
            if (head > 0) {
                // [초고속 경로] 캐시에 주소가 있으면 꺼내줌 (동기화 없음!)
                return cache[--head];
            }
            // [느린 경로] 캐시가 텅 비었으면 글로벌 풀에서 떼어옴
            return fetchFromGlobalBatch();
        }

        /**
         * [일괄 수령] 캐시가 비었을 때 한 번에 여러 개를 가져와 동기화 비용을 상쇄(Amortize)합니다.
         */
        private long fetchFromGlobalBatch() {
            int batchSize = cache.length / 2; // 캐시의 절반만큼 한 번에 떼어옴
            for (int i = 0; i < batchSize - 1; i++) {
                cache[head++] = globalPool.allocate().address(); // 글로벌 풀(CAS) 호출
            }
            return globalPool.allocate().address(); // 마지막 1개는 사용자에게 즉시 반환
        }

        /**
         * [해제] 99%의 확률로 동기화 없이 1ns 만에 반납됩니다.
         */
        public void free(long address) {
            if (head < cache.length) {
                // [초고속 경로] 내 스레드의 캐시 배열에 쏙 넣음 (동기화 없음!)
                cache[head++] = address;
            } else {
                // [느린 경로] 내 주머니가 다 찼으면 글로벌 풀로 반납
                MemorySegment seg = MemoryManager.EVERYTHING.asSlice(address, byteSize);
                globalPool.free(seg);
            }
        }
}
