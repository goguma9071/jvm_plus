package com.github.goguma9071.jvmplus.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 자바 객체를 오프힙 데이터와 연결하기 위한 핸들 레지스트리입니다.
 * 오프힙에는 객체 대신 정수형 핸들(ID)만 저장하고, 이 클래스를 통해 실제 객체를 찾습니다.
 */
public class HandleRegistry<T> {
    private final List<T> objects = new ArrayList<>();
    private final AtomicInteger nextId = new AtomicInteger(0);

    public HandleRegistry() {
        // ID 0은 null이나 유효하지 않은 상태를 위해 비워둠
        objects.add(null);
        nextId.incrementAndGet();
    }

    public synchronized int register(T obj) {
        if (obj == null) return 0;
        int id = nextId.getAndIncrement();
        objects.add(obj);
        return id;
    }

    public synchronized T get(int id) {
        if (id <= 0 || id >= objects.size()) return null;
        return objects.get(id);
    }

    public synchronized void unregister(int id) {
        if (id > 0 && id < objects.size()) {
            objects.set(id, null);
        }
    }
}
