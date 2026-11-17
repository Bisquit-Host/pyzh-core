package net.minecraft.server;

import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.LeafConfig;
import org.dreeam.leaf.config.LeafGlobalConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@LeafTest
class LeafConfigReloadTest {
    @Test
    void defersModuleMutationUntilTheTickBoundaryDrainsReloads() throws Exception {
        final AtomicReference<Thread> reloadThread = new AtomicReference<>();
        final var globalConfig = LeafConfig.class.getDeclaredField("globalConfig");
        globalConfig.setAccessible(true);
        final Object previousConfig = globalConfig.get(null);
        try (final var configurations = mockConstruction(LeafGlobalConfig.class);
             final var modules = mockStatic(ConfigModule.class);
             final var messages = mockStatic(Command.class)) {
            modules.when(ConfigModule::initModules).thenAnswer(ignored -> {
                reloadThread.set(Thread.currentThread());
                return null;
            });
            final var sender = mock(CommandSender.class);
            final var reload = LeafConfig.reloadAsync(sender);
            assertFalse(reload.isDone());
            assertTrue(configurations.constructed().isEmpty());
            modules.verifyNoInteractions();

            LeafConfig.runPendingReloads();
            assertTrue(reload.isDone());
            assertFalse(reload.isCompletedExceptionally());
            assertSame(Thread.currentThread(), reloadThread.get());
            assertEquals(1, configurations.constructed().size());
            modules.verify(ConfigModule::clearModules);
            modules.verify(ConfigModule::initModules);
            modules.verify(ConfigModule::loadAfterBootstrap);
        } finally {
            globalConfig.set(null, previousConfig);
        }
    }
}
