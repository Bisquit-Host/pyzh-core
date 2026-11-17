package net.minecraft.server;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import ca.spottedleaf.moonrise.common.misc.NearbyPlayers;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@LeafTest
class TrackerSnapshotTest {
    @Test
    void trackerTickPublishesTheSnapshotAfterAViewerLeavesRange() throws Exception {
        final CompletableFuture<Void> result = new CompletableFuture<>();
        new TickThread(() -> {
            try {
                viewerLeavesRange();
                result.complete(null);
            } catch (final Throwable failure) {
                result.completeExceptionally(failure);
            }
        }, "tracker-snapshot-test").start();
        result.get();
    }

    private static void viewerLeavesRange() throws Exception {
        final ServerLevel level = mock(ServerLevel.class);
        when(level.getServer()).thenReturn(mock(MinecraftServer.class));
        final ChunkMap chunkMap = mock(ChunkMap.class);
        final Field levelField = ChunkMap.class.getField("level");
        levelField.setAccessible(true);
        levelField.set(chunkMap, level);
        final Entity entity = mock(Entity.class);
        when(entity.getEntityData()).thenReturn(mock(SynchedEntityData.class));
        when(entity.blockPosition()).thenReturn(BlockPos.ZERO);
        final ChunkMap.TrackedEntity tracker = chunkMap.new TrackedEntity(entity, 64, 1, false);

        final ReferenceList<ServerPlayer> nearby = new ReferenceList<>(new ServerPlayer[0]);
        final NearbyPlayers.TrackedChunk chunk = mock(NearbyPlayers.TrackedChunk.class);
        when(chunk.getPlayers(NearbyPlayers.NearbyMapType.VIEW_DISTANCE)).thenReturn(nearby);
        tracker.moonrise$tick(chunk);

        final ServerPlayer leaving = mock(ServerPlayer.class);
        leaving.connection = mock(ServerGamePacketListenerImpl.class);
        when(leaving.moonrise$getChunkLoader()).thenReturn(mock(RegionizedPlayerChunkLoader.PlayerChunkLoaderData.class));
        when(leaving.getX()).thenReturn(1_000.0);
        final ServerPlayerConnection remaining = mock(ServerPlayerConnection.class);
        tracker.seenBy.add(remaining);
        tracker.seenBy.add(leaving.connection);
        tracker.seenByUpdated();
        nearby.add(leaving);

        tracker.moonrise$tick(chunk);

        final ServerPlayerConnection[] snapshot = tracker.seenBy();
        assertArrayEquals(new ServerPlayerConnection[]{remaining}, snapshot);
        assertSame(snapshot, tracker.seenBy());
    }
}
