package net.minecraft.world.level.block;

import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.config.modules.fixes.MCBugFix;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

@LeafTest
class RawCopperSoundTest {
    @Test
    void soundChangesAfterRegistrationAndConfigReload() {
        var state = Blocks.RAW_COPPER_BLOCK.defaultBlockState();
        boolean previous = MCBugFix.mc223153;
        try {
            MCBugFix.mc223153 = false;
            assertSame(SoundType.STONE, state.getSoundType());
            MCBugFix.mc223153 = true;
            assertSame(SoundType.COPPER, state.getSoundType());
            MCBugFix.mc223153 = false;
            assertSame(SoundType.STONE, state.getSoundType());
        } finally {
            MCBugFix.mc223153 = previous;
        }
    }
}
