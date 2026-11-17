package pyzh.harness;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

/**
 * Storage-system load: sixteen 32-hopper chains moving items chest to chest, a 512-hopper open collection
 * floor, and sixteen dropper lines (5 items/s each, observer-clocked) spitting items onto stepped water streams
 * that end over collection hoppers. Source chests are filled on the first run tick so flows are tick-exact.
 */
final class ItemsHoppers extends Scenario {
    private static final int X = 2000, Z = -4500, Y = 200;
    private static final int LINES = 16, CHAIN = 32, CHAIN_Z = Z - 44, STREAM_X = X - 10, STREAM_Z = Z - 10;
    private static final Material[] STREAM_ITEMS = {Material.COBBLESTONE, Material.ROTTEN_FLESH, Material.BONE, Material.GUNPOWDER,
        Material.STRING, Material.ARROW, Material.IRON_INGOT, Material.WHEAT};

    private final List<Block> chainSources = new ArrayList<>();
    private final List<Block> chainSinks = new ArrayList<>();
    private final List<Block> streamSources = new ArrayList<>();
    private final List<Block> streamSinks = new ArrayList<>();

    @Override
    String name() {
        return "items-hoppers";
    }

    @Override
    Region region() {
        return Region.around(X, Z, VIEW_MARGIN + 32);
    }

    @Override
    int warmupTicks(boolean quick) {
        return quick ? 2000 : 3000;
    }

    /** Source chests hold 1728 items and drain one per 8 ticks, so a run must stay under 13800 ticks. */
    @Override
    int measureTicks(boolean quick) {
        return quick ? 4000 : 9000;
    }

    @Override
    void setup(Context ctx) {
        Build b = ctx.build;
        b.load(X - 32, Z - 48, X + 32, Z + 48);
        b.fill(X - 30, Y, Z - 46, X + 30, Y, Z + 46, Material.STONE);

        for (int i = 0; i < LINES; i++) {
            int z = CHAIN_Z + 2 * i;
            for (int n = 0; n < CHAIN; n++) {
                b.set(X - 16 + n, Y + 1, z, "hopper[facing=east]");
            }
            b.set(X - 16, Y + 2, z, "chest");
            b.set(X + 16, Y + 1, z, "chest");
            chainSources.add(b.at(X - 16, Y + 2, z));
            chainSinks.add(b.at(X + 16, Y + 1, z));
        }

        for (int x = X - 16; x <= X + 15; x++) {
            for (int z = Z + 26; z <= Z + 41; z++) {
                b.set(x, Y, z, "hopper[facing=down]");
            }
        }

        for (int i = 0; i < LINES; i++) {
            int z = STREAM_Z + 2 * i;
            for (int step = 0; step < 3; step++) {
                int floor = Y + 2 - step, x1 = STREAM_X + 8 * step, x2 = x1 + 7;
                b.fill(x1, Y + 1, z, x2, floor, z, Material.STONE);
                b.fill(x1, floor + 1, z - 1, x2, floor + 2, z - 1, Material.GLASS);
                b.fill(x1, floor + 1, z + 1, x2, floor + 2, z + 1, Material.GLASS);
            }
            b.set(STREAM_X + 24, Y + 1, z, Material.GLASS);
            b.set(STREAM_X + 24, Y + 2, z, Material.GLASS);
            b.set(STREAM_X - 1, Y + 1, z, Material.GLASS);
            b.set(STREAM_X - 1, Y + 2, z, "observer[facing=up]");
            b.set(STREAM_X - 1, Y + 4, z, "dropper[facing=east]");
            b.set(STREAM_X - 1, Y + 5, z, "hopper[facing=down]");
            b.set(STREAM_X - 2, Y + 4, z, "hopper[facing=east]");
            b.set(STREAM_X - 1, Y + 6, z, "chest");
            b.set(STREAM_X - 2, Y + 5, z, "chest");
            streamSources.add(b.at(STREAM_X - 1, Y + 6, z));
            streamSources.add(b.at(STREAM_X - 2, Y + 5, z));
            for (int n = 22; n <= 23; n++) {
                b.set(STREAM_X + n, Y, z, "hopper[facing=down]");
                b.set(STREAM_X + n, Y - 1, z, "chest");
                streamSinks.add(b.at(STREAM_X + n, Y - 1, z));
            }
        }
        for (int i = 0; i < LINES; i++) {
            for (int step = 0; step < 3; step++) {
                b.set(STREAM_X + 8 * step, Y + 3 - step, STREAM_Z + 2 * i, "water[level=0]", true);
            }
        }

        b.set(X, Y + 6, Z + 5, Material.STONE);
        ctx.bots.join("sorter0", ctx.world, X + 0.5, Y + 7, Z + 5.5, GameMode.SURVIVAL);
    }

    /** Fills the sources and completes each vertical observer pair, which starts a 2-tick clock under its dropper. */
    @Override
    void start(Context ctx) {
        chainSources.forEach(block -> fill(block, Material.COBBLESTONE));
        for (int i = 0; i < streamSources.size(); i++) {
            fill(streamSources.get(i), STREAM_ITEMS[i / 2 % STREAM_ITEMS.length]);
        }
        for (int i = 0; i < LINES; i++) {
            ctx.build.set(STREAM_X - 1, Y + 3, STREAM_Z + 2 * i, "observer[facing=down]", true);
        }
    }

    private static void fill(Block chest, Material item) {
        Container container = (Container) chest.getState(false);
        for (int slot = 0; slot < container.getInventory().getSize(); slot++) {
            container.getInventory().setItem(slot, new ItemStack(item, item.getMaxStackSize()));
        }
    }

    @Override
    void digest(Context ctx, Digest digest) {
        digest.exact.put("chain_sink_items", Digest.sum(Digest.itemTotals(chainSinks)));
        digest.exact.put("chain_source_items", Digest.sum(Digest.itemTotals(chainSources)));
        digest.stat("stream_sink_items", Digest.sum(Digest.itemTotals(streamSinks)));
        digest.stat("stream_source_items", Digest.sum(Digest.itemTotals(streamSources)));
        digest.entityCounts("entities", ctx.world, new BoundingBox(X - 32, Y - 4, Z - 48, X + 32, Y + 12, Z + 48));
    }
}
