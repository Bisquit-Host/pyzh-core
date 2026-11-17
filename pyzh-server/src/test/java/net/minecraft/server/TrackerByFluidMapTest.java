package net.minecraft.server;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityFluidInteraction;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.util.map.TrackerByFluidMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@LeafTest
class TrackerByFluidMapTest {
    @Test
    void sizesFollowPopulatedSlotsAndLiveViews() {
        final TrackerByFluidMap map = new TrackerByFluidMap();
        final var entries = map.reference2ObjectEntrySet();
        assertTrue(map.isEmpty());
        assertTrue(entries.isEmpty());

        map.put(FluidTags.WATER, new EntityFluidInteraction.Tracker());
        assertEquals(1, map.size());
        assertEquals(1, entries.size());
        assertEquals(1, entries.toArray().length);

        map.put(FluidTags.WATER, new EntityFluidInteraction.Tracker());
        assertEquals(1, map.size());
        map.put(FluidTags.LAVA, new EntityFluidInteraction.Tracker());
        assertEquals(2, map.size());
        assertEquals(2, entries.size());

        final var iterator = entries.iterator();
        assertSame(FluidTags.WATER, iterator.next().getKey());
        iterator.remove();
        assertEquals(1, map.size());
        assertEquals(1, entries.size());
        assertSame(FluidTags.LAVA, entries.iterator().next().getKey());

        map.clear();
        assertEquals(0, map.size());
        assertTrue(entries.isEmpty());
        assertFalse(entries.iterator().hasNext());

        map.init();
        assertEquals(2, map.size());
        assertEquals(2, entries.size());
        map.reset();
        assertEquals(2, map.size());
    }
}
