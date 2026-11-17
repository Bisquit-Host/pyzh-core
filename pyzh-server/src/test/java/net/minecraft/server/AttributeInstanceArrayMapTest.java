package net.minecraft.server;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.util.map.AttributeInstanceArrayMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@LeafTest
class AttributeInstanceArrayMapTest {
    @Test
    void matchesOrdinaryMapAndEntryHashesAcrossMutations() {
        final AttributeInstanceArrayMap actual = new AttributeInstanceArrayMap();
        final Map<Holder<Attribute>, AttributeInstance> expected = new HashMap<>();
        assertEquals(expected.hashCode(), actual.hashCode());
        for (final Holder<Attribute> attribute : java.util.List.of(Attributes.MAX_HEALTH, Attributes.MOVEMENT_SPEED, Attributes.ARMOR)) {
            final AttributeInstance instance = new AttributeInstance(attribute, ignored -> {});
            actual.put(attribute, instance);
            expected.put(attribute, instance);
            assertEquals(expected, actual);
            assertEquals(actual, expected);
            assertEquals(expected.hashCode(), actual.hashCode());
            for (final var entry : actual.entrySet()) {
                assertEquals(Map.entry(entry.getKey(), entry.getValue()).hashCode(), entry.hashCode());
            }
        }
        actual.remove(Attributes.MOVEMENT_SPEED);
        expected.remove(Attributes.MOVEMENT_SPEED);
        assertEquals(expected.hashCode(), actual.hashCode());
        assertEquals(expected.hashCode(), actual.clone().hashCode());
        actual.clear();
        assertEquals(0, actual.hashCode());
    }

    @Test
    void entryHashAcceptsNullAfterRemovingItsMapping() {
        final AttributeInstanceArrayMap map = new AttributeInstanceArrayMap();
        final AttributeInstance instance = new AttributeInstance(Attributes.MAX_HEALTH, ignored -> {});
        map.put(Attributes.MAX_HEALTH, instance);
        final var entry = map.entrySet().iterator().next();
        assertSame(instance, entry.setValue(null));
        assertTrue(map.isEmpty());
        assertEquals(Attributes.MAX_HEALTH.hashCode(), entry.hashCode());
    }
}
