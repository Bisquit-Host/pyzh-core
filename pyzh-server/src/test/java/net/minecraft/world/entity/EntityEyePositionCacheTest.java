package net.minecraft.world.entity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@LeafTest
class EntityEyePositionCacheTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void updatesSunBurnEyeBlockAfterDimensionsChangeWithoutMovement(final boolean refresh) throws Exception {
        final Entity entity = Mockito.mock(Entity.class, Mockito.CALLS_REAL_METHODS);
        final Level level = Mockito.mock(Level.class);
        final EnvironmentAttributeSystem attributes = Mockito.mock(EnvironmentAttributeSystem.class);
        final Vec3 position = new Vec3(0.5, 64, 0.5);
        setField(entity, "position", position);
        setField(entity, "level", level);
        setField(entity, "bb", new AABB(0, 64, 0, 1, 66, 1));
        entity.firstTick = true;
        Mockito.when(level.environmentAttributes()).thenReturn(attributes);
        Mockito.when(attributes.getValue(eq(EnvironmentAttributes.MONSTERS_BURN), any(Vec3.class))).thenReturn(true);
        Mockito.doReturn(Pose.STANDING).when(entity).getPose();

        final List<BlockPos> sampledPositions = new ArrayList<>();
        Mockito.doAnswer(invocation -> {
            sampledPositions.add(invocation.getArgument(0));
            return 0.0F; // Stops before random burning and sky checks.
        }).when(entity).getLightLevelDependentMagicValue(any(BlockPos.class));

        Mockito.doReturn(EntityDimensions.scalable(0.6F, 2.0F).withEyeHeight(1.6F)).when(entity).getDimensions(Pose.STANDING);
        entity.fixupDimensions();
        entity.isSunBurnTick();

        Mockito.doReturn(EntityDimensions.scalable(0.6F, 1.0F).withEyeHeight(0.6F)).when(entity).getDimensions(Pose.STANDING);
        if (refresh) {
            entity.refreshDimensions();
        } else {
            entity.fixupDimensions();
        }
        entity.isSunBurnTick();

        assertSame(position, entity.position());
        assertEquals(List.of(new BlockPos(0, 65, 0), new BlockPos(0, 64, 0)), sampledPositions);
    }

    private static void setField(final Entity entity, final String name, final Object value) throws Exception {
        final Field field = Entity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(entity, value);
    }
}
