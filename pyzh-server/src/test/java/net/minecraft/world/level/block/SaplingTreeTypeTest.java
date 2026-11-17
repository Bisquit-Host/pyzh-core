package net.minecraft.world.level.block;

import java.util.concurrent.CompletableFuture;
import org.bukkit.TreeType;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.config.modules.async.SparklyPaperParallelWorldTicking;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@LeafTest
class SaplingTreeTypeTest {
    @Test
    void parallelWorldWithNoTreeTypeDoesNotBorrowAnotherWorldsValue() throws Exception {
        final boolean previous = SparklyPaperParallelWorldTicking.enabled;
        final TreeType global = SaplingBlock.treeType;
        final TreeType local = SaplingBlock.treeTypeRT.get();
        try {
            SparklyPaperParallelWorldTicking.enabled = true;
            SaplingBlock.setTreeTypeRT(TreeType.BIRCH);
            final CompletableFuture<TreeType> observed = new CompletableFuture<>();
            final Thread otherWorld = Thread.ofPlatform().start(() -> {
                try {
                    assertNull(SaplingBlock.getTreeTypeRT());
                    SaplingBlock.setTreeTypeRT(TreeType.JUNGLE);
                    assertEquals(TreeType.JUNGLE, SaplingBlock.getTreeTypeRT());
                    SaplingBlock.setTreeTypeRT(null);
                    observed.complete(SaplingBlock.getTreeTypeRT());
                } catch (Throwable failure) {
                    observed.completeExceptionally(failure);
                } finally {
                    SaplingBlock.treeTypeRT.remove();
                }
            });
            otherWorld.join();
            assertNull(observed.join());
            assertEquals(TreeType.BIRCH, SaplingBlock.getTreeTypeRT());
            SparklyPaperParallelWorldTicking.enabled = false;
            SaplingBlock.treeType = TreeType.REDWOOD;
            assertEquals(TreeType.REDWOOD, SaplingBlock.getTreeTypeRT());
        } finally {
            SparklyPaperParallelWorldTicking.enabled = previous;
            SaplingBlock.treeType = global;
            SaplingBlock.treeTypeRT.set(local);
        }
    }
}
