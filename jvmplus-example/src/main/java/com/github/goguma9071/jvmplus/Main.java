package com.github.goguma9071.jvmplus;

import com.github.goguma9071.jvmplus.memory.MemoryManager;
import com.github.goguma9071.jvmplus.memory.Struct;

public class Main {
    
    // 1. 하위 구조체 정의 (좌표)
    @Struct.Type
    public interface Vec3 extends Struct {
        @Struct.Field(order = 0) float x();
        void x(float v);
        
        @Struct.Field(order = 1) float y();
        void y(float v);
        
        @Struct.Field(order = 2) float z();
        void z(float v);
    }

    // 2. 상위 구조체 정의 (플레이어)
    @Struct.Type
    public interface Player extends Struct {
        @Struct.Field(order = 0) int id();
        void id(int v);

        // 중첩 구조체 필드
        @Struct.Field(order = 1) Vec3 pos();
        void pos(Vec3 v);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Jvm plus Nested Struct Test ===");



         Player player1 = MemoryManager.allocate(Player.class);

         MemoryManager.free(player1);

         try (Player whhh = MemoryManager.allocate(Player.class)) {
             whhh.id(745);

         }

        // 1. 플레이어 할당
        try (Player player = MemoryManager.allocate(Player.class)) {
            player.id(777);
            
            // 2. 중첩된 구조체(Vec3) 가져오기 및 값 설정
            // 이 때 Vec3는 새로운 할당이 아니라 Player의 메모리 일부를 가리키는 View입니다.
            Vec3 position = player.pos();
            position.x(10.5f);
            position.y(20.0f);
            position.z(-5.5f);

            // 3. 값 검증
            System.out.println("Player ID: " + player.id());
            System.out.println("Position -> X: " + player.pos().x() + ", Y: " + player.pos().y() + ", Z: " + player.pos().z());

            // 4. 레이아웃 확인 (메타데이터 상수 활용)
            Class<?> implClass = Class.forName("com.github.goguma9071.jvmplus.Main_PlayerImpl");
            Class<?> fieldsClass = implClass.getDeclaredClasses()[0];
            long posOffset = fieldsClass.getField("POS_OFFSET").getLong(null);
            System.out.println("Memory Layout: 'pos' field starts at offset " + posOffset + " bytes");
        }

        System.out.println("\n=== Nested Struct Test Finished ===");
    }
}
