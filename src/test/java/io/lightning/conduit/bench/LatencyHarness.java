package io.lightning.conduit.bench;

import io.lightning.conduit.dispatcher.EventDispatcher;
import org.HdrHistogram.Histogram;

/**
 * Per-dispatch latency distribution (HdrHistogram) for the single-threaded engine.
 * Numbers include {@code System.nanoTime()} overhead, reported separately as "timer".
 *
 * <pre>
 * java -cp "target/test-classes;target/classes;$(cat target/cp.txt)" io.lightning.conduit.bench.LatencyHarness [events]
 * </pre>
 */
public final class LatencyHarness {
    private static final Object PAYLOAD = new Object();

    public static void main(String[] args) {
        int events = args.length > 0 ? Integer.parseInt(args[0]) : 5_000_000;
        System.out.printf("%-10s %8s %8s %8s %8s %10s%n", "scenario", "p50", "p99", "p99.9", "p99.99", "max");
        run("timer", null, events);
        run("hop1", Graphs.chain(1, new Graphs.Tail()), events);
        run("chain5", Graphs.chain(5, new Graphs.Tail()), events);
        run("diamond", Graphs.diamond(new Graphs.Join()), events);
    }

    private static void run(String name, EventDispatcher<Object> src, int events) {
        Histogram h = new Histogram(10_000_000_000L, 3);
        for (int i = 0; i < events / 2; i++) measure(src);   // warm-up
        for (int i = 0; i < events; i++) h.recordValue(measure(src));
        System.out.printf("%-10s %8d %8d %8d %8d %10d  (ns)%n", name,
                h.getValueAtPercentile(50), h.getValueAtPercentile(99), h.getValueAtPercentile(99.9),
                h.getValueAtPercentile(99.99), h.getMaxValue());
    }

    private static long measure(EventDispatcher<Object> src) {
        long t0 = System.nanoTime();
        if (src != null) src.dispatch(PAYLOAD);
        return System.nanoTime() - t0;
    }
}
