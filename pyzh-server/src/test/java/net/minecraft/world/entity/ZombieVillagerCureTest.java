package net.minecraft.world.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.config.modules.fixes.MCBugFix;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.*;

@LeafTest
class ZombieVillagerCureTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void curedBabyChickenRidersOnlyDismountWhenEnabled(boolean enabled) throws Exception {
        boolean previous = MCBugFix.mc200418;
        try {
            MCBugFix.mc200418 = enabled;
            ZombieVillager zombie = Mockito.mock(ZombieVillager.class);
            ServerLevel level = Mockito.mock(ServerLevel.class);
            Villager villager = Mockito.mock(Villager.class);
            Mockito.when(zombie.isSilent()).thenReturn(true);
            Mockito.when(villager.isBaby()).thenReturn(true);
            Mockito.when(villager.isPassenger()).thenReturn(true);
            Mockito.when(villager.getVehicle()).thenReturn(Mockito.mock(Chicken.class));
            Mockito.doAnswer(invocation -> {
                ConversionParams.AfterConversion<Villager> afterConversion = invocation.getArgument(2);
                afterConversion.finalizeConversion(villager);
                return villager;
            }).when(zombie).convertTo(eq(EntityTypes.VILLAGER), any(ConversionParams.class),
                org.mockito.ArgumentMatchers.<ConversionParams.AfterConversion<Villager>>any(),
                eq(EntityTransformEvent.TransformReason.CURED), eq(CreatureSpawnEvent.SpawnReason.CURED));
            var finishConversion = ZombieVillager.class.getDeclaredMethod("finishConversion", ServerLevel.class);
            finishConversion.setAccessible(true);

            finishConversion.invoke(zombie, level);
            Mockito.verify(villager, Mockito.times(enabled ? 1 : 0)).removeVehicle();
        } finally {
            MCBugFix.mc200418 = previous;
        }
    }
}
