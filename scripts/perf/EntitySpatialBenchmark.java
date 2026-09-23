package ru.turbovadim.pyzh.perf;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.dreeam.leaf.util.KDTree2D;
import org.dreeam.leaf.util.KDTree3D;
import org.openjdk.jmh.annotations.*;

/**
 * Rebuild the activation and despawn trees, optionally followed by entity-distance queries.
 * Coordinates move between invocations. Input buffers are reused on both sides to isolate tree cost.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class EntitySpatialBenchmark {
    @Param({"16", "128"})
    public int players;

    private final KDTree2D activation = new KDTree2D();
    private final KDTree3D brain = new KDTree3D();
    private final KDTree3D despawn = new KDTree3D();
    private double[][] coordinates;
    private double[][] horizontal;
    private int[] indices;
    private double[] initialX;
    private final double[][] queries = new double[3][1024];
    private int tick;

    @Setup
    public void setup() {
        final Random random = new Random(42);
        coordinates = new double[3][players];
        horizontal = new double[][]{coordinates[0], coordinates[2]};
        indices = new int[players];
        for (int axis = 0; axis < 3; axis++) {
            for (int i = 0; i < players; i++) {
                coordinates[axis][i] = random.nextDouble() * 256;
            }
            for (int i = 0; i < queries[axis].length; i++) {
                queries[axis][i] = random.nextDouble() * 256;
            }
        }
        initialX = coordinates[0].clone();
    }

    private void rebuild() {
        final int movingPlayer = tick++ % players;
        coordinates[0][movingPlayer] = initialX[movingPlayer] + ((tick / players) & 15) * 0.125;
        activation.build(horizontal, indices);
        brain.build(coordinates, indices);
        despawn.build(coordinates, indices);
    }

    @Benchmark
    public double build() {
        rebuild();
        return activation.nearestSqr(0, 0, 16384)
            + brain.nearestSqr(0, 0, 0, 16384)
            + despawn.nearestSqr(0, 0, 0, 16384);
    }

    @Benchmark
    public double buildAndQuery() {
        rebuild();
        double checksum = 0;
        for (int i = 0; i < queries[0].length; i++) {
            final double x = queries[0][i];
            final double y = queries[1][i];
            final double z = queries[2][i];
            checksum += activation.nearestSqr(x, z, 1024);
            checksum += brain.nearestSqr(x, y, z, 16384);
            checksum += despawn.nearestSqr(x, y, z, 16384.0001);
        }
        return checksum;
    }
}
