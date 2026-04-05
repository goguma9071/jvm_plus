package com.github.goguma9071.jvmplus.memory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 자바 힙 객체와 오프힙 숫자 핸들을 연결하는 레지스트리입니다.
 */
public class HandleRegistry<T> {
    private final AtomicLong nextHandle = new AtomicLong(1); // 0은 NULL 대용
    private final ConcurrentHashMap<Long, T> registry = new ConcurrentHashMap<>();

    /**
     * 자바 객체를 등록하고 오프힙에 저장할 수 있는 핸들 ID를 반환합니다.
     */
    public long register(T obj) {
        if (obj == null) return 0;
        long handle = nextHandle.getAndIncrement();
        registry.put(handle, obj);
        return handle;
    }

    /**
     * 핸들 ID를 사용하여 자바 객체를 복원합니다.
     */
    public T get(long handle) {
        if (handle == 0) return null;
        return registry.get(handle);
    }

    /**
     * 더 이상 필요 없는 핸들을 해제합니다.
     */
    public void release(long handle) {
        registry.remove(handle);
    }
}
