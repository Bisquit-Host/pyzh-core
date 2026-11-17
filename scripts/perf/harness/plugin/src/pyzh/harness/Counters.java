package pyzh.harness;

import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;

/** Event counters for outcomes that leave no countable world state. Registered only by scenarios that need them. */
final class Counters implements Listener {
    final Map<CreatureSpawnEvent.SpawnReason, Integer> spawns = new EnumMap<>(CreatureSpawnEvent.SpawnReason.class);
    final Map<String, Integer> deaths = new TreeMap<>();
    int breeds;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        spawns.merge(event.getSpawnReason(), 1, Integer::sum);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        deaths.merge(event.getEntityType().getKey().getKey(), 1, Integer::sum);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        breeds++;
    }

    int spawned(CreatureSpawnEvent.SpawnReason reason) {
        return spawns.getOrDefault(reason, 0);
    }

    int died() {
        return deaths.values().stream().mapToInt(Integer::intValue).sum();
    }

    static Counters register(Scenario.Context ctx) {
        Counters counters = new Counters();
        ctx.plugin.getServer().getPluginManager().registerEvents(counters, ctx.plugin);
        return counters;
    }
}
