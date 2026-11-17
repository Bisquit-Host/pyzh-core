package net.minecraft.world.level.biome;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;

@LeafTest
class BiomeManagerLookupTest {
    @Test
    void chunkHintStillUsesTheSelectedChunksNoiseBiome() {
        final BiomeManager.NoiseBiomeSource source = Mockito.mock(BiomeManager.NoiseBiomeSource.class);
        final LevelChunk chunk = Mockito.mock(LevelChunk.class);
        final Holder<Biome> biome = Holder.direct(Mockito.mock(Biome.class));
        Mockito.when(chunk.getNoiseBiome(anyInt(), anyInt(), anyInt())).thenReturn(biome);
        assertSame(biome, new BiomeManager(source, 42L).getBiomeCached(chunk, new BlockPos(8, 64, 8)));
        Mockito.verifyNoInteractions(source);
    }
}
