package com.github.goguma9071.jvmplus;

import com.github.goguma9071.jvmplus.memory.MemoryManager;
import com.github.goguma9071.jvmplus.memory.Struct;

public class Main {
    
    // 미니 컴파일러에게 "이 인터페이스의 구현체를 만들어라!"라고 명령
    @Struct.Type
    public interface Player extends Struct {
        @Struct.Field(order = 0) int hp();
        void hp(int v);

        @Struct.Field(order = 1) double x();
        void x(double v);

        @Struct.Field(order = 2) double y();
        void y(double v);
    }

    public static void main(String[] args) {
        System.out.println("=== Mini-Compiler (Annotation Processor) Demo ===");

        // 1. 할당 (내부적으로 PlayerImpl이 있으면 그것을 사용함)
        Player p = MemoryManager.allocate(Player.class);
        
        System.out.println("Allocated Implementation: " + p.getClass().getSimpleName());
        System.out.println("Address: 0x" + Long.toHexString(p.address()));

        // 2. 데이터 조작
        p.hp(100);
        System.out.println("Initial HP: " + p.hp());

        p.hp(p.hp() - 20);
        System.out.println("HP after damage: " + p.hp());

        // 3. 해제
        MemoryManager.free(p);
        System.out.println("Memory freed.");

        System.out.println("=== Demo Finished ===");
    }
}
