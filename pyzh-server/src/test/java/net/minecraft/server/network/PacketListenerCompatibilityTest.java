package net.minecraft.server.network;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.support.environment.LeafTest;
import org.galemc.gale.configuration.GaleGlobalConfiguration;
import org.junit.jupiter.api.Test;
import org.leavesmc.leaves.protocol.core.LeavesCustomPayload;
import org.leavesmc.leaves.protocol.core.LeavesProtocolManager;
import org.leavesmc.leaves.protocol.core.ProtocolUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@LeafTest
public class PacketListenerCompatibilityTest {
    private final MinecraftServer server = mock(MinecraftServer.class);
    private final CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "test"), false);

    private ServerCommonPacketListenerImpl listener() {
        return mock(ServerCommonPacketListenerImpl.class, withSettings()
            .useConstructor(this.server, mock(Connection.class), this.cookie).defaultAnswer(CALLS_REAL_METHODS));
    }

    @Test
    void decodedLeavesPayloadWaitsForThePacketThread() throws Exception {
        PacketProcessor processor = new PacketProcessor(Thread.currentThread());
        when(this.server.packetProcessor()).thenReturn(processor);
        ServerCommonPacketListenerImpl listener = this.listener();
        when(listener.shouldHandleMessage(any())).thenReturn(true);
        LeavesCustomPayload payload = mock(LeavesCustomPayload.class);
        ServerboundCustomPayloadPacket packet = new ServerboundCustomPayloadPacket(payload);

        try (var protocols = mockStatic(LeavesProtocolManager.class)) {
            CompletableFuture.runAsync(() -> assertThrows(RunningOnDifferentThreadException.class, () -> listener.handleCustomPayload(packet))).get(5, TimeUnit.SECONDS);
            protocols.verifyNoInteractions();
            assertTrue(processor.executeSinglePacket());
            protocols.verify(() -> LeavesProtocolManager.handlePayload(any(), same(payload)));
            assertFalse(processor.executeSinglePacket());
        }
    }

    @Test
    void rawPluginPayloadReachesBukkitOnceWithoutEmptyLeavesDispatch() throws Exception {
        PacketProcessor processor = new PacketProcessor(Thread.currentThread());
        when(this.server.packetProcessor()).thenReturn(processor);
        this.server.server = mock(CraftServer.class);
        Messenger messenger = mock(Messenger.class);
        when(this.server.server.getMessenger()).thenReturn(messenger);
        ServerCommonPacketListenerImpl listener = this.listener();
        when(listener.shouldHandleMessage(any())).thenReturn(true);
        byte[] bytes = {1, 2, 3};
        ServerboundCustomPayloadPacket packet = new ServerboundCustomPayloadPacket(new DiscardedPayload(Identifier.fromNamespaceAndPath("jade", "client_handshake"), bytes));

        try (var utils = mockStatic(ProtocolUtils.class); var protocols = mockStatic(LeavesProtocolManager.class)) {
            CompletableFuture.runAsync(() -> assertThrows(RunningOnDifferentThreadException.class, () -> listener.handleCustomPayload(packet))).get(5, TimeUnit.SECONDS);
            protocols.verifyNoInteractions();
            assertTrue(processor.executeSinglePacket());
            verify(messenger).dispatchIncomingMessage(nullable(io.papermc.paper.connection.PlayerConnection.class), eq("jade:client_handshake"), same(bytes));
            utils.verifyNoInteractions();
            protocols.verify(LeavesProtocolManager::hasBytebufReceivers);
            protocols.verifyNoMoreInteractions();
            assertFalse(processor.executeSinglePacket());
        }
    }

    @Test
    void alternateKeepaliveAllowsConcurrentReplyWhileSendingWithoutHoldingTheStateLock() throws Exception {
        Field configField = GaleGlobalConfiguration.class.getDeclaredField("instance");
        configField.setAccessible(true);
        Object previous = configField.get(null);
        GaleGlobalConfiguration config = new GaleGlobalConfiguration();
        config.misc = config.new Misc();
        config.misc.keepalive = config.misc.new Keepalive();
        config.misc.keepalive.sendMultiple = true;
        configField.set(null, config);
        try {
            ServerCommonPacketListenerImpl listener = this.listener();
            doAnswer(invocation -> {
                ClientboundKeepAlivePacket packet = invocation.getArgument(0);
                CompletableFuture.runAsync(() -> listener.handleKeepAlive(new ServerboundKeepAlivePacket(packet.getId()))).get(5, TimeUnit.SECONDS);
                return null;
            }).when(listener).send(any(ClientboundKeepAlivePacket.class));

            for (int i = 0; i < 40; i++) {
                this.cookie.keepAlive().lastKeepAliveTx = 0;
                listener.keepConnectionAlive();
            }
            verify(listener, times(40)).send(any(ClientboundKeepAlivePacket.class));
            verify(listener, never()).disconnect(any(), any());
        } finally {
            configField.set(null, previous);
        }
    }

    @Test
    void spectatorTeleportDoesNotRequireBukkitPermission() throws Exception {
        PacketProcessor processor = new PacketProcessor(Thread.currentThread());
        when(this.server.packetProcessor()).thenReturn(processor);
        ServerGamePacketListenerImpl listener = mock(ServerGamePacketListenerImpl.class, CALLS_REAL_METHODS);
        Field serverField = ServerCommonPacketListenerImpl.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(listener, this.server);
        ServerPlayer player = mock(ServerPlayer.class);
        listener.player = player;
        ServerLevel level = mock(ServerLevel.class);
        when(level.getServer()).thenReturn(this.server);
        when(player.level()).thenReturn(level);
        when(player.isSpectator()).thenReturn(true);
        when(this.server.getAllLevels()).thenReturn(java.util.List.of(level));
        UUID targetId = UUID.randomUUID();
        ServerPlayer target = mock(ServerPlayer.class);
        when(level.getEntity(targetId)).thenReturn(target);
        when(target.getX()).thenReturn(12.0);
        when(target.getY()).thenReturn(64.0);
        when(target.getZ()).thenReturn(34.0);

        listener.handleTeleportToEntityPacket(new ServerboundTeleportToEntityPacket(targetId));
        verify(player).teleportTo(level, 12.0, 64.0, 34.0, Set.of(), 0.0F, 0.0F, true, PlayerTeleportEvent.TeleportCause.SPECTATE);
        verify(player, never()).getBukkitEntity();
    }
}
