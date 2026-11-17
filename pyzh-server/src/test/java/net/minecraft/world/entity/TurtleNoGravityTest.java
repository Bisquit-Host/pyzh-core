package net.minecraft.world.entity;

import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.animal.turtle.Turtle;
import net.minecraft.world.phys.Vec3;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.config.modules.fixes.MCBugFix;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

@LeafTest
class TurtleNoGravityTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void verticalSwimmingOutsideWaterOnlyStopsWhenEnabled(boolean enabled) throws Exception {
        boolean previous = MCBugFix.mc299642;
        try {
            MCBugFix.mc299642 = enabled;
            Turtle turtle = Mockito.mock(Turtle.class);
            Mockito.when(turtle.getNavigation()).thenReturn(Mockito.mock(PathNavigation.class));
            Mockito.when(turtle.isNoGravity()).thenReturn(true);
            Mockito.when(turtle.getSpeed()).thenReturn(1.0F);
            Mockito.when(turtle.getAttributeValue(Attributes.MOVEMENT_SPEED)).thenReturn(1.0);
            Mockito.when(turtle.getDeltaMovement()).thenReturn(Vec3.ZERO);
            var type = Class.forName("net.minecraft.world.entity.animal.turtle.Turtle$TurtleMoveControl");
            var constructor = type.getDeclaredConstructor(Turtle.class);
            constructor.setAccessible(true);
            MoveControl<?> control = (MoveControl<?>) constructor.newInstance(turtle);
            control.setWantedPosition(0, 1, 0, 1);

            control.tick();
            Mockito.verify(turtle, Mockito.times(enabled ? 0 : 1)).setDeltaMovement(new Vec3(0, 0.1, 0));
            Mockito.verify(turtle).setSpeed(enabled ? 0.0F : 1.0F);
        } finally {
            MCBugFix.mc299642 = previous;
        }
    }
}
