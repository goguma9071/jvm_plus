package com.github.goguma9071.jvmplus.memory;

import java.util.Iterator;

/**
 * 오프힙 가변 길이 배열 인터페이스.
 * 구조체(Struct), 기본 타입(Wrapper), 문자열(String)을 모두 지원합니다.
 * @param <T> 저장할 타입
 */
public interface StructVector<T> extends AutoCloseable, Iterable<T> {
    /**
     * 벡터의 끝에 새 요소를 추가합니다.
     * @param value 복사할 원본 객체
     */
    void add(T value);

    /**
     * 특정 인덱스의 요소를 가져옵니다.
     * @param index 인덱스
     * @return 해당 위치의 데이터 (구조체인 경우 rebase된 플라이웨이트)
     */
    T get(int index);

    /**
     * 특정 인덱스의 요소를 수정합니다.
     * @param index 인덱스
     * @param value 복사할 원본 객체
     */
    void set(int index, T value);

    /**
     * 특정 인덱스의 요소를 삭제하고 뒤의 요소들을 앞으로 당깁니다.
     * @param index 삭제할 인덱스
     */
    void remove(int index);

    /**
     * 지정된 비교자(Comparator)를 사용하여 오프힙 데이터를 직접 정렬합니다.
     * @param comparator 비교 로직
     */
    void sort(java.util.Comparator<? super T> comparator);

    /**
     * 현재 저장된 요소의 개수를 반환합니다.
     */
    int size();

    /**
     * 현재 할당된 메모리의 최대 용량을 반환합니다.
     */
    int capacity();

    /**
     * 모든 요소를 삭제하고 사이즈를 0으로 초기화합니다.
     */
    void clear();

    @Override
    void close();
}
