package pyzh.harness;

import com.sun.management.ThreadMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-tick samples for the measured window. A tick spans from the end of the harness' own per-tick work to the
 * start of the next tick's harness work, so it covers injected packet handling, the tick, and inter-tick tasks,
 * and excludes harness path planning and scenario feeds. In a sprint that span has no sleep; otherwise the
 * tick-end timestamp is used.
 */
final class Meter {
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final com.sun.management.OperatingSystemMXBean OS =
        (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    final long[] spanNanos;
    final long[] tickNanos;
    final long[] allocBytes;
    private final boolean sprint;
    private final long[] calibration;
    private int calibrations;
    private int index = -1;
    private long openNanos, openAlloc, openCpu;
    private long cpuNanos;
    private long startNanos, endNanos, gcCount, gcMillis, processCpu;

    Meter(int ticks, boolean sprint) {
        this.spanNanos = new long[ticks];
        this.tickNanos = new long[ticks];
        this.allocBytes = new long[ticks];
        this.calibration = new long[ticks / CALIBRATION_INTERVAL + 2];
        this.sprint = sprint;
    }

    static final int CALIBRATION_INTERVAL = 20;
    private static long sink;

    /**
     * Times a fixed arithmetic loop (about 0.1 ms) on the main thread. Its duration does not depend on the server,
     * only on how fast the core is that the scheduler gave this run, which explains part of the run-to-run noise
     * on machines with mixed core types or frequency scaling.
     */
    static long calibrate() {
        long start = System.nanoTime();
        long x = 88172645463325252L, sum = 0;
        for (int i = 0; i < 100_000; i++) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            sum += x;
        }
        sink += sum;
        return System.nanoTime() - start;
    }

    void addCalibration(long nanos) {
        if (running()) {
            calibration[calibrations++] = nanos;
        }
    }

    static long allocated() {
        return THREADS.getCurrentThreadAllocatedBytes();
    }

    boolean running() {
        return index >= 0 && index < spanNanos.length;
    }

    void begin() {
        index = 0;
        startNanos = System.nanoTime();
        gcCount = -gc(true);
        gcMillis = -gc(false);
        processCpu = -OS.getProcessCpuTime();
    }

    /** Harness work for this tick is done; the server's share starts now. */
    void open() {
        openCpu = THREADS.getCurrentThreadCpuTime();
        openAlloc = allocated();
        openNanos = System.nanoTime();
    }

    void tickEnd() {
        if (running()) {
            tickNanos[index] = System.nanoTime() - openNanos;
        }
    }

    /** Called at the very start of the next tick's harness work. Returns true when the window is complete. */
    boolean close(long nowNanos, long nowAlloc) {
        spanNanos[index] = nowNanos - openNanos;
        allocBytes[index] = nowAlloc - openAlloc;
        cpuNanos += THREADS.getCurrentThreadCpuTime() - openCpu;
        if (++index < spanNanos.length) {
            return false;
        }
        endNanos = nowNanos;
        gcCount += gc(true);
        gcMillis += gc(false);
        processCpu += OS.getProcessCpuTime();
        return true;
    }

    double wallSeconds() {
        return (endNanos - startNanos) / 1e9;
    }

    Map<String, Double> metrics() {
        int n = spanNanos.length;
        long[] sorted = (sprint ? spanNanos : tickNanos).clone();
        Arrays.sort(sorted);
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("mspt_mean", Arrays.stream(sorted).sum() / 1e6 / n);
        out.put("mspt_p50", sorted[n / 2] / 1e6);
        out.put("mspt_p95", sorted[(int) (n * 0.95)] / 1e6);
        out.put("mspt_p99", sorted[(int) (n * 0.99)] / 1e6);
        out.put("mspt_max", sorted[n - 1] / 1e6);
        out.put("main_cpu_ms_per_tick", cpuNanos / 1e6 / n);
        out.put("alloc_kb_per_tick", Arrays.stream(allocBytes).sum() / 1024.0 / n);
        out.put("gc_count", (double) gcCount);
        out.put("gc_ms", (double) gcMillis);
        out.put("process_cpu_ms_per_tick", processCpu / 1e6 / n);
        long[] calibrated = Arrays.copyOf(calibration, calibrations);
        Arrays.sort(calibrated);
        out.put("cpu_calibration_us", calibrations == 0 ? 0 : calibrated[calibrations / 2] / 1e3);
        out.put("wall_s", wallSeconds());
        return out;
    }

    private static long gc(boolean count) {
        long total = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, count ? bean.getCollectionCount() : bean.getCollectionTime());
        }
        return total;
    }
}
