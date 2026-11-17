package net.minecraft.world.level.levelgen.synth;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.LongFunction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

@LeafTest
class NoiseHashTest {
    @Test
    void seededStructuralEqualityStillDeduplicatesEveryNoiseType() {
        final List<LongFunction<Object>> factories = List.of(
            seed -> new ImprovedNoise(new XoroshiroRandomSource(seed)),
            seed -> new SimplexNoise(new XoroshiroRandomSource(seed)),
            seed -> PerlinNoise.create(new XoroshiroRandomSource(seed), -3, 1.0, 0.0, 0.5, -0.0),
            seed -> NormalNoise.create(new XoroshiroRandomSource(seed), -3, 1.0, 0.0, 0.5, -0.0),
            seed -> new BlendedNoise(new XoroshiroRandomSource(seed), 0.25, 0.125, 80, 160, 8)
        );
        for (final LongFunction<Object> factory : factories) {
            final Object first = factory.apply(1234);
            final Object equal = factory.apply(1234);
            final Object different = factory.apply(4321);
            assertEquals(first, equal);
            assertNotEquals(first, different);
            final int hash = first.hashCode();
            final HashMap<Object, String> deduplicated = new HashMap<>();
            deduplicated.put(first, "first");
            for (int i = 0; i < 10; i++) {
                assertEquals(hash, first.hashCode());
                assertEquals(hash, equal.hashCode());
                assertEquals("first", deduplicated.get(equal));
            }
        }
    }

    @Test
    void hashingAndComparingDoNotCopyAmplitudeArrays() {
        final CountingAmplitudes amplitudes = new CountingAmplitudes();
        final PerlinNoise first = PerlinNoise.create(new XoroshiroRandomSource(1234), -2, amplitudes);
        final PerlinNoise equal = PerlinNoise.create(new XoroshiroRandomSource(1234), -2, amplitudes);
        final int constructionCopies = amplitudes.copies;
        assertEquals(first, equal);
        final int hash = first.hashCode();
        amplitudes.set(0, 2.0);
        assertEquals(first, equal, "Sampler identity must use the construction-time amplitudes");
        assertEquals(hash, equal.hashCode());
        assertEquals(hash, first.hashCode());
        assertEquals(constructionCopies, amplitudes.copies);
    }

    @Test
    void cachesAZeroStructuralHash() throws Exception {
        final byte[] permutation = new byte[256];
        for (int i = 0; i < permutation.length; i++) permutation[i] = (byte) i;
        final int lastOffsetHash = -31 * 31 * 31 * (31 + Arrays.hashCode(permutation));
        final double lastOffset = Double.longBitsToDouble(
            0x3ff0000000000000L | Integer.toUnsignedLong(lastOffsetHash ^ 0x3ff00000)
        );
        final RandomSource random = Mockito.mock(RandomSource.class);
        Mockito.when(random.nextDouble()).thenReturn(0.0, 0.0, lastOffset / 256.0);
        final ImprovedNoise noise = new ImprovedNoise(random);
        assertEquals(0, noise.hashCode());
        final var initialized = ImprovedNoise.class.getDeclaredField("hashCodeInitialized");
        initialized.setAccessible(true);
        assertTrue(initialized.getBoolean(noise), "Zero must be a cached value, not an uninitialized sentinel");
        assertEquals(0, noise.hashCode());
    }

    private static final class CountingAmplitudes extends DoubleArrayList {
        private int copies;

        private CountingAmplitudes() {
            super(new double[]{1.0, 0.0, 0.5});
        }

        @Override
        public double[] toDoubleArray() {
            this.copies++;
            return super.toDoubleArray();
        }
    }
}
