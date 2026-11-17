package pyzh.harness;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.type.Piston;

/**
 * Three kinds of running circuits, all started at fixed run ticks so their phase is tick-exact:
 * 16 repeater-ring clocks (32 gt) each driving 12 sticky pistons and a dropper pulse counter, 16 comparator
 * subtraction clocks feeding dust lines, and 32 observer clocks (2 gt) each driving a piston, dust and a lamp.
 */
final class Redstone extends Scenario {
    private static final int X = -4000, Z = -4500, Y = 200;
    private static final int RINGS = 16, COMPARATORS = 16, OBSERVERS = 32;
    private static final int RING_X = X - 20, COMPARATOR_X = X, OBSERVER_X = X + 12;
    private static final int ROW_Z = Z - 40, OBSERVER_Z = Z - 46;

    private final List<Block> counters = new ArrayList<>();
    private long comparatorPower, observerPistons, ringPistons, lamps;

    @Override
    String name() {
        return "redstone";
    }

    @Override
    Region region() {
        return Region.around(X, Z, VIEW_MARGIN + 32);
    }

    @Override
    int warmupTicks(boolean quick) {
        return quick ? 2000 : 4000;
    }

    @Override
    int measureTicks(boolean quick) {
        return quick ? 4000 : 12000;
    }

    @Override
    void setup(Context ctx) {
        Build b = ctx.build;
        b.load(X - 32, Z - 50, X + 32, Z + 50);
        b.fill(X - 26, Y, Z - 48, X + 26, Y, Z + 48, Material.STONE);

        for (int i = 0; i < RINGS; i++) {
            int x = RING_X, z = ROW_Z + 5 * i;
            for (int n = 3; n <= 14; n++) {
                b.set(x + n, Y - 1, z, "sticky_piston[facing=down]");
                b.set(x + n, Y - 2, z, Material.STONE);
            }
            counters.add(b.container(x + 15, Y, z, "chest", Material.COBBLESTONE, 0));
            b.container(x + 15, Y + 1, z, "dropper[facing=down]", Material.COBBLESTONE, 9);
            b.set(x + 1, Y + 1, z, "repeater[facing=west,delay=4]", true);
            b.set(x + 2, Y + 1, z + 1, "repeater[facing=north,delay=4]", true);
            b.set(x + 1, Y + 1, z + 2, "repeater[facing=east,delay=4]", true);
            b.set(x, Y + 1, z + 1, "repeater[facing=south,delay=4]", true);
            for (int[] corner : new int[][] {{0, 0}, {2, 0}, {2, 2}, {0, 2}}) {
                b.set(x + corner[0], Y + 1, z + corner[1], "redstone_wire", true);
            }
            for (int n = 3; n <= 14; n++) {
                b.set(x + n, Y + 1, z, "redstone_wire", true);
            }
        }

        for (int i = 0; i < COMPARATORS; i++) {
            int x = COMPARATOR_X, z = ROW_Z + 5 * i;
            b.set(x + 1, Y + 1, z, "comparator[facing=west,mode=subtract]", true);
            b.set(x + 2, Y + 1, z + 1, "redstone_wire", true);
            b.set(x + 1, Y + 1, z + 1, "redstone_wire", true);
            for (int n = 2; n <= 8; n++) {
                b.set(x + n, Y + 1, z, "redstone_wire", true);
            }
        }

        for (int i = 0; i < OBSERVERS; i++) {
            int x = OBSERVER_X, z = OBSERVER_Z + 3 * i;
            b.set(x - 1, Y + 1, z, "piston[facing=west]");
            b.set(x, Y + 1, z, "observer[facing=east]");
            b.set(x + 10, Y + 1, z, "redstone_lamp");
            for (int n = 2; n <= 9; n++) {
                b.set(x + n, Y + 1, z, "redstone_wire", true);
            }
        }

        ctx.bots.join("engineer0", ctx.world, X + 9.5, Y + 1, Z + 0.5, GameMode.SURVIVAL);
    }

    @Override
    void start(Context ctx) {
        Build b = ctx.build;
        for (int i = 0; i < COMPARATORS; i++) {
            b.set(COMPARATOR_X, Y + 1, ROW_Z + 5 * i, "redstone_block", true);
        }
        for (int i = 0; i < OBSERVERS; i++) {
            b.set(OBSERVER_X + 1, Y + 1, OBSERVER_Z + 3 * i, "observer[facing=west]", true);
        }
    }

    /**
     * Ring i is kicked at tick 2i by a redstone block that stays 16 gt, giving a 16 gt pulse on a 32 gt loop with
     * the rings out of phase. Circuit state is sampled on a period coprime to every clock so the sums show each
     * circuit kind is actually switching.
     */
    @Override
    void tick(Context ctx, int tick) {
        Build b = ctx.build;
        if (tick % 2 == 0 && tick < 2 * RINGS + 16) {
            if (tick < 2 * RINGS) {
                b.set(RING_X - 1, Y + 1, ROW_Z + 5 * (tick / 2), "redstone_block", true);
            }
            if (tick >= 16) {
                b.set(RING_X - 1, Y + 1, ROW_Z + 5 * (tick / 2 - 8), "air", true);
            }
        }
        if (tick % 97 == 0) {
            for (int i = 0; i < COMPARATORS; i++) {
                comparatorPower += ((AnaloguePowerable) b.at(COMPARATOR_X + 2, Y + 1, ROW_Z + 5 * i).getBlockData()).getPower();
            }
            for (int i = 0; i < OBSERVERS; i++) {
                int z = OBSERVER_Z + 3 * i;
                observerPistons += b.at(OBSERVER_X - 1, Y + 1, z).getBlockData() instanceof Piston piston && piston.isExtended() ? 1 : 0;
                lamps += ((Lightable) b.at(OBSERVER_X + 10, Y + 1, z).getBlockData()).isLit() ? 1 : 0;
            }
            for (int i = 0; i < RINGS; i++) {
                ringPistons += b.at(RING_X + 3, Y - 1, ROW_Z + 5 * i).getBlockData() instanceof Piston piston && piston.isExtended() ? 1 : 0;
            }
        }
    }

    @Override
    void digest(Context ctx, Digest digest) {
        digest.exact.put("ring_pulses", Digest.sum(Digest.itemTotals(counters)));
        digest.exact.put("sampled_comparator_power", comparatorPower);
        digest.exact.put("sampled_observer_pistons_extended", observerPistons);
        digest.exact.put("sampled_ring_pistons_extended", ringPistons);
        digest.exact.put("sampled_lamps_lit", lamps);
        digest.exact.put("state_crc", Digest.blockChecksum(ctx.world, X - 26, Y - 3, Z - 48, X + 26, Y + 1, Z + 48));
    }
}
