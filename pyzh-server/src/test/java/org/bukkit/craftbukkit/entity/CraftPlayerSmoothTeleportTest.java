package org.bukkit.craftbukkit.entity;

import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Location;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@LeafTest
public class CraftPlayerSmoothTeleportTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restoresPreviousFlagWhenEitherOverloadThrows(boolean previous) {
        ServerPlayer handle = mock(ServerPlayer.class);
        CraftPlayer player = mock(CraftPlayer.class, CALLS_REAL_METHODS);
        doReturn(handle).when(player).getHandle();
        handle.smoothWorldTeleport = previous;
        Location location = new Location(null, 0, 0, 0);
        doAnswer(invocation -> {
            assertTrue(handle.smoothWorldTeleport);
            throw new IllegalArgumentException("invalid world");
        }).when(player).teleport(location);

        assertThrows(IllegalArgumentException.class, () -> player.teleportWithoutRespawn(location));
        assertEquals(previous, handle.smoothWorldTeleport);
        assertThrows(IllegalArgumentException.class, () -> player.teleportWithoutRespawnOptionally(location));
        assertEquals(previous, handle.smoothWorldTeleport);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesResultAndNestedFlag(boolean result) {
        ServerPlayer handle = mock(ServerPlayer.class);
        CraftPlayer player = mock(CraftPlayer.class, CALLS_REAL_METHODS);
        doReturn(handle).when(player).getHandle();
        Location outer = new Location(null, 0, 0, 0);
        Location inner = new Location(null, 1, 0, 0);
        doReturn(result).when(player).teleport(inner);
        doAnswer(invocation -> {
            assertTrue(handle.smoothWorldTeleport);
            assertEquals(result, player.teleportWithoutRespawnOptionally(inner));
            assertTrue(handle.smoothWorldTeleport);
            return result;
        }).when(player).teleport(outer);

        assertEquals(result, player.teleportWithoutRespawnOptionally(outer));
        assertFalse(handle.smoothWorldTeleport);
        player.teleportWithoutRespawn(outer);
        assertFalse(handle.smoothWorldTeleport);
    }
}
