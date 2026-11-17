package net.minecraft.server.level;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

@LeafTest
class CustomSpawnerOverridesTest {
    @Test
    void unsetKeepsConstructorInstancesAndOrder() {
        final CustomSpawner other = Mockito.mock(CustomSpawner.class);
        final PhantomSpawner phantom = new PhantomSpawner();
        final List<CustomSpawner> spawners = new ArrayList<>(List.of(other, phantom));
        ServerLevel.configureCustomSpawner(spawners, PhantomSpawner.class, null, () -> {
            fail("An unset option must not construct a replacement");
            return null;
        });
        assertEquals(List.of(other, phantom), spawners);
        spawners.clear();
        ServerLevel.configureCustomSpawner(spawners, PhantomSpawner.class, null, PhantomSpawner::new);
        assertTrue(spawners.isEmpty());
    }

    @Test
    void explicitOverridesOnlyChangeMatchingSpawners() {
        final CustomSpawner other = Mockito.mock(CustomSpawner.class);
        final PhantomSpawner phantom = new PhantomSpawner();
        final List<CustomSpawner> spawners = new ArrayList<>(List.of(phantom, other));
        ServerLevel.configureCustomSpawner(spawners, PhantomSpawner.class, true, PhantomSpawner::new);
        assertEquals(List.of(phantom, other), spawners);
        ServerLevel.configureCustomSpawner(spawners, PhantomSpawner.class, false, PhantomSpawner::new);
        assertEquals(List.of(other), spawners);
        ServerLevel.configureCustomSpawner(spawners, PhantomSpawner.class, true, PhantomSpawner::new);
        assertSame(other, spawners.getFirst());
        assertInstanceOf(PhantomSpawner.class, spawners.getLast());
    }
}
