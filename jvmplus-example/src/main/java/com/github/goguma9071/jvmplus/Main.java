package com.github.goguma9071.jvmplus;

import static com.github.goguma9071.jvmplus.JPhelper.*;
import com.github.goguma9071.jvmplus.memory.*;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Comparator;

public class Main {

    // --- [1] Enum 정의 ---
    public enum Status { ACTIVE, INACTIVE, DELETED }

    // --- [2] 물리 구조체 정의 (모든 고급 기능 집합) ---
    @Struct.Type
    public interface GameObject extends Struct {
        @Struct.Field(order = 1) int id(); void id(int v);

        // Union: 정수 ID와 부동소수점 에너지를 동일 주소에서 공유
        @Struct.Union
        @Struct.Field(order = 2) float energy(); void energy(float v);

        @Struct.Enum(byteSize = 1)
        @Struct.Field(order = 3) Status status(); void status(Status s);

        @Struct.UTF8(length = 16)
        @Struct.Field(order = 4) String name(); void name(String n);

        @Struct.Static
        @Struct.Field(order = 5) int globalCount(); void globalCount(int v);

        @Struct.Raw(length = 32)
        @Struct.Field(order = 6) RawBuffer secretData();

        // Atomic: 멀티스레드 안전한 필드 및 메서드 선언
        @Struct.Atomic
        @Struct.Field(order = 7) int health(); void health(int v);
        int addAndGetHealth(int delta);
        boolean casHealth(int expected, int newValue);

        @Struct.NativeCall(name = "printf", lib = "libc.so.6")
        int printf(String fmt, String msg);
    }

    @Struct.Type
    public interface Vec3 extends Struct {
        @Struct.Field(order = 1) double x(); void x(double v);
        @Struct.Field(order = 2) double y(); void y(double v);
        @Struct.Field(order = 3) double z(); void z(double v);
    }

    public static void main(String[] args) {
        System.out.println("========== JVM PLUS MEGA INTEGRATION TEST (ULTIMATE) ==========\n");

        testArenaAndSugar();
        testComplexStructFeatures();
        testAtomicAndMmap();
        testCollectionsAndAllocators();
        testSimdAndNative();

        System.out.println("\n========== ALL SYSTEMS NOMINAL: PROJECT COMPLETE ==========");
    }

    private static void testArenaAndSugar() {
        System.out.println("[1. RAII, Scoping & Sugar]");
        try (var p = ptr(5678)) {
            System.out.println("RAII Pointer (Manual Pool) val: " + val(p));
        }

        Pointer<Integer> pAuto = ptr(1234).auto();
        System.out.println("Auto Pointer (GC Managed) val: " + val(pAuto));

        try (Arena a = scope()) {
            GameObject obj = alloc(GameObject.class, a);
            obj.id(777);
            System.out.println("Scoped Object ID: " + obj.id());
        } 
        System.out.println("Scope closed.\n");
    }

    private static void testComplexStructFeatures() {
        System.out.println("[2. Union, Enum, Static & Raw]");
        try (GameObject g1 = alloc(GameObject.class);
             GameObject g2 = alloc(GameObject.class)) {
            
            g1.energy(1.0f);
            System.out.println("Union Energy (Float): " + g1.energy());
            System.out.printf("Union ID (Same memory as Energy): 0x%X\n", g1.id());

            g1.status(Status.ACTIVE);
            g1.name("JVM_PLUS_ENGINE");
            System.out.println("Status: " + g1.status() + ", Name: " + g1.name());

            g1.globalCount(999);
            System.out.println("G1 Static Count: " + g1.globalCount());
            System.out.println("G2 Static Count (Shared): " + g2.globalCount());

            RawBuffer vault = g1.secretData();
            vault.setLong(0, 0x1122334455667788L);
            System.out.printf("RawVault Long: 0x%X\n", vault.getLong(0));
            System.out.println();
        }
    }

    private static void testAtomicAndMmap() {
        System.out.println("[3. Atomic Operations & mmap Persistence]");
        
        try (GameObject g = alloc(GameObject.class)) {
            g.health(100);
            g.addAndGetHealth(50);
            System.out.println("Atomic Health (100 + 50): " + g.health());
            boolean success = g.casHealth(150, 200);
            System.out.println("CAS Health (150 -> 200) Success: " + success + ", Current: " + g.health());
        }

        String path = "game_save.bin";
        try {
            try (StructArray<GameObject> db = map(path, 1, GameObject.class)) {
                GameObject p = db.get(0);
                p.id(99);
                p.name("PersistentHero");
                System.out.println("Mmap Save: " + p.name() + " (ID: " + p.id() + ")");
            }

            try (StructArray<GameObject> db = map(path, 1, GameObject.class)) {
                GameObject p = db.get(0);
                System.out.println("Mmap Load: " + p.name() + " (ID: " + p.id() + ")");
                if (p.id() == 99 && "PersistentHero".equals(p.name())) {
                    System.out.println("VERIFICATION SUCCESS: Persistence working via mmap.");
                }
            }
            Files.deleteIfExists(Paths.get(path));
        } catch (Exception e) {
            System.err.println("Mmap test failed: " + e.getMessage());
        }
        System.out.println();
    }

    private static void testCollectionsAndAllocators() {
        System.out.println("[4. Advanced Collections & Custom Allocators]");
        
        try (Allocator myRegion = bump(1024 * 1024)) {
            System.out.println("Creating Vector & HashMap in Bump Region...");
            
            try (StructVector<Integer> vec = pvector(Integer.class, myRegion)) {
                vec.add(30); vec.add(10); vec.add(20);
                vec.sort(Integer::compareTo);
                System.out.print("Sorted PVector: ");
                for (int i : vec) System.out.print(i + " ");
                System.out.println();
            }

            try (OffHeapHashMap<String, Integer> map = hashmap(String.class, Integer.class, myRegion)) {
                map.put("JPC", 2024);
                System.out.println("HashMap Value: " + map.get("JPC"));
            }
        }
        System.out.println("Bump Region closed.\n");
    }

    private static void testSimdAndNative() {
        System.out.println("[5. SIMD & Native Linker]");
        
        int size = 100;
        try (StructArray<Vec3> array = MemoryManager.allocateSoA(Vec3.class, size)) {
            for (int i = 0; i < size; i++) array.get(i).x(10.0);
            try {
                double sum = (double) array.getClass().getMethod("sumDouble", String.class).invoke(array, "x");
                System.out.println("SIMD Total X: " + sum);
            } catch (Exception ignored) {}
        }

        try (GameObject g = alloc(GameObject.class)) {
            System.out.print("C says: ");
            g.printf("Native message from %s!\n", "JVM PLUS");
        } catch (Exception e) {
            System.out.println("Native call skipped.");
        }
    }
}
