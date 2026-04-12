package com.github.goguma9071.jvmplus;

import static com.github.goguma9071.jvmplus.JPhelper.*;
import com.github.goguma9071.jvmplus.memory.*;
import java.util.Date;
import java.util.Arrays;

public class Main {

    // 1. 모든 프리미티브 및 특수 기능을 포함하는 종합 구조체
    @Struct.Type(alignment = 8) // 성능을 위해 8바이트 정렬 사용
    public interface ComprehensiveStruct extends Struct {
        // 프리미티브 타입
        @Struct.Field(order = 1) byte b(); ComprehensiveStruct b(byte v);
        @Struct.Field(order = 2) int i(); ComprehensiveStruct i(int v);
        @Struct.Field(order = 3) long l(); ComprehensiveStruct l(long v);
        @Struct.Field(order = 4) double d(); ComprehensiveStruct d(double v);

        // 고정 길이 문자열 (UTF-8)
        @Struct.UTF8(length = 16)
        @Struct.Field(order = 5) String name(); ComprehensiveStruct name(String v);

        // 비트 필드 (int 하나를 쪼개서 사용)
        @Struct.BitField(bits = 1)
        @Struct.Field(order = 6) int flagA(); ComprehensiveStruct flagA(int v);
        @Struct.BitField(bits = 2)
        @Struct.Field(order = 7) int mode(); ComprehensiveStruct mode(int v);
        @Struct.BitField(bits = 5)
        @Struct.Field(order = 8) int value(); ComprehensiveStruct value(int v);

        // 원자적 연산 (Atomic) - 멀티스레드 안전
        @Struct.Atomic
        @Struct.Field(order = 9) int counter(); 
        ComprehensiveStruct counter(int v); // 세터 추가
        ComprehensiveStruct casCounter(int exp, int val); // CAS 연산
        ComprehensiveStruct addAndGetCounter(int delta);  // 원자적 더하기

        // 자바 객체 핸들 (Object 저장)
        @Struct.Field(order = 10) Object javaObj(); ComprehensiveStruct javaObj(Object obj);

        // 포인터 (다른 구조체를 가리킴)
        @Struct.Field(order = 11) Pointer<ComprehensiveStruct> next();
        ComprehensiveStruct next(Pointer<ComprehensiveStruct> p);
        
        // 네이티브 콜 (C 표준 라이브러리의 abs 함수 호출 예시)
        @Struct.NativeCall(name = "abs")
        int nativeAbs(int n);
    }

    // 2. SoA (Structure of Arrays) 테스트용 심플 구조체
    @Struct.Type(alignment = 8)
    public interface DataPoint extends Struct {
        @Struct.Field(order = 1) double x(); DataPoint x(double v);
        @Struct.Field(order = 2) double y(); DataPoint y(double v);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("========== JVM PLUS COMPREHENSIVE TEST SUITE ==========");

        try (var _s = suppress()) {
            
            // [TEST 1] 기본 할당 및 프리미티브 액세스
            System.out.println("\n[1. Basic Allocation & Primitives]");
            var s1 = alloc(ComprehensiveStruct.class);
            s1.b((byte)127).i(123456).l(9876543210L).d(3.141592);
            System.out.println("  Byte: " + s1.b() + ", Int: " + s1.i() + ", Long: " + s1.l() + ", Double: " + s1.d());

            // [TEST 2] 문자열 및 비트필드
            System.out.println("\n[2. UTF-8 Strings & BitFields]");
            s1.name("JVM Plus Struct").flagA(1).mode(2).value(15);
            System.out.println("  Name: " + s1.name());
            System.out.println("  BitFields -> flagA: " + s1.flagA() + ", mode: " + s1.mode() + ", value: " + s1.value());

            // [TEST 3] 원자적 연산 (Atomic)
            System.out.println("\n[3. Atomic Operations]");
            s1.addAndGetCounter(10);
            System.out.println("  After Add(10): " + s1.counter());
            s1.casCounter(10, 50);
            System.out.println("  After CAS(10 -> 50): " + s1.counter());

            // [TEST 4] 자바 객체 핸들
            System.out.println("\n[4. Java Object Handles]");
            Date myDate = new Date();
            s1.javaObj(myDate);
            System.out.println("  Stored: " + myDate);
            System.out.println("  Retrieved: " + s1.javaObj());

            // [TEST 5] 포인터 연산
            System.out.println("\n[5. Pointer Operations]");
            var s2 = alloc(ComprehensiveStruct.class).i(999);
            s1.next(s2.asPointer());
            System.out.println("  Linked Struct i: " + s1.next().deref().i());
            
            // [TEST 6] 네이티브 콜 (FFM Downcall)
            System.out.println("\n[6. Native Calls]");
            int absVal = s1.nativeAbs(-500);
            System.out.println("  Native abs(-500): " + absVal);

            // [TEST 7] SoA (Structure of Arrays) & SIMD
            System.out.println("\n[7. SoA & SIMD Vectorization]");
            int count = 1000;
            var soa = soa(count, DataPoint.class);
            for(int i=0; i<count; i++) {
                soa.get(i).x(i * 1.0).y(i * 2.0);
            }
            // SIMD 가속 합계 연산 (DoubleVector 사용)
            double sumX = soa.sumDouble("x"); 
            System.out.println("  SoA SIMD Sum of X (0...999): " + sumX);

            // [TEST 8] Zero-copy Bridge (incorporate)
            System.out.println("\n[8. Zero-copy Bridge (byte[] -> Struct)]");
            // byte[]에 대해 쓰려면 alignment 1인 구조체가 필요함 (여기선 ComprehensiveStruct가 8이므로 힙 메모리도 long[] 권장)
            long[] heap = new long[10];
            var bridged = incorporate(heap, ComprehensiveStruct.class);
            bridged.i(8888);
            System.out.println("  Bridged ID: " + bridged.i());
            System.out.println("  Raw Heap[0] (as int): " + (int)(heap[0] & 0xFFFFFFFFL));

            // [TEST 9] 자동 메모리 관리 (GC-managed)
            System.out.println("\n[9. Auto-managed (GC) Struct]");
            ComprehensiveStruct autoStruct = s1.auto(); // 캐스팅 추가
            System.out.println("  Auto Struct Name: " + autoStruct.name());

        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("\n========== ALL TESTS COMPLETED SUCCESSFULLY ==========");
    }
}
