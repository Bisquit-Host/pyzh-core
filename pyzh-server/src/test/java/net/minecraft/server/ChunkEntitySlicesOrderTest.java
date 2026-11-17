package net.minecraft.server;

import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

@LeafTest
class ChunkEntitySlicesOrderTest {
    @Test
    void preservesInsertionOrderInAllQueryIndexesAfterRemovalAndReinsertion() {
        final ChunkEntitySlices slices = new ChunkEntitySlices(null, 0, 0, FullChunkStatus.ENTITY_TICKING, null, 0, 0);
        final ItemEntity first = entity(1);
        final ItemEntity second = entity(2);
        final ItemEntity third = entity(3);
        final ItemEntity fourth = entity(4);
        for (final ItemEntity entity : List.of(first, second, third, fourth)) {
            assertTrue(slices.addEntity(entity, 0));
        }
        assertQueryOrder(slices, List.of(first, second, third, fourth));
        assertTrue(slices.removeEntity(second, 0));
        assertQueryOrder(slices, List.of(first, third, fourth));
        assertTrue(slices.addEntity(second, 0));
        assertQueryOrder(slices, List.of(first, third, fourth, second));
        assertTrue(slices.removeEntity(first, 0));
        assertTrue(slices.removeEntity(second, 0));
        assertQueryOrder(slices, List.of(third, fourth));
    }

    private static void assertQueryOrder(final ChunkEntitySlices slices, final List<Entity> expected) {
        final AABB box = new AABB(0, 0, 0, 16, 16, 16);
        final List<Entity> result = new ArrayList<>();
        slices.getEntities((Entity) null, box, result, null);
        assertEquals(expected, result);
        result.clear();
        slices.getEntities(ItemEntity.class, null, box, result, null);
        assertEquals(expected, result);
        result.clear();
        slices.getEntities(EntityTypes.ITEM, box, result, null);
        assertEquals(expected, result);
        result.clear();
        slices.getHardCollidingEntities(null, box, result, null);
        assertEquals(expected, result);
    }

    private static ItemEntity entity(final int id) {
        final ItemEntity entity = Mockito.mock(ItemEntity.class);
        Mockito.when(entity.getId()).thenReturn(id);
        Mockito.doReturn(EntityTypes.ITEM).when(entity).getType();
        Mockito.when(entity.moonrise$isHardColliding()).thenReturn(true);
        return entity;
    }
}
