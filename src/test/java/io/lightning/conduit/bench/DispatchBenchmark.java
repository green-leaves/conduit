package io.lightning.conduit.bench;

import io.lightning.conduit.dispatcher.EventDispatcher;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Single-threaded dispatch cost per external event.
 *
 * <pre>
 * mvn -q test-compile dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/cp.txt
 * java -cp "target/test-classes;target/classes;$(cat target/cp.txt)" org.openjdk.jmh.Main DispatchBenchmark -prof gc
 * </pre>
 * (use {@code :} instead of {@code ;} as the path separator outside Windows)
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class DispatchBenchmark {

    private final Object payload = new Object();

    private EventDispatcher<Object> plain;
    private EventDispatcher<Object> hop1;
    private EventDispatcher<Object> chain5;
    private EventDispatcher<Object> diamond;
    private EventDispatcher<Object> chain5Mega;
    private long plainCount;

    @Setup
    public void setUp() {
        plain = EventDispatcher.create();
        plain.register(e -> plainCount++);
        hop1 = Graphs.chain(1, new Graphs.Tail());
        chain5 = Graphs.chain(5, new Graphs.Tail());
        diamond = Graphs.diamond(new Graphs.Join());
        chain5Mega = Graphs.megamorphicChain5(new Graphs.Tail());
    }

    /** Baseline: one plain listener, no node scheduling. */
    @Benchmark
    public void plainListener() {
        plain.dispatch(payload);
    }

    @Benchmark
    public void hop1() {
        hop1.dispatch(payload);
    }

    @Benchmark
    public void chain5() {
        chain5.dispatch(payload);
    }

    /** Five hops, each a different node class. */
    @Benchmark
    public void chain5Mega() {
        chain5Mega.dispatch(payload);
    }

    @Benchmark
    public void diamond() {
        diamond.dispatch(payload);
    }
}
