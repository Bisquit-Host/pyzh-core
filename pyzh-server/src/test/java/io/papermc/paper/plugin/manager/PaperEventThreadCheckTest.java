package io.papermc.paper.plugin.manager;

import org.bukkit.Server;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@LeafTest
public class PaperEventThreadCheckTest {
    @Test
    void rejectsWrongThreadEvenWithoutListeners() {
        Server server = mock(Server.class);
        PaperEventManager manager = new PaperEventManager(server);
        assertThrows(IllegalStateException.class, () -> manager.callEvent(new EmptyEvent(false)));
        when(server.isPrimaryThread()).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> manager.callEvent(new EmptyEvent(true)));
        assertDoesNotThrow(() -> manager.callEvent(new EmptyEvent(false)));
        when(server.isPrimaryThread()).thenReturn(false);
        when(server.isStopping()).thenReturn(true);
        assertDoesNotThrow(() -> manager.callEvent(new EmptyEvent(false)));
    }

    private static final class EmptyEvent extends Event {
        private final HandlerList handlers = new HandlerList();

        private EmptyEvent(boolean async) {
            super(async);
        }

        @Override
        public HandlerList getHandlers() {
            return this.handlers;
        }
    }
}
