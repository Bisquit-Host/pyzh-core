package pyzh.harness;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

/** Block placement helpers. Everything is placed without physics unless stated, so builds are order-independent. */
final class Build {
    final World world;
    private final Map<String, BlockData> cache = new HashMap<>();

    Build(World world) {
        this.world = world;
    }

    Block at(int x, int y, int z) {
        return world.getBlockAt(x, y, z);
    }

    void set(int x, int y, int z, Material material) {
        world.getBlockAt(x, y, z).setType(material, false);
    }

    /** Places a block state such as "repeater[facing=east,delay=2]". */
    void set(int x, int y, int z, String state) {
        set(x, y, z, state, false);
    }

    void set(int x, int y, int z, String state, boolean physics) {
        world.getBlockAt(x, y, z).setBlockData(cache.computeIfAbsent(state, s -> Bukkit.createBlockData("minecraft:" + s)), physics);
    }

    void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material material) {
        for (int y = y1; y <= y2; y++) {
            for (int z = z1; z <= z2; z++) {
                for (int x = x1; x <= x2; x++) {
                    world.getBlockAt(x, y, z).setType(material, false);
                }
            }
        }
    }

    Block container(int x, int y, int z, String state, Material item, int stacks) {
        set(x, y, z, state);
        Block block = at(x, y, z);
        Container container = (Container) block.getState(false);
        for (int i = 0; i < stacks; i++) {
            container.getInventory().setItem(i, new ItemStack(item, item.getMaxStackSize()));
        }
        return block;
    }

    /** Loads the chunks covering a block rectangle and keeps them loaded while the build is in progress. */
    void load(int x1, int z1, int x2, int z2) {
        for (int cx = x1 >> 4; cx <= x2 >> 4; cx++) {
            for (int cz = z1 >> 4; cz <= z2 >> 4; cz++) {
                world.getChunkAt(cx, cz);
            }
        }
    }
}
