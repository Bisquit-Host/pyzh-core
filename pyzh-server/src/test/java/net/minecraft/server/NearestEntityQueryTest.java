package net.minecraft.server;

import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.EntityLookup;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerEntityGetter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.Level;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@LeafTest
class NearestEntityQueryTest {
    @Test
    void matchesListSearchAndKeepsTargetingOrderAndFirstTieWithoutCollectingCandidates() throws Exception {
        final ServerLevel level = Mockito.mock(ServerLevel.class);
        final var purpurConfig = Level.class.getDeclaredField("purpurConfig");
        purpurConfig.setAccessible(true);
        purpurConfig.set(level, Mockito.mock(org.purpurmc.purpur.PurpurWorldConfig.class));
        final EntityLookup lookup = Mockito.mock(EntityLookup.class);
        Mockito.when(level.moonrise$getEntityLookup()).thenReturn(lookup);
        final ServerEntityGetter getter = Mockito.mock(ServerEntityGetter.class, Mockito.CALLS_REAL_METHODS);
        Mockito.when(getter.getLevel()).thenReturn(level);
        final AABB box = new AABB(-10, -10, -10, 10, 10, 10);
        final LivingEntity rejected = entity(0.0);
        final LivingEntity far = entity(25.0);
        final LivingEntity first = entity(4.0);
        final LivingEntity tied = entity(4.0);
        final List<LivingEntity> candidates = new ArrayList<>(List.of(rejected, far, first, tied));
        final List<LivingEntity> tested = new ArrayList<>();
        final TargetingConditions conditions = TargetingConditions.forNonCombat().ignoreLineOfSight().selector((target, world) -> {
            tested.add(target);
            return target != rejected;
        });

        Mockito.doAnswer(invocation -> {
            final List<? super LivingEntity> output = invocation.getArgument(3);
            final Predicate<? super LivingEntity> predicate = invocation.getArgument(4);
            for (final LivingEntity candidate : candidates) {
                if (predicate.test(candidate)) output.add(candidate);
            }
            assertTrue(output.isEmpty(), "The spatial scan must not retain a candidate list");
            return null;
        }).when(lookup).getEntities(eq(LivingEntity.class), isNull(), same(box), anyList(), any());

        final LivingEntity baseline = getter.getNearestEntity(candidates, conditions, null, 0, 0, 0);
        final List<LivingEntity> baselineOrder = List.copyOf(tested);
        tested.clear();
        assertSame(first, baseline);
        assertSame(baseline, getter.getNearestEntity(LivingEntity.class, conditions, null, 0, 0, 0, box));
        assertEquals(baselineOrder, tested);

        final var tag = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE,
            net.minecraft.resources.Identifier.fromNamespaceAndPath("pyzh", "nearest_test"));
        Mockito.when(far.is(tag)).thenReturn(true);
        Mockito.when(tied.is(tag)).thenReturn(true);
        tested.clear();
        assertSame(tied, getter.getNearestEntity(tag, conditions, null, 0, 0, 0, box));
        assertEquals(List.of(far, tied), tested, "Nonmatching tags must not run targeting checks");
        candidates.clear();
        assertNull(getter.getNearestEntity(LivingEntity.class, conditions, null, 0, 0, 0, box));
    }

    private static LivingEntity entity(final double squaredDistance) {
        final LivingEntity entity = Mockito.mock(LivingEntity.class);
        Mockito.when(entity.canBeSeenByAnyone()).thenReturn(true);
        Mockito.when(entity.distanceToSqr(0, 0, 0)).thenReturn(squaredDistance);
        return entity;
    }
}
