package pyzh.harness;

import org.bukkit.GameMode;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.util.BoundingBox;

/** Survival players spread over natural terrain at night: natural spawning, despawning, random ticks, ambient mob AI. */
final class IdleSurvival extends Scenario {
    private static final int CENTER_X = 500, CENTER_Z = 2500;
    private static final int COLUMNS = 5, ROWS = 4, SPACING = 160, PATROL = 28;
    private static final int HALF_X = (COLUMNS - 1) * SPACING / 2, HALF_Z = (ROWS - 1) * SPACING / 2;

    private Counters counters;

    @Override
    String name() {
        return "idle-survival";
    }

    @Override
    Region region() {
        int margin = VIEW_MARGIN + PATROL + 32;
        return new Region((CENTER_X - HALF_X - margin) >> 4, (CENTER_Z - HALF_Z - margin) >> 4,
            (CENTER_X + HALF_X + margin) >> 4, (CENTER_Z + HALF_Z + margin) >> 4);
    }

    @Override
    int warmupTicks(boolean quick) {
        return quick ? 1500 : 3000;
    }

    @Override
    int measureTicks(boolean quick) {
        return quick ? 2000 : 6000;
    }

    @Override
    boolean naturalSpawning() {
        return true;
    }

    @Override
    void setup(Context ctx) {
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                int index = row * COLUMNS + column;
                int x = CENTER_X - HALF_X + column * SPACING;
                int z = CENTER_Z - HALF_Z + row * SPACING;
                int y = ctx.world.getHighestBlockYAt(x, z) + 1;
                Bots.Bot bot = ctx.bots.join("idle" + index, ctx.world, x + 0.5, y, z + 0.5, GameMode.SURVIVAL);
                if (index % 4 == 0) {
                    double homeX = bot.homeX, homeZ = bot.homeZ;
                    int phase = index * 37;
                    bot.mover = new Movers.Walker((tick, xz) -> {
                        boolean away = (tick + phase) / 300 % 2 == 1;
                        xz[0] = homeX + (away ? PATROL : 0);
                        xz[1] = homeZ;
                    }, 0.153, index % 8 == 0 ? 1 : -1);
                }
            }
        }
        counters = Counters.register(ctx);
    }

    @Override
    void digest(Context ctx, Digest digest) {
        int margin = VIEW_MARGIN + PATROL;
        digest.entityCounts("entities", ctx.world, new BoundingBox(CENTER_X - HALF_X - margin, -64, CENTER_Z - HALF_Z - margin,
            CENTER_X + HALF_X + margin, 320, CENTER_Z + HALF_Z + margin));
        digest.stat("natural_spawns", counters.spawned(CreatureSpawnEvent.SpawnReason.NATURAL));
        digest.stat("deaths", counters.died());
        digest.exact.put("bots", ctx.bots.all.size());
    }
}
