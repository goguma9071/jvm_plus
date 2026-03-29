package com.github.goguma9071.jvmplus.memory;

import java.lang.reflect.Field;

/**
 * JVM Plus 전용 디버깅 유틸리티입니다.
 */
public class JPdebug {

    /**
     * 구조체의 메모리 레이아웃(오프셋, 패딩, 크기)을 콘솔에 출력합니다.
     */
    public static void inspect(Struct struct) {
        if (struct == null) {
            System.out.println("[JPC Inspector] Struct is null");
            return;
        }
        try {
            Field mapField = struct.getClass().getField("LAYOUT_MAP");
            String map = (String) mapField.get(null);
            System.out.println(map);
            System.out.println("At Address: 0x" + Long.toHexString(struct.address()).toUpperCase());
        } catch (Exception e) {
            System.err.println("[JPC Inspector] Could not find layout map for: " + struct.getClass().getSimpleName());
        }
    }

    /**
     * 현재 해제되지 않은 모든 오프힙 메모리 할당 정보를 출력합니다.
     */
    public static void checkLeaks() {
        // MemoryManager의 ALLOCATIONS는 private이므로, 
        // 실제 구현 시에는 MemoryManager에 리포팅 메서드를 추가하고 여기서 호출합니다.
        System.out.println("[JPC Leak Detector] Leak report is automatically generated at shutdown.");
    }
}
