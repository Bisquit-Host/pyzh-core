package net.minecraft.world.level;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import ca.spottedleaf.moonrise.common.misc.NearbyPlayers;
import io.papermc.paper.configuration.WorldConfiguration;
import io.papermc.paper.configuration.type.number.IntOr;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import java.util.function.Consumer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.util.CraftSpawnCategory;
import org.bukkit.entity.SpawnCategory;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.config.modules.opt.ThrottleNaturalMobSpawning;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;

@LeafTest
class SpawnBookkeepingDifferentialTest {
    @Test
    void customCategoryIteratorsCanChangePlayerMembershipBetweenSkippedCategories() throws Exception {
        final boolean enabled = ThrottleNaturalMobSpawning.enabled;
        final long[] attempts = ThrottleNaturalMobSpawning.failedAttempts;
        final int[] chances = ThrottleNaturalMobSpawning.spawnChance;
        try (final var spawner = Mockito.mockStatic(NaturalSpawner.class, Mockito.CALLS_REAL_METHODS)) {
            final List<Decision> decisions = new ArrayList<>();
            spawner.when(() -> NaturalSpawner.spawnCategoryForChunk(any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenAnswer(call -> {
                    decisions.add(new Decision(((LevelChunk)call.getArgument(2)).getPos(), call.getArgument(0), call.getArgument(5), call.getArgument(6) != null));
                    return 0;
                });
            List<Decision> expected = null;
            for (int pass = 0; pass < 2; pass++) {
                final Fixture fixture = new Fixture(621L);
                ThrottleNaturalMobSpawning.enabled = false;
                fixture.level.paperConfig().entities.spawning.perPlayerMobSpawns = true;
                fixture.level.paperConfig().entities.spawning.spawningThrottle.failedAttemptsThreshold = IntOr.Disabled.DISABLED;
                Mockito.when(fixture.level.getWorld().getSpawnLimit(SpawnCategory.ANIMAL)).thenReturn(10);
                final ServerPlayer player = Mockito.mock(ServerPlayer.class);
                setField(player, ServerPlayer.class, "mobCounts", new int[ServerPlayer.MOBCATEGORY_TOTAL_ENUMS]);
                setField(player, ServerPlayer.class, "mobBackoffCounts", new int[ServerPlayer.MOBCATEGORY_TOTAL_ENUMS]);
                final var inRange = new ReferenceList<>(new ServerPlayer[0]);
                inRange.add(player);
                final LevelChunk chunk = fixture.chunks.getFirst();
                final List<MobCategory> categories = new java.util.AbstractList<>() {
                    @Override
                    public MobCategory get(final int index) {
                        Mockito.when(fixture.level.moonrise$getNearbyPlayers().getPlayers(chunk.getPos(), NearbyPlayers.NearbyMapType.TICK_VIEW_DISTANCE))
                            .thenReturn(index == 0 ? null : inRange);
                        return index == 0 ? MobCategory.MONSTER : MobCategory.CREATURE;
                    }
                    @Override
                    public int size() { return 2; }
                };
                decisions.clear();
                if (pass == 0) {
                    oldSpawnForChunk(fixture.level, chunk, fixture.state, categories);
                    expected = List.copyOf(decisions);
                    assertEquals(List.of(new Decision(chunk.getPos(), MobCategory.CREATURE, 10, true)), expected);
                } else {
                    NaturalSpawner.spawnForChunk(fixture.level, chunk, fixture.state, categories);
                    assertEquals(expected, decisions);
                }
            }
        } finally {
            ThrottleNaturalMobSpawning.enabled = enabled;
            ThrottleNaturalMobSpawning.failedAttempts = attempts;
            ThrottleNaturalMobSpawning.spawnChance = chances;
        }
    }

    @Test
    void matchesOldDecisionsRandomCallsAndCountersWithLiveChanges() throws Exception {
        final boolean enabled = ThrottleNaturalMobSpawning.enabled;
        final long[] attempts = ThrottleNaturalMobSpawning.failedAttempts;
        final int[] chances = ThrottleNaturalMobSpawning.spawnChance;
        try (final var spawner = Mockito.mockStatic(NaturalSpawner.class, Mockito.CALLS_REAL_METHODS)) {
            final List<Decision> decisions = new ArrayList<>();
            spawner.when(() -> NaturalSpawner.spawnCategoryForChunk(any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenAnswer(call -> {
                    final MobCategory category = call.getArgument(0);
                    final ServerLevel level = call.getArgument(1);
                    final LevelChunk chunk = call.getArgument(2);
                    final int maxSpawns = call.getArgument(5);
                    final Consumer<Entity> tracker = call.getArgument(6);
                    decisions.add(new Decision(chunk.getPos(), category, maxSpawns, tracker != null));
                    final int spawned = Math.min(maxSpawns, level.random.nextInt(4));
                    final var players = level.moonrise$getNearbyPlayers().getPlayers(chunk.getPos(), NearbyPlayers.NearbyMapType.TICK_VIEW_DISTANCE);
                    if (players != null) {
                        for (int i = 0; i < players.size(); i++) {
                            players.getRawDataUnchecked()[i].mobCounts[category.ordinal()] += spawned;
                            players.getRawDataUnchecked()[i].mobBackoffCounts[(category.ordinal() + 1) % ServerPlayer.MOBCATEGORY_TOTAL_ENUMS]++;
                        }
                    }
                    // Model settings changed by a spawn listener before the next category/chunk.
                    level.ticksPerSpawnCategory.put(CraftSpawnCategory.toBukkit(category), 0L);
                    final MobCategory next = MobCategory.values()[(category.ordinal() + 1) % MobCategory.values().length];
                    Mockito.when(level.getWorld().getSpawnLimit(CraftSpawnCategory.toBukkit(next))).thenReturn(spawned + 2);
                    if (players != null && players.size() > 0) {
                        final var replacement = new ReferenceList<>(new ServerPlayer[0]);
                        for (int i = 1; i < players.size(); i++) replacement.add(players.getRawDataUnchecked()[i]);
                        Mockito.when(level.moonrise$getNearbyPlayers().getPlayers(chunk.getPos(), NearbyPlayers.NearbyMapType.TICK_VIEW_DISTANCE))
                            .thenReturn(replacement);
                    }
                    return spawned;
                });
            for (int trial = 0; trial < 120; trial++) {
                final long seed = 0x91b38a7L + trial;
                final Fixture old = new Fixture(seed);
                decisions.clear();
                for (final LevelChunk chunk : old.chunks) oldSpawnForChunk(old.level, chunk, old.state, old.categories);
                final List<Decision> expected = List.copyOf(decisions);
                final long nextRandom = old.level.random.nextLong();
                final List<String> counts = old.counts();

                final Fixture current = new Fixture(seed);
                decisions.clear();
                for (final LevelChunk chunk : current.chunks) NaturalSpawner.spawnForChunk(current.level, chunk, current.state, current.categories);
                assertEquals(expected, decisions, "spawn decisions, trial " + trial);
                assertEquals(nextRandom, current.level.random.nextLong(), "RNG state, trial " + trial);
                assertEquals(counts, current.counts(), "player counts and failed attempts, trial " + trial);
            }
        } finally {
            ThrottleNaturalMobSpawning.enabled = enabled;
            ThrottleNaturalMobSpawning.failedAttempts = attempts;
            ThrottleNaturalMobSpawning.spawnChance = chances;
        }
    }

    private record Decision(ChunkPos chunk, MobCategory category, int maxSpawns, boolean track) {}

    private static final class Fixture {
        private final ServerLevel level = Mockito.mock(ServerLevel.class);
        private final List<LevelChunk> chunks = new ArrayList<>();
        private final List<ServerPlayer> players = new ArrayList<>();
        private final List<MobCategory> categories = new ArrayList<>(List.of(NaturalSpawner.SPAWNING_CATEGORIES));
        private final NaturalSpawner.SpawnState state;

        private Fixture(final long seed) throws Exception {
            final Random random = new Random(seed);
            final var config = Mockito.mock(WorldConfiguration.class);
            config.entities = Mockito.mock(WorldConfiguration.Entities.class);
            config.entities.spawning = Mockito.mock(WorldConfiguration.Entities.Spawning.class);
            config.entities.spawning.perPlayerMobSpawns = random.nextBoolean();
            final var throttle = Mockito.mock(WorldConfiguration.Entities.Spawning.SpawningThrottle.class);
            config.entities.spawning.spawningThrottle = throttle;
            throttle.failedAttemptsThreshold = new IntOr.Disabled(random.nextBoolean() ? OptionalInt.of(random.nextInt(4)) : OptionalInt.empty());
            throttle.throttledTicksPerSpawn = new Reference2IntOpenHashMap<>();
            Mockito.when(level.paperConfig()).thenReturn(config);
            setField(level, Level.class, "random", RandomSource.create(seed));
            final var rates = new Object2LongOpenHashMap<SpawnCategory>();
            setField(level, Level.class, "ticksPerSpawnCategory", rates);
            Mockito.when(level.getGameTime()).thenReturn((long)random.nextInt(800));
            final CraftWorld world = Mockito.mock(CraftWorld.class);
            Mockito.when(level.getWorld()).thenReturn(world);
            for (final MobCategory category : MobCategory.values()) {
                final SpawnCategory bukkit = CraftSpawnCategory.toBukkit(category);
                rates.put(bukkit, new long[]{0, 1, 2, 3, 400}[random.nextInt(5)]);
                Mockito.when(world.getSpawnLimit(bukkit)).thenReturn(random.nextInt(90));
                throttle.throttledTicksPerSpawn.put(category, random.nextInt(6) + 1);
            }
            ThrottleNaturalMobSpawning.enabled = random.nextBoolean();
            ThrottleNaturalMobSpawning.failedAttempts = new long[ServerPlayer.MOBCATEGORY_TOTAL_ENUMS];
            ThrottleNaturalMobSpawning.spawnChance = new int[ServerPlayer.MOBCATEGORY_TOTAL_ENUMS];
            for (int i = 0; i < ServerPlayer.MOBCATEGORY_TOTAL_ENUMS; i++) {
                ThrottleNaturalMobSpawning.failedAttempts[i] = random.nextInt(5) - 1;
                ThrottleNaturalMobSpawning.spawnChance[i] = random.nextBoolean() ? Integer.MAX_VALUE : Integer.MAX_VALUE / 2;
            }
            final ServerChunkCache source = Mockito.mock(ServerChunkCache.class);
            final ChunkMap map = Mockito.mock(ChunkMap.class);
            setField(map, ChunkMap.class, "spawnMobTracker", (Consumer<Entity>)map::updatePlayerMobTypeMap);
            setField(source, ServerChunkCache.class, "chunkMap", map);
            Mockito.when(level.getChunkSource()).thenReturn(source);
            Mockito.when(source.getGenerator()).thenReturn(Mockito.mock(ChunkGenerator.class));
            Mockito.when(map.getMobCountNear(any(), any())).thenAnswer(call -> {
                final ServerPlayer player = call.getArgument(0);
                final int index = ((MobCategory)call.getArgument(1)).ordinal();
                return player.mobCounts[index] + player.mobBackoffCounts[index];
            });
            final NearbyPlayers nearby = Mockito.mock(NearbyPlayers.class);
            Mockito.when(level.moonrise$getNearbyPlayers()).thenReturn(nearby);
            final LocalMobCapCalculator caps = Mockito.mock(LocalMobCapCalculator.class);
            Mockito.when(caps.canSpawn(any(), any())).thenAnswer(call -> ((MobCategory)call.getArgument(0)).ordinal() % 2 == 0);
            state = NaturalSpawner.createState(289, List.of(), (key, output) -> {}, caps);
            for (int i = 0, size = random.nextInt(25); i < size; i++) {
                final ServerPlayer player = Mockito.mock(ServerPlayer.class);
                final int[] mobCounts = new int[ServerPlayer.MOBCATEGORY_TOTAL_ENUMS];
                final int[] backoff = new int[mobCounts.length];
                for (int j = 0; j < mobCounts.length; j++) {
                    mobCounts[j] = random.nextInt(100);
                    backoff[j] = random.nextInt(4);
                    if (random.nextInt(50) == 0) mobCounts[j] = Integer.MAX_VALUE;
                }
                setField(player, ServerPlayer.class, "mobCounts", mobCounts);
                setField(player, ServerPlayer.class, "mobBackoffCounts", backoff);
                players.add(player);
            }
            java.util.Collections.shuffle(categories, random);
            for (int i = 0; i < 7; i++) {
                final LevelChunk chunk = Mockito.mock(LevelChunk.class);
                final ChunkPos pos = new ChunkPos(random.nextInt(40) - 20, random.nextInt(40) - 20);
                Mockito.when(chunk.getPos()).thenReturn(pos);
                final long[] failures = new long[ServerPlayer.MOBCATEGORY_TOTAL_ENUMS];
                for (int j = 0; j < failures.length; j++) failures[j] = random.nextInt(8);
                setField(chunk, ChunkAccess.class, "failedSpawnAttempts", failures);
                final ReferenceList<ServerPlayer> inRange = new ReferenceList<>(new ServerPlayer[0]);
                for (final ServerPlayer player : players) if (random.nextBoolean()) inRange.add(player);
                Mockito.when(nearby.getPlayers(pos, NearbyPlayers.NearbyMapType.TICK_VIEW_DISTANCE))
                    .thenReturn(inRange.size() == 0 && random.nextBoolean() ? null : inRange);
                chunks.add(chunk);
            }
        }

        private List<String> counts() {
            final List<String> result = new ArrayList<>();
            for (final ServerPlayer player : players) {
                result.add(Arrays.toString(player.mobCounts));
                result.add(Arrays.toString(player.mobBackoffCounts));
            }
            for (final LevelChunk chunk : chunks) result.add(Arrays.toString(chunk.failedSpawnAttempts));
            return result;
        }
    }

    // The baseline bookkeeping, including its minimum-over-all-players rule and throttle RNG order.
    private static void oldSpawnForChunk(final ServerLevel level, final LevelChunk chunk, final NaturalSpawner.SpawnState state,
                                         final List<MobCategory> categories) {
        final ChunkGenerator generator = level.getChunkSource().getGenerator();
        for (final MobCategory category : categories) {
            if (ThrottleNaturalMobSpawning.enabled) {
                final int chance = ThrottleNaturalMobSpawning.spawnChance[category.ordinal()];
                final long failed = ThrottleNaturalMobSpawning.failedAttempts[category.ordinal()];
                if (failed >= 0 && chunk.failedSpawnAttempts[category.ordinal()] >= failed
                    && (level.random.nextInt() & Integer.MAX_VALUE) >= chance) continue;
            }
            boolean canSpawn;
            int maxSpawns = Integer.MAX_VALUE;
            if (level.paperConfig().entities.spawning.perPlayerMobSpawns) {
                int limit = category.getMaxInstancesPerChunk();
                final SpawnCategory bukkit = CraftSpawnCategory.toBukkit(category);
                boolean spawnThisTick = true;
                long ticks = level.ticksPerSpawnCategory.getLong(bukkit);
                final long originalTicks = ticks;
                final var throttle = level.paperConfig().entities.spawning.spawningThrottle;
                final OptionalInt threshold = throttle.failedAttemptsThreshold.value();
                if (threshold.isPresent() && chunk.failedSpawnAttempts[category.ordinal()] > threshold.getAsInt()) {
                    ticks = Math.max(ticks, throttle.throttledTicksPerSpawn.getOrDefault(category, -1));
                }
                if (CraftSpawnCategory.isValidForLimits(bukkit)) {
                    spawnThisTick = originalTicks != 0 && level.getGameTime() % ticks == 0;
                    limit = level.getWorld().getSpawnLimit(bukkit);
                }
                if (!throttle.failedAttemptsThreshold.enabled() || spawnThisTick) {
                    int minDiff = Integer.MAX_VALUE;
                    final var inRange = level.moonrise$getNearbyPlayers().getPlayers(chunk.getPos(), NearbyPlayers.NearbyMapType.TICK_VIEW_DISTANCE);
                    if (inRange != null) {
                        final ServerPlayer[] raw = inRange.getRawDataUnchecked();
                        for (int i = 0; i < inRange.size(); i++) minDiff = Math.min(limit - level.getChunkSource().chunkMap.getMobCountNear(raw[i], category), minDiff);
                    }
                    maxSpawns = minDiff == Integer.MAX_VALUE ? 0 : minDiff;
                    canSpawn = maxSpawns > 0;
                } else canSpawn = false;
            } else {
                canSpawn = category.ordinal() % 2 == 0;
            }
            if (canSpawn) {
                final int spawned = NaturalSpawner.spawnCategoryForChunk(category, level, chunk, (type, pos, access) -> true,
                    (mob, access) -> {}, maxSpawns,
                    level.paperConfig().entities.spawning.perPlayerMobSpawns ? level.getChunkSource().chunkMap::updatePlayerMobTypeMap : null, generator);
                if (NaturalSpawner.usesFailedSpawnAttempts(level)) {
                    if (spawned == 0) chunk.failedSpawnAttempts[category.ordinal()]++;
                    else chunk.failedSpawnAttempts[category.ordinal()] = 0;
                }
            }
        }
    }

    private static void setField(final Object target, final Class<?> owner, final String name, final Object value) throws Exception {
        final var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
