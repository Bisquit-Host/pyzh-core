package pyzh.harness;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class Movers {
    private Movers() {
    }

    interface Goal {
        void at(int tick, double[] xz);
    }

    /** Constant-velocity flight for creative bots. */
    record Flyer(double vx, double vz) implements Bots.Mover {
        @Override
        public boolean step(Bots.Bot bot, ServerLevel level, int tick) {
            bot.nx = bot.x + vx;
            bot.ny = bot.y;
            bot.nz = bot.z + vz;
            bot.nyaw = (float) Math.toDegrees(Math.atan2(-vx, vz));
            bot.nOnGround = false;
            return true;
        }
    }

    /**
     * Walks towards a moving goal over real terrain the way a client would: positions are resolved against block
     * collisions (step up to 1.25, fall up to 4, float in water) so the server's movement checks accept them.
     * A bot that cannot make progress is relocated to the surface at its goal.
     *
     * The server rejects a move whose box overlaps a full block it did not overlap before, compared without any
     * epsilon, so the walker resolves collisions with a box CLEARANCE larger than the player's on every side.
     * CLEARANCE stays below the collision epsilon (1e-7): a box overlapping by more than that is not stopped.
     * Sweeps do use the epsilon, so a box hanging less than that over an edge falls beside the block and ends
     * up overlapping it; the final position is therefore re-checked with the server's own collision test.
     */
    static final class Walker implements Bots.Mover {
        private static final double CLEARANCE = 2.0e-8;
        private static final double HALF_WIDTH = 0.6F / 2.0F + CLEARANCE;
        private static final double HEIGHT = 1.8F + CLEARANCE;
        private static final double JUMP = 1.25;
        private static final double MAX_FALL = 4.0;

        private final Goal goal;
        private final double speed;
        private final int side;
        private final double[] xz = new double[2];
        private int blockedTicks;
        private int seenRejected;
        private int detourTicks;
        private double detourX, detourZ;

        Walker(Goal goal, double speed, int side) {
            this.goal = goal;
            this.speed = speed;
            this.side = side;
        }

        @Override
        public boolean step(Bots.Bot bot, ServerLevel level, int tick) {
            goal.at(tick, xz);
            double dx = xz[0] - bot.x;
            double dz = xz[1] - bot.z;
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (bot.rejected != seenRejected) {
                seenRejected = bot.rejected;
                blockedTicks += 8;
            }
            if (distance > 40 || blockedTicks > 40) {
                blockedTicks = 0;
                detourTicks = 0;
                bot.teleport(xz[0], level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(xz[0]), Mth.floor(xz[1])), xz[1]);
                return false;
            }
            if (distance < 0.75) {
                return false;
            }
            double hx = dx / distance;
            double hz = dz / distance;
            if (detourTicks > 0) {
                detourTicks--;
                if (tryMove(bot, level, detourX, detourZ)) {
                    return true;
                }
                detourTicks = 0;
            }
            if (tryMove(bot, level, hx, hz)) {
                blockedTicks = 0;
                return true;
            }
            for (int i = 1; i <= 3; i++) {
                for (int k = 0; k < 2; k++) {
                    double angle = (k == 0 ? side : -side) * i * Math.PI / 4;
                    double rx = hx * Math.cos(angle) - hz * Math.sin(angle);
                    double rz = hx * Math.sin(angle) + hz * Math.cos(angle);
                    if (tryMove(bot, level, rx, rz)) {
                        detourX = rx;
                        detourZ = rz;
                        detourTicks = 12;
                        blockedTicks++;
                        return true;
                    }
                }
            }
            blockedTicks += 4;
            return false;
        }

        private boolean tryMove(Bots.Bot bot, ServerLevel level, double hx, double hz) {
            Entity player = bot.player();
            AABB box = new AABB(bot.x - HALF_WIDTH, bot.y - CLEARANCE, bot.z - HALF_WIDTH, bot.x + HALF_WIDTH, bot.y + HEIGHT, bot.z + HALF_WIDTH);
            Vec3 want = new Vec3(hx * speed, 0, hz * speed);
            Vec3 got = Entity.collideBoundingBox(player, want, box, level, List.of());
            double up = 0;
            if (blocked(want, got)) {
                up = Entity.collideBoundingBox(player, new Vec3(0, JUMP, 0), box, level, List.of()).y;
                if (up < 0.5) {
                    return false;
                }
                box = box.move(0, up, 0);
                got = Entity.collideBoundingBox(player, want, box, level, List.of());
                if (blocked(want, got)) {
                    return false;
                }
            }
            box = box.move(got);
            double fall = Entity.collideBoundingBox(player, new Vec3(0, -(up + MAX_FALL), 0), box, level, List.of()).y;
            boolean floor = fall > -(up + MAX_FALL) + 1.0e-7;
            double x = (box.minX + box.maxX) / 2;
            double z = (box.minZ + box.maxZ) / 2;
            double y = box.minY + fall + CLEARANCE;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            boolean floating = false;
            for (int i = 0; i < 8 && !level.getFluidState(pos.set(x, y + 1.0, z)).isEmpty(); i++) {
                y += 1.0;
                floating = true;
            }
            if (!floor && level.getFluidState(pos.set(x, y, z)).isEmpty()) {
                return false;
            }
            if (!level.noCollision(player, player.getDimensions(Pose.STANDING).makeBoundingBox(x, y, z))) {
                return false;
            }
            bot.nx = x;
            bot.ny = y;
            bot.nz = z;
            bot.nyaw = (float) Math.toDegrees(Math.atan2(-hx, hz));
            bot.nOnGround = floor && !floating;
            return true;
        }

        private static boolean blocked(Vec3 want, Vec3 got) {
            return Math.abs(want.x - got.x) > 1.0e-7 || Math.abs(want.z - got.z) > 1.0e-7;
        }
    }
}
