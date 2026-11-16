package net.minecraft.server;

import com.mojang.datafixers.DataFixer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.config.modules.async.AsyncPlayerDataSave;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@LeafTest
class PlayerAdvancementsSaveTest {
    @TempDir Path directory;

    @Test
    void skipsUnchangedDataButSavesDirectCriterionChangesReloadsAndMissingFiles() throws Exception {
        final boolean previous = AsyncPlayerDataSave.enabled;
        AsyncPlayerDataSave.enabled = false;
        try {
            final DataFixer fixer = Mockito.mock(DataFixer.class);
            Mockito.doAnswer(invocation -> invocation.getArgument(1)).when(fixer).update(any(), any(), anyInt(), anyInt());
            final ServerAdvancementManager manager = Mockito.mock(ServerAdvancementManager.class);
            Mockito.when(manager.tree()).thenReturn(new AdvancementTree());
            final Advancement advancement = Mockito.mock(Advancement.class);
            Mockito.when(advancement.requirements()).thenReturn(AdvancementRequirements.allOf(List.of("a")));
            final AdvancementHolder holder = new AdvancementHolder(Identifier.fromNamespaceAndPath("pyzh", "save_test"), advancement);
            Mockito.when(manager.get(holder.id())).thenReturn(holder);
            final Path file = this.directory.resolve("advancements.json");
            final PlayerAdvancements player = new PlayerAdvancements(fixer, Mockito.mock(PlayerList.class), manager, file, Mockito.mock(ServerPlayer.class));
            final var progress = player.getOrStartProgress(holder);
            player.save();
            assertTrue(Files.isRegularFile(file));
            final FileTime marker = FileTime.fromMillis(1_000L);
            Files.setLastModifiedTime(file, marker);
            player.save();
            assertEquals(marker, Files.getLastModifiedTime(file));

            progress.getCriterion("a").grant();
            player.save();
            assertNotEquals(marker, Files.getLastModifiedTime(file));
            assertTrue(Files.readString(file).contains("pyzh:save_test"));
            Files.setLastModifiedTime(file, marker);
            progress.getCriterion("a").revoke();
            player.save();
            assertNotEquals(marker, Files.getLastModifiedTime(file));

            Files.setLastModifiedTime(file, marker);
            player.reload(manager);
            player.save();
            assertNotEquals(marker, Files.getLastModifiedTime(file));
            Files.delete(file);
            player.save();
            assertTrue(Files.isRegularFile(file));
        } finally {
            AsyncPlayerDataSave.enabled = previous;
        }
    }

    @Test
    void retriesFailedWritesWithoutAnotherProgressChange() throws Exception {
        final boolean previous = AsyncPlayerDataSave.enabled;
        AsyncPlayerDataSave.enabled = false;
        try {
            final Path parent = this.directory.resolve("blocked");
            Files.writeString(parent, "not a directory");
            final Path file = parent.resolve("advancements.json");
            final ServerAdvancementManager manager = Mockito.mock(ServerAdvancementManager.class);
            Mockito.when(manager.tree()).thenReturn(new AdvancementTree());
            final PlayerAdvancements player = new PlayerAdvancements(Mockito.mock(DataFixer.class), Mockito.mock(PlayerList.class), manager, file, Mockito.mock(ServerPlayer.class));
            player.save();
            assertFalse(Files.exists(file));
            Files.delete(parent);
            player.save();
            assertTrue(Files.isRegularFile(file));
        } finally {
            AsyncPlayerDataSave.enabled = previous;
        }
    }
}
