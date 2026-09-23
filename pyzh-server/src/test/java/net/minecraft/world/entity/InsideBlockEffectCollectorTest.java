package net.minecraft.world.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;

@LeafTest
class InsideBlockEffectCollectorTest {

    @Test
    void appliesEffectsInStepOrderAcrossEmptyFlushes() {
        final InsideBlockEffectApplier.StepBasedCollector collector = new InsideBlockEffectApplier.StepBasedCollector();
        final Entity entity = Mockito.mock(Entity.class);
        Mockito.when(entity.isAlive()).thenReturn(true);
        final List<String> applied = new ArrayList<>();
        final InsideBlockEffectType first = InsideBlockEffectType.values()[0];
        final InsideBlockEffectType last = InsideBlockEffectType.values()[InsideBlockEffectType.values().length - 1];

        collector.applyAndClear(entity);
        assertEquals(List.of(), applied);

        collector.advanceStep(0, BlockPos.ZERO);
        collector.runAfter(last, record(applied, "step0-after-last"));
        collector.runBefore(first, record(applied, "step0-before-first"));
        collector.advanceStep(1, BlockPos.ZERO);
        collector.advanceStep(2, BlockPos.ZERO);
        collector.runBefore(last, record(applied, "step2-before-last"));
        collector.applyAndClear(entity);
        assertEquals(List.of("step0-before-first", "step0-after-last", "step2-before-last"), applied);

        applied.clear();
        collector.applyAndClear(entity);
        collector.advanceStep(0, BlockPos.ZERO);
        collector.runAfter(first, record(applied, "again"));
        collector.applyAndClear(entity);
        assertEquals(List.of("again"), applied);
    }

    private static Consumer<Entity> record(final List<String> applied, final String name) {
        return entity -> applied.add(name);
    }
}
