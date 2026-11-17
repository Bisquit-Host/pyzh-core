package net.minecraft.server.network;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Random;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import org.bukkit.Location;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;

@LeafTest
class MoveEventPositionDifferentialTest {
    @Test
    void preservesEventThresholdsHistoryAndActivityWithoutListeners() throws Exception {
        final ServerPlayer player = Mockito.mock(ServerPlayer.class, Mockito.withSettings().stubOnly());
        final ServerGamePacketListenerImpl listener = Mockito.mock(ServerGamePacketListenerImpl.class,
            Mockito.withSettings().stubOnly().defaultAnswer(Mockito.CALLS_REAL_METHODS));
        listener.player = player;
        final Method update = ServerGamePacketListenerImpl.class.getDeclaredMethod("updateMoveEventPosition",
            double.class, double.class, double.class, float.class, float.class);
        update.setAccessible(true);
        final String[] names = {"lastPosX", "lastPosY", "lastPosZ", "lastYRot", "lastXRot"};
        final Field[] fields = new Field[names.length];
        for (int i = 0; i < names.length; i++) {
            fields[i] = ServerGamePacketListenerImpl.class.getDeclaredField(names[i]);
            fields[i].setAccessible(true);
        }
        final int[] activityCalls = {0};
        final boolean[] immobile = {false};
        final Location[] snapped = {new Location(null, 0, 0, 0)};
        Mockito.doAnswer(invocation -> { activityCalls[0]++; return null; }).when(player).resetLastActionTime();
        Mockito.doAnswer(invocation -> immobile[0]).when(player).isImmobile();
        Mockito.doAnswer(invocation -> snapped[0].getX()).when(player).getX();
        Mockito.doAnswer(invocation -> snapped[0].getY()).when(player).getY();
        Mockito.doAnswer(invocation -> snapped[0].getZ()).when(player).getZ();
        Mockito.doAnswer(invocation -> snapped[0].getYaw()).when(player).getBukkitYaw();
        Mockito.doAnswer(invocation -> snapped[0].getPitch()).when(player).getXRot();
        final Field justTeleported = ServerGamePacketListenerImpl.class.getDeclaredField("justTeleported");
        justTeleported.setAccessible(true);
        final Random random = new Random(1515);
        Location from = new Location(null, -0.0, 64, 0, -0.0F, 0);
        int expectedActivity = 0;
        for (int step = 0; step < 100_000; step++) {
            if (step > 0 && step % 97 == 0) {
                from = new Location(null, random.nextBoolean() ? (random.nextBoolean() ? -3.0E7 : 3.0E7) : -0.0,
                    random.nextBoolean() ? -2.0E7 : 2.0E7, -0.0, -180, 90);
            }
            fields[0].setDouble(listener, from.getX());
            fields[1].setDouble(listener, from.getY());
            fields[2].setDouble(listener, from.getZ());
            fields[3].setFloat(listener, from.getYaw());
            fields[4].setFloat(listener, from.getPitch());
            final double distance = switch (step % 6) {
                case 0 -> 0.0625;
                case 1 -> Math.nextDown(0.0625);
                case 2 -> Math.nextUp(0.0625);
                case 3 -> 0;
                default -> random.nextDouble() - 0.5;
            };
            final float angle = switch (step % 5) {
                case 0 -> 10.0F;
                case 1 -> Math.nextDown(10.0F);
                case 2 -> Math.nextUp(10.0F);
                default -> 0.0F;
            };
            final Location to = new Location(null, from.getX() + distance, from.getY(),
                from.getZ() + (step % 6 == 3 ? distance : 0), Mth.wrapDegrees(from.getYaw() + angle), from.getPitch());
            immobile[0] = random.nextInt(20) == 0;
            final boolean teleported = random.nextBoolean();
            justTeleported.setBoolean(listener, teleported);
            snapped[0] = from.clone();
            if (step % 3 == 0) snapped[0].setX(Math.nextUp(from.getX()));
            if (step % 7 == 0) snapped[0].setYaw(-from.getYaw());
            if (step % 11 == 0) snapped[0].setPitch(-from.getPitch());
            // The old Location-based branch, with an empty handler list and no AFK callback.
            final double delta = Mth.square(from.getX() - to.getX()) + Mth.square(from.getY() - to.getY()) + Mth.square(from.getZ() - to.getZ());
            final float deltaAngle = Math.abs(from.getYaw() - to.getYaw()) + Math.abs(from.getPitch() - to.getPitch());
            Location expected = from;
            boolean accepted = true;
            if ((delta > 1f / 256 || deltaAngle > 10.0F) && !immobile[0]) {
                expected = to;
                if (to.getBlockX() != from.getBlockX() || to.getBlockY() != from.getBlockY() || to.getBlockZ() != from.getBlockZ()
                    || to.getYaw() != from.getYaw() || to.getPitch() != from.getPitch()) expectedActivity++;
                if (!from.equals(snapped[0]) && teleported) accepted = false;
            }
            assertEquals(accepted, update.invoke(listener, to.getX(), to.getY(), to.getZ(), to.getYaw(), to.getPitch()), "accepted at step " + step);
            assertEquals(teleported && accepted, justTeleported.getBoolean(listener), "teleport flag at step " + step);
            final Location actual = new Location(null, fields[0].getDouble(listener), fields[1].getDouble(listener),
                fields[2].getDouble(listener), fields[3].getFloat(listener), fields[4].getFloat(listener));
            assertEquals(expected, actual, "step " + step);
            assertEquals(expectedActivity, activityCalls[0], "activity at step " + step);
            from = expected;
        }
    }
}
