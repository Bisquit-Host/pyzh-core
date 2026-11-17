package org.bukkit.craftbukkit.entity;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.entity.EntityTypes;
import org.bukkit.entity.EntityType;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.config.modules.opt.EnableCachedMTBEntityTypeConvert;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@LeafTest
public class CraftEntityTypeCacheTest {
    @Test
    @SuppressWarnings("unchecked")
    void disabledConversionNeitherReadsNorWritesTheCache() throws Exception {
        Field field = CraftEntityType.class.getDeclaredField("MINECRAFT_TO_BUKKIT_KEY_CACHE");
        field.setAccessible(true);
        var cache = (Map<net.minecraft.world.entity.EntityType<?>, EntityType>) field.get(null);
        var previousEntries = new HashMap<>(cache);
        boolean previous = EnableCachedMTBEntityTypeConvert.enabled;
        try {
            EnableCachedMTBEntityTypeConvert.enabled = false;
            cache.clear();
            cache.put(EntityTypes.ZOMBIE, EntityType.COW);
            assertEquals(EntityType.ZOMBIE, CraftEntityType.minecraftToBukkit(EntityTypes.ZOMBIE));
            assertEquals(EntityType.COW, cache.get(EntityTypes.ZOMBIE));

            cache.clear();
            assertEquals(EntityType.ZOMBIE, CraftEntityType.minecraftToBukkit(EntityTypes.ZOMBIE));
            assertTrue(cache.isEmpty());
            EnableCachedMTBEntityTypeConvert.enabled = true;
            assertEquals(EntityType.ZOMBIE, CraftEntityType.minecraftToBukkit(EntityTypes.ZOMBIE));
            assertEquals(EntityType.ZOMBIE, cache.get(EntityTypes.ZOMBIE));
        } finally {
            cache.clear();
            cache.putAll(previousEntries);
            EnableCachedMTBEntityTypeConvert.enabled = previous;
        }
    }
}
