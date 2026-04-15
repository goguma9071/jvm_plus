package com.github.goguma9071.jvmplus;

import static com.github.goguma9071.jvmplus.JPhelper.*;
import com.github.goguma9071.jvmplus.memory.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class Main {

    // --- [1] Struct Definitions ---

    @Struct.Type(alignment = 8)
    public interface DataPoint extends Struct {
        @Struct.Field(order = 1) double x(); DataPoint x(double v);
        @Struct.Field(order = 2) double y(); DataPoint y(double v);
    }

    @Struct.Type(alignment = 8)
    public interface NestedStruct extends Struct {
        @Struct.Field(order = 1) int id(); NestedStruct id(int v);
        @Struct.Field(order = 2) DataPoint point();
    }

    @Struct.Type(alignment = 4)
    public interface UnionStruct extends Struct {
        @Struct.Field(order = 1) @Struct.Union int i(); UnionStruct i(int v);
        @Struct.Field(order = 2) @Struct.Union float f(); UnionStruct f(float v);
        @Struct.Field(order = 3) long padding(); // To check offset
    }


    @Struct.Type(alignment = 8)
    public interface ComprehensiveStruct extends Struct {
        @Struct.Field(order = 1) byte b(); ComprehensiveStruct b(byte v);
        @Struct.Field(order = 2) int i(); ComprehensiveStruct i(int v);
        @Struct.Field(order = 3) long l(); ComprehensiveStruct l(long v);
        @Struct.Field(order = 4) double d(); ComprehensiveStruct d(double v);

        @Struct.UTF8(length = 16)
        @Struct.Field(order = 5) String name(); ComprehensiveStruct name(String v);

        @Struct.BitField(bits = 1)
        @Struct.Field(order = 6) int flagA(); ComprehensiveStruct flagA(int v);
        @Struct.BitField(bits = 2)
        @Struct.Field(order = 7) int mode(); ComprehensiveStruct mode(int v);
        @Struct.BitField(bits = 5)
        @Struct.Field(order = 8) int value(); ComprehensiveStruct value(int v);

        @Struct.Atomic
        @Struct.Field(order = 9) int counter();
        ComprehensiveStruct counter(int v);
        ComprehensiveStruct casCounter(int exp, int val);
        ComprehensiveStruct addAndGetCounter(int delta);

        @Struct.Field(order = 10) Object javaObj(); ComprehensiveStruct javaObj(Object obj);
        @Struct.Field(order = 11) Pointer<ComprehensiveStruct> next();
        ComprehensiveStruct next(Pointer<ComprehensiveStruct> p);

        @Struct.NativeCall(name = "abs")
        int nativeAbs(int n);

    }

    private ComprehensiveStruct s1;
    private StructArray<DataPoint> soa;
    private long[] heapArray = new long[10];

    @Setup
    public void setup1() {
        suppress();
        s1 = alloc(ComprehensiveStruct.class);
        soa = soa(1000, DataPoint.class);
        for(int i=0; i<1000; i++) soa.get(i).x(i).y(i);
    }

    @TearDown
    public void tearDown1() {
        if (s1 != null) s1.free();
        if (soa != null) soa.free();
    }

    @Benchmark
    public Object benchmarkAllocation() {
        try (ComprehensiveStruct s = alloc(ComprehensiveStruct.class)) {
            return s;
        }
    }

    @Benchmark
    public Object benchmarkFieldAccess() {
        return s1.b((byte)127).i(123456).l(9876543210L).d(3.141592);
    }

    @Benchmark
    public String benchmarkStringAndBitField() {
        return s1.name("Benchmark").name();
    }

    @Benchmark
    public int benchmarkAtomic() {
        return s1.addAndGetCounter(1).counter();
    }

    @Benchmark
    public double benchmarkSoASimdSum() {
        return soa.sumDouble("x");
    }

    @Benchmark
    public Object benchmarkIncorporate() {
        return incorporate(heapArray, ComprehensiveStruct.class);
    }

    @Benchmark
    public int benchmarkNativeCall() {
        return s1.nativeAbs(-500);
    }




    // --- [2] Java Heap Equivalents ---

    public static class HeapDataPoint {
        public double x, y;
    }

    public static class HeapNested {
        public int id;
        public final HeapDataPoint point = new HeapDataPoint();
    }

    // --- [3] Benchmark State ---

    private DataPoint offHeapPoint;
    private HeapDataPoint heapPoint;
    private OffHeapHashMap<Integer, Integer> offHeapMap;
    private Map<Integer, Integer> heapMap;
    private StructArray<DataPoint> structArray;
    private HeapDataPoint[] heapArray1;

    @Setup
    public void setup() {
        suppress();
        offHeapPoint = alloc(DataPoint.class);
        heapPoint = new HeapDataPoint();
        
        offHeapMap = hashmap(Integer.class, Integer.class);
        heapMap = new HashMap<>();
        
        structArray = soa(1000, DataPoint.class);
        heapArray1 = new HeapDataPoint[1000];
        for (int i = 0; i < 1000; i++) {
            structArray.get(i).x(i).y(i);
            heapArray1[i] = new HeapDataPoint();
            heapArray1[i].x = i;
            heapArray1[i].y = i;
        }
    }

    @TearDown
    public void tearDown() {
        if (offHeapPoint != null) offHeapPoint.free();
        if (offHeapMap != null) offHeapMap.free();
        if (structArray != null) structArray.free();
    }

    // --- [4] Benchmarks ---

    @Benchmark
    public Object offHeap_Allocation() {
        try (DataPoint p = alloc(DataPoint.class)) {
            return p;
        }
    }

    @Benchmark
    public Object heap_Allocation() {
        return new HeapDataPoint();
    }

    @Benchmark
    public double offHeap_FieldAccess() {
        return offHeapPoint.x(10.5).x();
    }

    @Benchmark
    public double heap_FieldAccess() {
        heapPoint.x = 10.5;
        return heapPoint.x;
    }

    @Benchmark
    public void offHeap_MapPut() {
        offHeapMap.put(1, 100);
    }

    @Benchmark
    public void heap_MapPut() {
        heapMap.put(1, 100);
    }

    @Benchmark
    public double offHeap_ArraySum() {
        double sum = 0;
        for (int i = 0; i < 1000; i++) {
            sum += structArray.get(i).x();
        }
        return sum;
    }

    @Benchmark
    public double heap_ArraySum() {
        double sum = 0;
        for (int i = 0; i < 1000; i++) {
            sum += heapArray1[i].x;
        }
        return sum;
    }

    // --- [5] Manual Tests & Result Display ---

    public static void main(String[] args) throws Exception {
        System.out.println("=== JVMPLUS Functionality Test ===");

        // 1. Pointer Arithmetic & Distance
        try (StructArray<DataPoint> array = array(DataPoint.class, 5)) {
            Pointer<DataPoint> p0 = array.get(0).asPointer();
            Pointer<DataPoint> p3 = array.get(3).asPointer();
            
            System.out.println("[Pointer] p0 address: " + p0.address());
            System.out.println("[Pointer] p3 address: " + p3.address());
            System.out.println("[Pointer] distance(p0, p3): " + p0.distanceTo(p3) + " elements");
            
            Pointer<DataPoint> pOffset = p0.offset(3);
            System.out.println("[Pointer] p0.offset(3) same as p3? " + pOffset.isSame(p3));

            p0.free();
            p3.free();
            pOffset.free();
        }

        // 2. Nested Struct
        try (NestedStruct nested = alloc(NestedStruct.class)) {
            nested.id(1).point().x(100.5).y(200.5);
            System.out.println("[Nested] id: " + nested.id() + ", x: " + nested.point().x() + ", y: " + nested.point().y());
        }

        // 3. Union Struct
        try (UnionStruct union = alloc(UnionStruct.class)) {
            union.i(0x3F800000); // IEEE 754 for 1.0f
            System.out.println("[Union] Set int 0x3F800000 -> float val: " + union.f());
            union.f(2.0f);
            System.out.println("[Union] Set float 2.0f -> int val: 0x" + Integer.toHexString(union.i()).toUpperCase());
        }

        // 4. OffHeapHashMap
        try (OffHeapHashMap<String, Long> map = hashmap(String.class, Long.class)) {
            map.put("Key1", 12345L);
            map.put("Key2", 67890L);
            System.out.println("[HashMap] Get Key1: " + map.get("Key1"));
            System.out.println("[HashMap] Get Key2: " + map.get("Key2"));
            System.out.println("[HashMap] Size: " + map.size());
        }

        // 5. mmap & Zero Copy I/O
        File tempFile = File.createTempFile("jvmplus_mmap", ".dat");
        tempFile.deleteOnExit();
        
        System.out.println("[mmap] Mapping file: " + tempFile.getAbsolutePath());
        try (StructArray<DataPoint> mapped = map(tempFile.getAbsolutePath(), 10, DataPoint.class)) {
            mapped.get(0).x(1.23).y(4.56);
            mapped.get(9).x(9.99).y(0.01);
            System.out.println("[mmap] Data written to mapped memory.");
        }

        // Zero Copy I/O test
        try (DataPoint p = alloc(DataPoint.class)) {
            p.x(777.777).y(888.888);
            try (FileChannel channel = FileChannel.open(tempFile.toPath(), StandardOpenOption.WRITE)) {
                write(channel, p);
                System.out.println("[I/O] Zero-copy write performed.");
            }
            
            p.x(0).y(0); // Clear
            try (FileChannel channel = FileChannel.open(tempFile.toPath(), StandardOpenOption.READ)) {
                read(channel, p);
                System.out.println("[I/O] Zero-copy read result: x=" + p.x() + ", y=" + p.y());
            }
        }

        System.out.println("\n=== Starting JMH Benchmarks ===\n");
        Options opt = new OptionsBuilder()
                .include(Main.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
