package pyzh.harness;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

/**
 * Behavior outcomes of a run. "exact" fields must be identical between runs of unchanged behavior;
 * "statistical" fields vary run to run and are compared as distributions.
 */
final class Digest {
    final Map<String, Object> exact = new TreeMap<>();
    final Map<String, Double> statistical = new TreeMap<>();
    /** Harness health indicators, not compared. */
    final Map<String, Object> info = new TreeMap<>();

    void stat(String key, double value) {
        statistical.put(key, value);
    }

    void entityCounts(String prefix, World world, BoundingBox box) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Entity entity : world.getNearbyEntities(box)) {
            counts.merge(entity.getType().getKey().getKey(), 1, Integer::sum);
        }
        int total = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            statistical.put(prefix + "." + entry.getKey(), entry.getValue().doubleValue());
            total += entry.getValue();
        }
        statistical.put(prefix + ".total", (double) total);
    }

    static Map<String, Integer> itemTotals(Collection<Block> containers) {
        Map<String, Integer> totals = new TreeMap<>();
        for (Block block : containers) {
            if (block.getState(false) instanceof Container container) {
                for (ItemStack stack : container.getInventory().getContents()) {
                    if (stack != null && !stack.isEmpty()) {
                        totals.merge(stack.getType().getKey().getKey(), stack.getAmount(), Integer::sum);
                    }
                }
            }
        }
        return totals;
    }

    static int sum(Map<String, Integer> totals) {
        return totals.values().stream().mapToInt(Integer::intValue).sum();
    }

    void itemTotalsStat(String prefix, Collection<Block> containers) {
        Map<String, Integer> totals = itemTotals(containers);
        totals.forEach((item, count) -> statistical.put(prefix + "." + item, count.doubleValue()));
        statistical.put(prefix + ".total", (double) sum(totals));
    }

    static long blockChecksum(World world, int x1, int y1, int z1, int x2, int y2, int z2) {
        CRC32 crc = new CRC32();
        for (int y = y1; y <= y2; y++) {
            for (int z = z1; z <= z2; z++) {
                for (int x = x1; x <= x2; x++) {
                    crc.update(world.getBlockAt(x, y, z).getBlockData().getAsString().getBytes());
                }
            }
        }
        return crc.getValue();
    }
}
