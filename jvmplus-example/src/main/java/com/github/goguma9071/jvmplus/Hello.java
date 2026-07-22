package com.github.goguma9071.jvmplus;

import com.github.goguma9071.jvmplus.memory.Struct;
import com.github.goguma9071.jvmplus.memory.pointer.BytePtr;
import com.github.goguma9071.jvmplus.memory.pointer.IntPtr;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

import static com.github.goguma9071.jvmplus.JPhelper.*;
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class Hello {
    @Benchmark
    public BytePtr start() {
        BytePtr a = byteAlloc((byte) 116);
        return a;
    }

    @Struct.Type
    public interface no_1 extends Struct {

    }

    public static void main(String args[]) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(Hello.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
