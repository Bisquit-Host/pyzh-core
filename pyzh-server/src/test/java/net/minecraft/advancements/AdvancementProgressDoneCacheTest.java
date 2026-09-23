package net.minecraft.advancements;

import java.util.List;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@LeafTest
class AdvancementProgressDoneCacheTest {

    @Test
    void cachedCompletionFollowsEveryProgressChange() {
        final AdvancementProgress progress = new AdvancementProgress();
        progress.update(new AdvancementRequirements(List.of(List.of("a"), List.of("b", "c"))));
        final AdvancementProgress other = new AdvancementProgress();
        other.update(AdvancementRequirements.allOf(List.of("x")));

        assertFalse(progress.isDone());
        progress.grantProgress("a");
        assertFalse(progress.isDone());
        progress.grantProgress("c");
        assertTrue(progress.isDone());

        other.grantProgress("x");
        assertTrue(progress.isDone(), "a change elsewhere only forces a recompute");

        progress.revokeProgress("a");
        assertFalse(progress.isDone());
        progress.getCriterion("a").grant();
        assertTrue(progress.isDone(), "direct criterion grants invalidate too");

        progress.update(new AdvancementRequirements(List.of(List.of("a"), List.of("d"))));
        assertFalse(progress.isDone());
    }
}
