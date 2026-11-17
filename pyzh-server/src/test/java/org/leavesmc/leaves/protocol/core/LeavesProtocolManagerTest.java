package org.leavesmc.leaves.protocol.core;

import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@LeafTest
public class LeavesProtocolManagerTest {
    private final Map<Field, Map<?, ?>> previousMaps = new HashMap<>();

    @BeforeEach
    void isolateRegistrations() throws Exception {
        for (Field field : LeavesProtocolManager.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Map.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                Map<?, ?> map = (Map<?, ?>) field.get(null);
                this.previousMaps.put(field, new HashMap<>(map));
                map.clear();
            }
        }
        ActiveProtocol.initializations = 0;
        DisabledProtocol.initializations = 0;
    }

    @AfterEach
    @SuppressWarnings("unchecked")
    void restoreRegistrations() throws Exception {
        for (var entry : this.previousMaps.entrySet()) {
            Map<Object, Object> map = (Map<Object, Object>) entry.getKey().get(null);
            map.clear();
            map.putAll(entry.getValue());
        }
    }

    @Test
    void onlyActiveInboundCodecsConsumePayloadsAndConstructorFailureDoesNotAbortStartup() {
        LeavesProtocolManager.init(new LinkedHashSet<>(List.of(
            BrokenProtocol.class, InboundPayload.class, OutboundPayload.class, DisabledPayload.class,
            ActiveProtocol.class, DisabledProtocol.class
        )));
        assertEquals(1, ActiveProtocol.initializations);
        assertEquals(0, DisabledProtocol.initializations);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            buffer.writeByte(127);
            assertNull(LeavesProtocolManager.decode(DisabledPayload.ID, buffer));
            assertNull(LeavesProtocolManager.decode(OutboundPayload.ID, buffer));
            assertEquals(0, buffer.readerIndex());
            assertInstanceOf(InboundPayload.class, LeavesProtocolManager.decode(InboundPayload.ID, buffer));

            buffer.clear();
            LeavesProtocolManager.encode(buffer, new OutboundPayload());
            assertEquals(OutboundPayload.ID, buffer.readIdentifier());
        } finally {
            buffer.release();
        }
    }

    @Test
    void emptyBytebufDispatchDoesNotDecorateTheBuffer() {
        assertFalse(LeavesProtocolManager.hasBytebufReceivers());
        var buffer = Unpooled.buffer();
        try {
            buffer.writeByte(42);
            assertFalse(LeavesProtocolManager.handleBytebuf(new IdentifierSelector(null, null), Identifier.withDefaultNamespace("brand"), buffer));
            assertEquals(0, buffer.readerIndex());
        } finally {
            buffer.release();
        }
    }

    @LeavesProtocol.Register(namespace = "test")
    public static final class BrokenProtocol implements LeavesProtocol {
        public BrokenProtocol() {
            throw new IllegalStateException("test construction failure");
        }

        @Override
        public boolean isActive() {
            return true;
        }
    }

    @LeavesProtocol.Register(namespace = "test")
    public static final class ActiveProtocol implements LeavesProtocol {
        private static int initializations;

        @ProtocolHandler.Init
        public void init() {
            initializations++;
        }

        @ProtocolHandler.PayloadReceiver(payload = InboundPayload.class)
        public void receive(ServerPlayer player, InboundPayload payload) {
        }

        @Override
        public boolean isActive() {
            return true;
        }
    }

    @LeavesProtocol.Register(namespace = "test")
    public static final class DisabledProtocol implements LeavesProtocol {
        private static int initializations;

        @ProtocolHandler.Init
        public void init() {
            initializations++;
        }

        @ProtocolHandler.PayloadReceiver(payload = DisabledPayload.class)
        public void receive(ServerPlayer player, DisabledPayload payload) {
        }

        @Override
        public boolean isActive() {
            return false;
        }
    }

    public record InboundPayload() implements LeavesCustomPayload {
        @LeavesCustomPayload.ID
        static final Identifier ID = Identifier.fromNamespaceAndPath("test", "inbound");
        @Codec
        static final StreamCodec<RegistryFriendlyByteBuf, InboundPayload> CODEC = StreamCodec.unit(new InboundPayload());
    }

    public record OutboundPayload() implements LeavesCustomPayload {
        @LeavesCustomPayload.ID
        static final Identifier ID = Identifier.fromNamespaceAndPath("test", "outbound");
        @Codec
        static final StreamCodec<RegistryFriendlyByteBuf, OutboundPayload> CODEC = StreamCodec.unit(new OutboundPayload());
    }

    public record DisabledPayload() implements LeavesCustomPayload {
        @LeavesCustomPayload.ID
        static final Identifier ID = Identifier.fromNamespaceAndPath("test", "disabled");
        @Codec
        static final StreamCodec<RegistryFriendlyByteBuf, DisabledPayload> CODEC = StreamCodec.unit(new DisabledPayload());
    }
}
