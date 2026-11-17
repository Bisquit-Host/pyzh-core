package net.minecraft.world.entity;

import ca.spottedleaf.moonrise.patches.collisions.CollisionUtil;
import ca.spottedleaf.moonrise.common.PlatformHooks;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices;
import io.papermc.paper.configuration.WorldConfiguration;
import io.papermc.paper.configuration.type.number.IntOr;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.OptionalInt;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.bukkit.support.environment.LeafTest;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.dreeam.leaf.config.modules.opt.DespawnTime;
import org.dreeam.leaf.world.EntityCollisionCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.purpurmc.purpur.PurpurWorldConfig;
import org.spigotmc.SpigotWorldConfig;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@LeafTest
class EntityCoreCompatibilityTest {
    @Test
    void entitiesStartWithUnthrottledBrainPriority() throws Exception {
        assertEquals(1, marker().activatedPriority);
    }

    @Test
    void distanceUsesPaperFloatRoundingByDefault() {
        final Entity first = Mockito.mock(Entity.class, Mockito.CALLS_REAL_METHODS);
        final Entity second = Mockito.mock(Entity.class);
        final double[][] positions = {
            {123.456789, 67.123456, -987.654321},
            {0.99999998, 0.0003456, 0.123456789},
            {30000000.123, -64.987, 29999999.999}
        };
        for (final double[] position : positions) {
            Mockito.doReturn(position[0]).when(first).getX();
            Mockito.doReturn(position[1]).when(first).getY();
            Mockito.doReturn(position[2]).when(first).getZ();
            final float x = (float)position[0];
            final float y = (float)position[1];
            final float z = (float)position[2];
            assertEquals(Mth.sqrt(x * x + y * y + z * z), first.distanceTo(second));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void compensatesAgeAndVetoesSavingOnlyForWeakLoading(final boolean enabled) throws Exception {
        final boolean previous = DespawnTime.proactiveWeakLoading;
        try {
            DespawnTime.proactiveWeakLoading = enabled;
            final Entity entity = marker();
            entity.totalEntityAge = 10;
            setField(entity, Entity.class, "lastTickTime", MinecraftServer.currentTick - 100);

            assertEquals(enabled, entity.isExceedingDespawnTime(true));
            assertEquals(!enabled, entity.shouldBeSaved());
            final ValueOutput output = Mockito.mock(ValueOutput.class);
            entity.saveWithoutId(output, false, false, false);
            Mockito.verify(output).putInt("Spigot.ticksLived", enabled ? 110 : 10);
            entity.updateEntityAge();
            assertEquals(enabled ? 110 : 11, entity.totalEntityAge);
            entity.updateEntityAge();
            assertEquals(enabled ? 111 : 12, entity.totalEntityAge);
            assertEquals(MinecraftServer.currentTick, field(entity, Entity.class, "lastTickTime"));

            final Marker overdue = Mockito.spy(marker());
            overdue.setId(1);
            overdue.totalEntityAge = 60;
            Mockito.doNothing().when(overdue).discard(EntityRemoveEvent.Cause.DESPAWN);
            final ChunkEntitySlices slices = new ChunkEntitySlices(overdue.level(), 0, 0, FullChunkStatus.INACCESSIBLE, null, -4, 19);
            assertTrue(slices.addEntity(overdue, 0));
            final PlatformHooks hooks = Mockito.mock(PlatformHooks.class);
            try (final var platform = Mockito.mockStatic(PlatformHooks.class)) {
                platform.when(PlatformHooks::get).thenReturn(hooks);
                slices.unload();
            }
            Mockito.verify(overdue, Mockito.times(enabled ? 1 : 0)).discard(EntityRemoveEvent.Cause.DESPAWN);
            Mockito.verify(hooks, Mockito.times(enabled ? 0 : 1)).unloadEntity(overdue);
        } finally {
            DespawnTime.proactiveWeakLoading = previous;
        }
    }

    private static Marker marker() throws Exception {
        final ServerLevel level = Mockito.mock(ServerLevel.class);
        final PurpurWorldConfig purpur = Mockito.mock(PurpurWorldConfig.class);
        purpur.entitySharedRandom = true;
        purpur.drowningAirTicks = 300;
        setField(level, Level.class, "purpurConfig", purpur);
        setField(level, Level.class, "spigotConfig", Mockito.mock(SpigotWorldConfig.class));
        final WorldConfiguration paper = Mockito.mock(WorldConfiguration.class);
        paper.entities = Mockito.mock(WorldConfiguration.Entities.class);
        paper.entities.spawning = Mockito.mock(WorldConfiguration.Entities.Spawning.class);
        paper.entities.spawning.despawnTime = new Reference2ObjectOpenHashMap<>();
        paper.entities.spawning.despawnTime.put(EntityTypes.MARKER, new IntOr.Disabled(OptionalInt.of(50)));
        Mockito.when(level.paperConfig()).thenReturn(paper);
        return new Marker(EntityTypes.MARKER, level);
    }

    @ParameterizedTest
    @CsvSource({"1, 0, 1", "5, 0, 1", "1, 1, 1", "0, 0, 0"})
    void glidingDamageLeavesTheLastDurabilityPoint(final int perSecond, final double speedMultiplier, final int expectedDamage) throws Exception {
        final ServerLevel level = Mockito.mock(ServerLevel.class);
        final PurpurWorldConfig config = Mockito.mock(PurpurWorldConfig.class);
        config.elytraDamagePerSecond = perSecond;
        config.elytraDamageMultiplyBySpeed = speedMultiplier;
        setField(level, Level.class, "purpurConfig", config);
        final LivingEntity entity = Mockito.mock(LivingEntity.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doReturn(level).when(entity).level();
        Mockito.doReturn(true).when(entity).isFallFlying();
        Mockito.doReturn(true).when(entity).canGlide();
        Mockito.doReturn(new Vec3(3, 0, 0)).when(entity).getDeltaMovement();
        Mockito.doNothing().when(entity).gameEvent(net.minecraft.world.level.gameevent.GameEvent.ELYTRA_GLIDE);
        setField(entity, Entity.class, "random", net.minecraft.util.RandomSource.create(1));
        entity.fallFlyTicks = 19;
        final ItemStack glider = Mockito.spy(new ItemStack(Items.ELYTRA));
        glider.setDamageValue(glider.getMaxDamage() - 2);
        Mockito.doNothing().when(glider).hurtAndBreak(anyInt(), same(entity), any(EquipmentSlot.class));
        Mockito.doReturn(ItemStack.EMPTY).when(entity).getItemBySlot(any());
        Mockito.doReturn(glider).when(entity).getItemBySlot(EquipmentSlot.CHEST);

        entity.updateFallFlying();

        Mockito.verify(glider).hurtAndBreak(expectedDamage, entity, EquipmentSlot.CHEST);
    }

    @Test
    void clearsCollisionScratchListsAfterExceptionsAndNormalReturns() throws Exception {
        final ServerLevel level = Mockito.mock(ServerLevel.class);
        final EntityCollisionCache cache = new EntityCollisionCache();
        setField(level, ServerLevel.class, "entityCollisionCache", cache);
        final Entity entity = Mockito.mock(Entity.class, Mockito.CALLS_REAL_METHODS);
        setField(entity, Entity.class, "level", level);
        final AABB box = new AABB(0, 0, 0, 1, 1, 1);
        Mockito.doReturn(box).when(entity).getBoundingBox();
        final Vec3 movement = new Vec3(1, 0, 0);
        final var collide = Entity.class.getDeclaredMethod("collide", Vec3.class);
        collide.setAccessible(true);
        final RuntimeException failure = new RuntimeException("collision callback");

        try (final var collisions = Mockito.mockStatic(CollisionUtil.class)) {
            collisions.when(() -> CollisionUtil.getEntityHardCollisions(same(level), same(entity), any(AABB.class), same(cache.entityAABBs()), eq(0), isNull()))
                .thenAnswer(invocation -> {
                    assertTrue(cache.entityAABBs().isEmpty());
                    assertTrue(cache.potentialCollisionsBB().isEmpty());
                    assertTrue(cache.potentialCollisionsVoxel().isEmpty());
                    cache.entityAABBs().add(box);
                    cache.potentialCollisionsBB().add(box);
                    cache.potentialCollisionsVoxel().add(Shapes.block());
                    return true;
                });
            collisions.when(() -> CollisionUtil.getCollisionsForBlocksOrWorldBorder(same(level), same(entity), any(AABB.class), same(cache.potentialCollisionsVoxel()), same(cache.potentialCollisionsBB()), anyInt(), isNull()))
                .thenThrow(failure).thenReturn(false);
            collisions.when(() -> CollisionUtil.performCollisions(same(movement), same(box), same(cache.potentialCollisionsVoxel()), same(cache.potentialCollisionsBB())))
                .thenReturn(movement);

            assertSame(failure, assertThrows(InvocationTargetException.class, () -> collide.invoke(entity, movement)).getCause());
            assertTrue(cache.entityAABBs().isEmpty());
            assertTrue(cache.potentialCollisionsBB().isEmpty());
            assertTrue(cache.potentialCollisionsVoxel().isEmpty());
            assertSame(movement, collide.invoke(entity, movement));
            assertTrue(cache.entityAABBs().isEmpty());
            assertTrue(cache.potentialCollisionsBB().isEmpty());
            assertTrue(cache.potentialCollisionsVoxel().isEmpty());
        }
    }

    private static Object field(final Object target, final Class<?> owner, final String name) throws Exception {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(final Object target, final Class<?> owner, final String name, final Object value) throws Exception {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
