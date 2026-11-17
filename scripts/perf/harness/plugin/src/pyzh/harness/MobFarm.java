package pyzh.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Zombie;
import org.bukkit.util.BoundingBox;

/**
 * A grinder as seen from its AFK spot: 180 hostile mobs held in a pit under the player (targeting, failed
 * pathfinding, crowding), four pens of 75 animals, a drop shaft that kills a steady feed of hostiles onto a
 * hopper, and a one-block cow crusher relying on entity cramming. Skeletons only come through the shaft:
 * penned with other mobs they shoot each other and the population collapses. Spiders only live in the pit:
 * in the shaft they climb down the walls, survive and pile up.
 */
final class MobFarm extends Scenario {
    private static final int X = 1000, Z = 4000, Y = 200;
    private static final int SHAFT_X = X + 18, SHAFT_Z = Z + 18, SHAFT_TOP = Y + 29;
    private static final int CRUSHER_X = X - 18, CRUSHER_Z = Z + 18;
    private static final List<Class<? extends LivingEntity>> FEED = List.of(Zombie.class, Skeleton.class, Creeper.class);

    private final List<Block> shaftChest = new ArrayList<>();
    private final List<Block> crusherChest = new ArrayList<>();
    private Counters counters;
    private int fedHostiles, fedCows;

    @Override
    String name() {
        return "mob-farm";
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
        return quick ? 3000 : 9000;
    }

    @Override
    void setup(Context ctx) {
        Build b = ctx.build;
        b.load(X - 32, Z - 32, X + 32, Z + 32);
        b.fill(X - 30, Y, Z - 30, X + 30, Y, Z + 30, Material.STONE);

        b.fill(X - 11, Y - 5, Z - 11, X + 10, Y, Z + 10, Material.STONE);
        b.fill(X - 10, Y - 4, Z - 10, X + 9, Y, Z + 9, Material.AIR);
        b.fill(X, Y - 4, Z, X, Y, Z, Material.STONE);
        SplittableRandom random = ctx.random(1);
        spawnPit(ctx, random, Zombie.class, 120);
        spawnPit(ctx, random, Creeper.class, 40);
        spawnPit(ctx, random, Spider.class, 20);

        pen(ctx, X - 23, Z - 5, Cow.class);
        pen(ctx, X + 13, Z - 5, Sheep.class);
        pen(ctx, X - 5, Z - 23, Pig.class);
        pen(ctx, X - 5, Z + 13, Chicken.class);

        b.fill(SHAFT_X - 1, Y + 1, SHAFT_Z - 1, SHAFT_X + 1, SHAFT_TOP + 2, SHAFT_Z + 1, Material.GLASS);
        b.fill(SHAFT_X, Y + 1, SHAFT_Z, SHAFT_X, SHAFT_TOP + 2, SHAFT_Z, Material.AIR);
        b.set(SHAFT_X, Y, SHAFT_Z, "hopper[facing=east]");
        b.set(SHAFT_X + 1, Y, SHAFT_Z, "chest");
        shaftChest.add(b.at(SHAFT_X + 1, Y, SHAFT_Z));

        b.fill(CRUSHER_X - 1, Y + 1, CRUSHER_Z - 1, CRUSHER_X + 1, Y + 3, CRUSHER_Z + 1, Material.GLASS);
        b.fill(CRUSHER_X, Y + 1, CRUSHER_Z, CRUSHER_X, Y + 3, CRUSHER_Z, Material.AIR);
        b.set(CRUSHER_X, Y, CRUSHER_Z, "hopper[facing=east]");
        b.set(CRUSHER_X + 1, Y, CRUSHER_Z, "chest");
        crusherChest.add(b.at(CRUSHER_X + 1, Y, CRUSHER_Z));
        for (int i = 0; i < 28; i++) {
            ctx.world.spawn(new Location(ctx.world, CRUSHER_X + 0.5, Y + 1, CRUSHER_Z + 0.5), Cow.class);
        }

        ctx.bots.join("farm0", ctx.world, X + 0.5, Y + 1, Z + 0.5, GameMode.SURVIVAL);
        counters = Counters.register(ctx);
    }

    private void spawnPit(Context ctx, SplittableRandom random, Class<? extends LivingEntity> type, int count) {
        for (int i = 0; i < count; i++) {
            int x, z;
            do {
                x = X - 10 + random.nextInt(20);
                z = Z - 10 + random.nextInt(20);
            } while (Math.abs(x - X) <= 1 && Math.abs(z - Z) <= 1);
            ctx.world.spawn(new Location(ctx.world, x + 0.5, Y - 4, z + 0.5), type);
        }
    }

    /** 10x10 grass pen with two-high glass walls and 75 animals. */
    private void pen(Context ctx, int x, int z, Class<? extends LivingEntity> type) {
        Build b = ctx.build;
        b.fill(x - 1, Y + 1, z - 1, x + 10, Y + 2, z + 10, Material.GLASS);
        b.fill(x, Y + 1, z, x + 9, Y + 2, z + 9, Material.AIR);
        b.fill(x, Y, z, x + 9, Y, z + 9, Material.GRASS_BLOCK);
        for (int i = 0; i < 75; i++) {
            ctx.world.spawn(new Location(ctx.world, x + 0.5 + i % 10, Y + 1, z + 0.5 + i / 10), type);
        }
    }

    @Override
    void tick(Context ctx, int tick) {
        if (tick % 10 == 0) {
            ctx.world.spawn(new Location(ctx.world, SHAFT_X + 0.5, SHAFT_TOP, SHAFT_Z + 0.5), FEED.get(tick / 10 % FEED.size()));
            fedHostiles++;
        }
        if (tick % 40 == 0) {
            ctx.world.spawn(new Location(ctx.world, CRUSHER_X + 0.5, Y + 1, CRUSHER_Z + 0.5), Cow.class);
            fedCows++;
        }
    }

    @Override
    void digest(Context ctx, Digest digest) {
        digest.exact.put("fed_hostiles", fedHostiles);
        digest.exact.put("fed_cows", fedCows);
        digest.entityCounts("entities", ctx.world, new BoundingBox(X - 32, Y - 8, Z - 32, X + 32, Y + 40, Z + 32));
        digest.stat("deaths", counters.died());
        for (Map.Entry<String, Integer> entry : counters.deaths.entrySet()) {
            digest.stat("deaths." + entry.getKey(), entry.getValue());
        }
        digest.itemTotalsStat("shaft_chest", shaftChest);
        digest.itemTotalsStat("crusher_chest", crusherChest);
    }
}
