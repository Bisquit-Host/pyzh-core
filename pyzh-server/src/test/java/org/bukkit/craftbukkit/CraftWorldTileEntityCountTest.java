package org.bukkit.craftbukkit;

import ca.spottedleaf.moonrise.common.PlatformHooks;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.config.modules.opt.OptimizeBlockEntities;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@LeafTest
public class CraftWorldTileEntityCountTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void countsNonTickingBlockEntitiesInTickingChunks(boolean optimize) throws Exception {
        boolean previous = OptimizeBlockEntities.enabled;
        OptimizeBlockEntities.enabled = optimize;
        try (var platform = mockStatic(PlatformHooks.class)) {
            ServerLevel level = mock(ServerLevel.class);
            CraftWorld world = mock(CraftWorld.class, CALLS_REAL_METHODS);
            Field worldField = CraftWorld.class.getDeclaredField("world");
            worldField.setAccessible(true);
            worldField.set(world, level);
            Field tickersField = Level.class.getDeclaredField("blockEntityTickers");
            tickersField.setAccessible(true);
            tickersField.set(level, List.of(mock(TickingBlockEntity.class)));
            LevelChunk chunk = mock(LevelChunk.class);
            var blockEntities = new HashMap<BlockPos, BlockEntity>();
            blockEntities.put(BlockPos.ZERO, mock(BlockEntity.class));
            blockEntities.put(BlockPos.ZERO.above(), mock(BlockEntity.class));
            Field entitiesField = ChunkAccess.class.getDeclaredField("blockEntities");
            entitiesField.setAccessible(true);
            entitiesField.set(chunk, blockEntities);
            ChunkHolder ticking = mock(ChunkHolder.class);
            when(ticking.getTickingChunk()).thenReturn(chunk);
            ChunkHolder nonTicking = mock(ChunkHolder.class);
            PlatformHooks hooks = mock(PlatformHooks.class);
            platform.when(PlatformHooks::get).thenReturn(hooks);
            when(hooks.getVisibleChunkHolders(level)).thenReturn(List.of(ticking, nonTicking));

            assertEquals(2, world.getTileEntityCount());
            assertEquals(1, world.getTickableTileEntityCount());
        } finally {
            OptimizeBlockEntities.enabled = previous;
        }
    }
}
