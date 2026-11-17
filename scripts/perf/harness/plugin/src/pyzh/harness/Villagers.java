package pyzh.harness;

import java.util.List;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

/**
 * Two trading-hall corridors (120 villagers boxed in 1x1 cells facing job sites, no reachable beds) next to two
 * breeder rooms (12 fed villagers, 24 beds and a bell each, plus a caged zombie in view that keeps part of them
 * panicking so golems spawn). The clock runs through work, meeting, idle and rest hours.
 */
final class Villagers extends Scenario {
    private static final int X = -1500, Z = -4500, Y = 200;
    private static final int CELLS = 30, HALL_X = X - 30;
    private static final List<String> JOB_SITES = List.of("lectern", "composter", "smithing_table", "fletching_table", "cartography_table",
        "brewing_stand", "blast_furnace", "smoker", "barrel", "loom", "grindstone", "stonecutter", "cauldron");
    private static final BoundingBox BOX = new BoundingBox(X - 36, Y - 4, Z - 16, X + 36, Y + 16, Z + 26);

    private Counters counters;
    private int placed;

    @Override
    String name() {
        return "villagers";
    }

    @Override
    Region region() {
        return Region.around(X, Z, VIEW_MARGIN + 32);
    }

    @Override
    int warmupTicks(boolean quick) {
        return quick ? 1500 : 3000;
    }

    @Override
    int measureTicks(boolean quick) {
        return quick ? 4000 : 15000;
    }

    /** Full: measured 4000-19000 (work, meet, idle, rest). Quick: measured 8500-12500. */
    @Override
    long dayTime(boolean quick) {
        return quick ? 7000 : 1000;
    }

    @Override
    void setup(Context ctx) {
        Build b = ctx.build;
        b.load(X - 36, Z - 16, X + 36, Z + 26);
        b.fill(X - 34, Y, Z - 14, X + 34, Y, Z + 24, Material.SMOOTH_STONE);
        hall(ctx, Z - 8);
        hall(ctx, Z);
        breeder(ctx, X - 20, Z + 8);
        breeder(ctx, X + 6, Z + 8);
        ctx.bots.join("trader0", ctx.world, X + 0.5, Y + 1, Z + 0.5, GameMode.SURVIVAL);
        counters = Counters.register(ctx);
    }

    /** A corridor along x with a row of cells on each side; every cell has its job site between it and the corridor. */
    private void hall(Context ctx, int corridorZ) {
        Build b = ctx.build;
        for (int side = -1; side <= 1; side += 2) {
            int cellZ = corridorZ + 2 * side, siteZ = corridorZ + side, backZ = corridorZ + 3 * side;
            b.fill(HALL_X - 1, Y + 1, Math.min(siteZ, backZ), HALL_X + 2 * CELLS - 1, Y + 2, Math.max(siteZ, backZ), Material.GLASS);
            for (int i = 0; i < CELLS; i++) {
                int x = HALL_X + 2 * i;
                b.fill(x, Y + 1, cellZ, x, Y + 2, cellZ, Material.AIR);
                b.set(x, Y + 1, siteZ, JOB_SITES.get((i + (side + 1) * 3) % JOB_SITES.size()));
                villager(ctx, x + 0.5, cellZ + 0.5, false);
            }
        }
    }

    private void breeder(Context ctx, int roomX, int roomZ) {
        Build b = ctx.build;
        b.fill(roomX - 1, Y + 1, roomZ - 1, roomX + 12, Y + 3, roomZ + 12, Material.STONE);
        b.fill(roomX, Y + 1, roomZ, roomX + 11, Y + 3, roomZ + 11, Material.AIR);
        for (int i = 0; i < 12; i++) {
            b.set(roomX + i, Y + 1, roomZ, "red_bed[facing=north,part=head]");
            b.set(roomX + i, Y + 1, roomZ + 1, "red_bed[facing=north,part=foot]");
            b.set(roomX + i, Y + 1, roomZ + 11, "red_bed[facing=south,part=head]");
            b.set(roomX + i, Y + 1, roomZ + 10, "red_bed[facing=south,part=foot]");
        }
        b.set(roomX + 6, Y + 1, roomZ + 5, "bell[attachment=floor]");
        for (int i = 0; i < 4; i++) {
            b.set(roomX + 1 + 3 * i, Y + 1, roomZ + 7, "composter");
        }
        for (int i = 0; i < 12; i++) {
            villager(ctx, roomX + 0.5 + i, roomZ + 3.5 + i % 2, true);
        }

        int cageX = roomX + 13;
        b.fill(cageX - 1, Y + 1, roomZ + 3, cageX + 1, Y + 3, roomZ + 7, Material.STONE);
        b.fill(cageX, Y + 1, roomZ + 4, cageX, Y + 2, roomZ + 6, Material.AIR);
        b.fill(cageX - 1, Y + 2, roomZ + 4, cageX - 1, Y + 2, roomZ + 6, Material.AIR);
        ctx.world.spawn(new Location(ctx.world, cageX + 0.5, Y + 1, roomZ + 5.5), Zombie.class, false, zombie -> {
            zombie.setRemoveWhenFarAway(false);
            zombie.setCanPickupItems(false);
        });
    }

    private void villager(Context ctx, double x, double z, boolean breeder) {
        placed++;
        ctx.world.spawn(new Location(ctx.world, x, Y + 1, z), Villager.class, villager -> {
            if (breeder) {
                villager.getInventory().addItem(new ItemStack(Material.BREAD, 64), new ItemStack(Material.BREAD, 64));
                villager.setMemory(MemoryKey.LAST_SLEPT, ctx.world.getGameTime());
            }
        });
    }

    @Override
    void digest(Context ctx, Digest digest) {
        int villagers = 0, babies = 0, employed = 0, sleeping = 0;
        for (Entity entity : ctx.world.getNearbyEntities(BOX)) {
            if (entity instanceof Villager villager) {
                villagers++;
                babies += villager.isAdult() ? 0 : 1;
                employed += villager.getProfession() == Villager.Profession.NONE ? 0 : 1;
                sleeping += villager.isSleeping() ? 1 : 0;
            }
        }
        digest.exact.put("villagers_placed", placed);
        digest.stat("villagers", villagers);
        digest.stat("villagers.babies", babies);
        digest.stat("villagers.employed", employed);
        digest.stat("villagers.sleeping", sleeping);
        digest.stat("bred", counters.breeds);
        digest.stat("golems_spawned", counters.spawned(CreatureSpawnEvent.SpawnReason.VILLAGE_DEFENSE));
        digest.stat("deaths", counters.died());
        digest.entityCounts("entities", ctx.world, BOX);
    }
}
