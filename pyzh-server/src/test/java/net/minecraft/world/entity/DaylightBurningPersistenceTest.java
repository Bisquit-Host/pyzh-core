package net.minecraft.world.entity;

import io.papermc.paper.configuration.WorldConfiguration;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMaps;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftPigZombie;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.LeafTest;
import org.galemc.gale.configuration.GaleGlobalConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.purpurmc.purpur.PurpurWorldConfig;
import org.spigotmc.SpigotWorldConfig;

import static org.junit.jupiter.api.Assertions.*;

@LeafTest
class DaylightBurningPersistenceTest {
    private MockedStatic<GaleGlobalConfiguration> galeConfig;

    @BeforeEach
    void configureSensing() {
        var config = new GaleGlobalConfiguration();
        config.smallOptimizations = config.new SmallOptimizations();
        config.smallOptimizations.reducedIntervals = config.smallOptimizations.new ReducedIntervals();
        galeConfig = Mockito.mockStatic(GaleGlobalConfiguration.class);
        galeConfig.when(GaleGlobalConfiguration::get).thenReturn(config);
    }

    @AfterEach
    void restoreSensingConfig() {
        galeConfig.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void savesBothPaperAndPurpurFlags(boolean shouldBurn) throws Exception {
        Level level = level();
        for (LivingEntity entity : List.of(new Skeleton(EntityTypes.SKELETON, level),
            new Zombie(EntityTypes.ZOMBIE, level), new Phantom(EntityTypes.PHANTOM, level))) {
            entity.setShouldBurnInDay(shouldBurn);
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, RegistryHelper.registryAccess());

            entity.addAdditionalSaveData(output);

            assertEquals(shouldBurn, output.buildResult().getBoolean("Paper.ShouldBurnInDay").orElseThrow());
            assertEquals(shouldBurn, output.buildResult().getBoolean("Purpur.ShouldBurnInDay").orElseThrow());
        }
    }

    @Test
    void paperTrueDoesNotEnableDaylightBurningForHusks() throws Exception {
        LivingEntity husk = new Husk(EntityTypes.HUSK, level());
        assertFalse(husk.shouldBurnInDay());
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Paper.ShouldBurnInDay", true);

        husk.readAdditionalSaveData(TagValueInput.create(ProblemReporter.DISCARDING, RegistryHelper.registryAccess(), tag));

        assertFalse(husk.shouldBurnInDay());
        tag.putBoolean("Purpur.ShouldBurnInDay", true);
        husk.readAdditionalSaveData(TagValueInput.create(ProblemReporter.DISCARDING, RegistryHelper.registryAccess(), tag));
        assertTrue(husk.shouldBurnInDay());
    }

    @Test
    void piglinKeepsPaperBukkitFlagWithoutOptInBurning() throws Exception {
        ZombifiedPiglin piglin = new ZombifiedPiglin(EntityTypes.ZOMBIFIED_PIGLIN, level());
        var api = new CraftPigZombie(Mockito.mock(CraftServer.class), piglin);
        assertTrue(api.shouldBurnInDay());
        assertFalse(piglin.shouldBurnInDay());

        LivingEntity entity = piglin;
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Purpur.ShouldBurnInDay", true);
        entity.readAdditionalSaveData(TagValueInput.create(ProblemReporter.DISCARDING, RegistryHelper.registryAccess(), tag));
        assertTrue(api.shouldBurnInDay());
        assertFalse(piglin.shouldBurnInDay());

        api.setShouldBurnInDay(false);
        assertFalse(api.shouldBurnInDay());
        api.setShouldBurnInDay(true);
        assertTrue(api.shouldBurnInDay());
        assertFalse(piglin.shouldBurnInDay());
    }

    private static Level level() throws Exception {
        Level level = Mockito.mock(Level.class);
        var purpur = Mockito.mock(PurpurWorldConfig.class);
        setField(level, "purpurConfig", purpur);
        setField(level, "spigotConfig", Mockito.mock(SpigotWorldConfig.class));
        var paper = Mockito.mock(WorldConfiguration.class);
        paper.entities = Mockito.mock(WorldConfiguration.Entities.class);
        paper.entities.spawning = Mockito.mock(WorldConfiguration.Entities.Spawning.class);
        paper.entities.spawning.despawnTime = Reference2ObjectMaps.emptyMap();
        paper.entities.behavior = Mockito.mock(WorldConfiguration.Entities.Behavior.class);
        paper.entities.behavior.doorBreakingDifficulty = Map.of(EntityTypes.ZOMBIE, List.of(Difficulty.HARD));
        Mockito.when(level.paperConfig()).thenReturn(paper);
        return level;
    }

    private static void setField(Level level, String name, Object value) throws Exception {
        var field = Level.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(level, value);
    }
}
