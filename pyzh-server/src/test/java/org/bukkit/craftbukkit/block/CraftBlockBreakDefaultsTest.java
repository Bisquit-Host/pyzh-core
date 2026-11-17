package org.bukkit.craftbukkit.block;

import org.bukkit.inventory.ItemStack;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.config.modules.async.SparklyPaperParallelWorldTicking;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@LeafTest
public class CraftBlockBreakDefaultsTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void keepsEffectsAndExperienceDisabledWithEitherTickingMode(boolean parallel) {
        boolean previous = SparklyPaperParallelWorldTicking.enabled;
        SparklyPaperParallelWorldTicking.enabled = parallel;
        try {
            CraftBlock block = mock(CraftBlock.class, CALLS_REAL_METHODS);
            ItemStack tool = mock(ItemStack.class);
            doReturn(true).when(block).breakNaturally(null, false, false, false);
            doReturn(true).when(block).breakNaturally(tool, false, false, false);

            assertTrue(block.breakNaturally());
            assertTrue(block.breakNaturally(tool));
            verify(block).breakNaturally(null, false, false, false);
            verify(block).breakNaturally(tool, false, false, false);
        } finally {
            SparklyPaperParallelWorldTicking.enabled = previous;
        }
    }
}
