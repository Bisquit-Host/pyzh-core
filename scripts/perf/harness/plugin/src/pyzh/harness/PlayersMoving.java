package pyzh.harness;

import java.util.SplittableRandom;
import org.bukkit.GameMode;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.util.BoundingBox;

/**
 * A crowd of 100 survival players walking a circuit over natural terrain: every player tracks every other,
 * move packets run the real movement checks, and the shared view area keeps loading and unloading chunks.
 */
final class PlayersMoving extends Scenario {
    private static final int X = -3500, Z = 500, PLAYERS = 100;
    private static final double CIRCUIT_RADIUS = 96, CROWD_RADIUS = 20, CROWD_SPEED = 0.2, WALK_SPEED = 0.26;

    private Counters counters;

    @Override
    String name() {
        return "players-moving";
    }

    @Override
    Region region() {
        return Region.around(X, Z, (int) (CIRCUIT_RADIUS + CROWD_RADIUS) + VIEW_MARGIN + 48);
    }

    @Override
    int warmupTicks(boolean quick) {
        return quick ? 800 : 1500;
    }

    @Override
    int measureTicks(boolean quick) {
        return quick ? 1200 : 3000;
    }

    @Override
    boolean naturalSpawning() {
        return true;
    }

    @Override
    void setup(Context ctx) {
        SplittableRandom random = ctx.random(7);
        for (int i = 0; i < PLAYERS; i++) {
            double radius = CROWD_RADIUS * Math.sqrt(random.nextDouble());
            double angle = random.nextDouble() * 2 * Math.PI;
            double offsetX = radius * Math.cos(angle), offsetZ = radius * Math.sin(angle);
            Movers.Goal goal = (tick, xz) -> {
                double phase = tick * CROWD_SPEED / CIRCUIT_RADIUS;
                xz[0] = X + CIRCUIT_RADIUS * Math.cos(phase) + offsetX;
                xz[1] = Z + CIRCUIT_RADIUS * Math.sin(phase) + offsetZ;
            };
            double[] xz = new double[2];
            goal.at(0, xz);
            int y = ctx.world.getHighestBlockYAt((int) Math.floor(xz[0]), (int) Math.floor(xz[1])) + 1;
            ctx.bots.join("walker" + i, ctx.world, xz[0], y, xz[1], GameMode.SURVIVAL).mover = new Movers.Walker(goal, WALK_SPEED, i % 2 == 0 ? 1 : -1);
        }
        counters = Counters.register(ctx);
    }

    @Override
    void digest(Context ctx, Digest digest) {
        int reach = (int) (CIRCUIT_RADIUS + CROWD_RADIUS) + VIEW_MARGIN;
        digest.exact.put("bots", ctx.bots.all.size());
        digest.entityCounts("entities", ctx.world, new BoundingBox(X - reach, -64, Z - reach, X + reach, 320, Z + reach));
        digest.stat("natural_spawns", counters.spawned(CreatureSpawnEvent.SpawnReason.NATURAL));
        digest.stat("deaths", counters.died());
    }
}
