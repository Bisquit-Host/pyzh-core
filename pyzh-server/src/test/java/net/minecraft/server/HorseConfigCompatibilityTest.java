package net.minecraft.server;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.purpurmc.purpur.PurpurConfig;
import org.purpurmc.purpur.PurpurWorldConfig;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;

@LeafTest
class HorseConfigCompatibilityTest {
    @Test
    void customRangesWarnOnceAndTheFamilyOptionSuppressesTheWarning() throws Exception {
        YamlConfiguration previous = PurpurConfig.config;
        try (var bukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
            Logger logger = Mockito.mock(Logger.class);
            bukkit.when(Bukkit::getLogger).thenReturn(logger);
            PurpurConfig.config = new YamlConfiguration();
            PurpurWorldConfig config = Mockito.mock(PurpurWorldConfig.class, Mockito.CALLS_REAL_METHODS);
            Field worldName = PurpurWorldConfig.class.getDeclaredField("worldName");
            worldName.setAccessible(true);
            worldName.set(config, "minecraft:overworld");
            Method warn = PurpurWorldConfig.class.getDeclaredMethod("warnAboutHorseAttributes", boolean.class);
            warn.setAccessible(true);
            warn.invoke(config, false);
            Mockito.verifyNoInteractions(logger);
            PurpurConfig.config.set("world-settings.default.mobs.donkey.attributes.max_health.max", 40.0);
            warn.invoke(config, true);
            Mockito.verifyNoInteractions(logger);
            warn.invoke(config, false);
            warn.invoke(config, false);
            Mockito.verify(logger).warning(contains("mobs.horse-family.configured-attributes"));
            Mockito.verifyNoMoreInteractions(logger);

            Method horseSettings = PurpurWorldConfig.class.getDeclaredMethod("horseSettings");
            horseSettings.setAccessible(true);
            PurpurConfig.config.set("world-settings.default.mobs.horse-family.configured-attributes", true);
            horseSettings.invoke(config);
            assertTrue(config.configuredHorseAttributes);
            PurpurConfig.config.set("world-settings.default.mobs.horse-family.configured-attributes", false);
            horseSettings.invoke(config);
            assertFalse(config.configuredHorseAttributes);
        } finally {
            PurpurConfig.config = previous;
        }
    }
}
