package org.dreeam.leaf.protocol;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@LeafTest
public class ProtocolsTest {
    @Test
    void encoderKeepsItsSnapshotWhenRegistrationChanges() throws Exception {
        final Protocol first = mock(Protocol.class);
        final Protocol target = mock(Protocol.class);
        final TestPayload payload = new TestPayload();
        final StreamCodec<FriendlyByteBuf, TestPayload> codec = StreamCodec.of(
            (buffer, value) -> buffer.writeInt(42), buffer -> new TestPayload());
        when(target.namespace()).thenReturn("snapshot_test");
        when(target.s2c()).thenReturn(List.of(new Protocols.TypeAndCodec<>(TestPayload.TYPE, codec)));
        when(first.namespace()).thenAnswer(ignored -> {
            final Thread reloader = new Thread(() -> Protocols.unregister(target));
            reloader.start();
            reloader.join(5_000L);
            assertFalse(reloader.isAlive());
            return "other_namespace";
        });
        Protocols.register(first);
        Protocols.register(target);
        final FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            Protocols.write(buffer, payload);
            assertEquals(TestPayload.TYPE.id(), buffer.readIdentifier());
            assertEquals(42, buffer.readInt());
            assertFalse(buffer.isReadable());

            buffer.clear();
            Protocols.write(buffer, payload);
            assertEquals(0, buffer.readableBytes(), "The next encode must observe the removal");
        } finally {
            buffer.release();
            Protocols.unregister(first);
            Protocols.unregister(target);
        }
    }

    @Test
    void tickKeepsRegistrationOrderAcrossSnapshotChanges() {
        final Protocol first = mock(Protocol.class);
        final Protocol second = mock(Protocol.class);
        final Protocol third = mock(Protocol.class);
        final Protocol added = mock(Protocol.class);
        doAnswer(ignored -> {
            Protocols.unregister(second);
            Protocols.register(added);
            return null;
        }).doNothing().when(first).tickServer(null);
        Protocols.register(first);
        Protocols.register(second);
        Protocols.register(third);
        try {
            Protocols.tickServer(null);
            final var order = inOrder(first, second, third, added);
            order.verify(first).tickServer(null);
            order.verify(second).tickServer(null);
            order.verify(third).tickServer(null);
            verifyNoInteractions(added);

            Protocols.tickServer(null);
            order.verify(first).tickServer(null);
            order.verify(third).tickServer(null);
            order.verify(added).tickServer(null);
            order.verifyNoMoreInteractions();
        } finally {
            Protocols.unregister(first);
            Protocols.unregister(second);
            Protocols.unregister(third);
            Protocols.unregister(added);
        }
    }

    private record TestPayload() implements LeafCustomPayload {
        private static final Type<TestPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("snapshot_test", "payload"));

        @Override
        public Type<TestPayload> type() {
            return TYPE;
        }
    }
}
