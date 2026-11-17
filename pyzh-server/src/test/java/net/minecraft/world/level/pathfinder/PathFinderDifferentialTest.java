package net.minecraft.world.level.pathfinder;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.phys.AABB;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.async.path.AsyncPath;
import org.dreeam.leaf.async.path.AsyncPathProcessor;
import org.dreeam.leaf.async.path.NodeEvaluatorGenerator;
import org.dreeam.leaf.config.modules.async.AsyncPathfinding;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.purpurmc.purpur.PurpurWorldConfig;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the pathfinder against the copy of the previous implementation in the reference package over random worlds and
 * requires the same expansion order, the same neighbors with the same costs and the same path.
 */
@LeafTest
class PathFinderDifferentialTest {
    private static final int WORLDS = 150;
    private static final int SEARCHES_PER_WORLD = 6;
    private static final float[][] MOB_SIZES = {{0.6F, 1.95F}, {0.4F, 0.3F}, {0.9F, 1.4F}, {1.4F, 0.9F}, {1.4F, 2.7F}, {1.95F, 2.2F}, {0.7F, 0.6F}, {0.3F, 0.9F}};
    private static final float[] STEP_HEIGHTS = {0.6F, 0.6F, 1.0F, 0.0F, 1.5F, 2.2F};
    private static final int[] FOLLOW_RANGES = {8, 16, 24, 35, 48};
    private static final int[][] HEIGHTS = {{-64, 384, 64}, {-64, 384, 63}, {-64, 384, 71}, {-64, 384, -61}, {-64, 384, 312}, {0, 256, 3}, {0, 256, 128}};
    private static List<BlockState> floors;
    private static List<BlockState> obstacles;
    private static List<BlockState> hazards;

    private enum Kind { WALK, WALK_HOME, FLY, SWIM, SWIM_BREACH, AMPHIBIOUS, FROG }

    @BeforeAll
    static void blocks() {
        floors = List.of(
            Blocks.STONE.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.SOUL_SAND.defaultBlockState(), Blocks.DIRT_PATH.defaultBlockState(),
            Blocks.FARMLAND.defaultBlockState(), Blocks.ICE.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState(), Blocks.GLASS.defaultBlockState(),
            Blocks.STONE_SLAB.defaultBlockState(), Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP), Blocks.OAK_STAIRS.defaultBlockState(),
            Blocks.HOPPER.defaultBlockState(), Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.HALF, Half.TOP), Blocks.HONEY_BLOCK.defaultBlockState(),
            Blocks.POWDER_SNOW.defaultBlockState(), Blocks.SLIME_BLOCK.defaultBlockState(), Blocks.CHEST.defaultBlockState(), Blocks.SCAFFOLDING.defaultBlockState()
        );
        obstacles = List.of(
            Blocks.STONE.defaultBlockState(), Blocks.GLASS.defaultBlockState(), Blocks.OAK_FENCE.defaultBlockState(), Blocks.COBBLESTONE_WALL.defaultBlockState(),
            Blocks.OAK_FENCE_GATE.defaultBlockState(), Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.OPEN, true),
            Blocks.OAK_DOOR.defaultBlockState(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, true), Blocks.IRON_DOOR.defaultBlockState(),
            Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, true), Blocks.OAK_TRAPDOOR.defaultBlockState(),
            Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN, true), Blocks.STONE_SLAB.defaultBlockState(),
            Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP), Blocks.OAK_STAIRS.defaultBlockState(), Blocks.MOSS_CARPET.defaultBlockState(),
            Blocks.SNOW.defaultBlockState(), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 6), Blocks.COBWEB.defaultBlockState(),
            Blocks.RAIL.defaultBlockState(), Blocks.POWERED_RAIL.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState(), Blocks.LADDER.defaultBlockState(),
            Blocks.SCAFFOLDING.defaultBlockState(), Blocks.GLASS_PANE.defaultBlockState(), Blocks.IRON_BARS.defaultBlockState(), Blocks.LANTERN.defaultBlockState(),
            Blocks.BAMBOO.defaultBlockState(), Blocks.ANVIL.defaultBlockState(), Blocks.CAULDRON.defaultBlockState(), Blocks.COCOA.defaultBlockState(),
            Blocks.LILY_PAD.defaultBlockState(), Blocks.BIG_DRIPLEAF.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState(), Blocks.TORCH.defaultBlockState(),
            Blocks.OAK_PRESSURE_PLATE.defaultBlockState(), Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false), Blocks.HONEY_BLOCK.defaultBlockState(),
            Blocks.POWDER_SNOW.defaultBlockState(), Blocks.FLOWER_POT.defaultBlockState()
        );
        hazards = List.of(
            Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.CACTUS.defaultBlockState(), Blocks.SWEET_BERRY_BUSH.defaultBlockState(),
            Blocks.FIRE.defaultBlockState(), Blocks.CAMPFIRE.defaultBlockState(), Blocks.MAGMA_BLOCK.defaultBlockState(), Blocks.WITHER_ROSE.defaultBlockState(),
            Blocks.POINTED_DRIPSTONE.defaultBlockState(), Blocks.LAVA_CAULDRON.defaultBlockState(), Blocks.STONECUTTER.defaultBlockState(),
            Blocks.POWDER_SNOW.defaultBlockState(), Blocks.SOUL_FIRE.defaultBlockState(), Blocks.SOUL_CAMPFIRE.defaultBlockState()
        );
        assertEquals(PathType.FENCE.ordinal(), Blocks.OAK_FENCE.defaultBlockState().pathType, "block tags must be bound for this test");
    }

    @Test
    void randomWorldsGiveTheSameSearchAndPath() throws Exception {
        long expansions = 0;
        int reached = 0;
        int searches = 0;
        for (int seed = 0; seed < WORLDS; seed++) {
            Random random = new Random(seed);
            World world = World.generate(random);
            PathTypeCache levelCache = new PathTypeCache();
            net.minecraft.world.level.pathfinder.reference.PathTypeCache referenceLevelCache = new net.minecraft.world.level.pathfinder.reference.PathTypeCache();
            for (int kindIndex = 0; kindIndex < Kind.values().length; kindIndex++) {
                Kind kind = Kind.values()[(seed + kindIndex) % Kind.values().length];
                if (kindIndex > 1 && random.nextInt(3) != 0) {
                    continue;
                }

                Subject subject = new Subject(world, kind, random);
                for (int search = 0; search < SEARCHES_PER_WORLD; search++) {
                    boolean cached = random.nextInt(4) != 0;
                    Search request = Search.random(world, subject, random);
                    String label = "world " + seed + " " + kind + " search " + search + " " + request + " " + subject;
                    Result expected = subject.runReference(request, cached ? referenceLevelCache : null);
                    Result actual = subject.run(request, cached ? levelCache : null);
                    actual.assertSame(expected, label);
                    expansions += actual.trace.size();
                    reached += actual.reached ? 1 : 0;
                    searches++;
                }
            }
        }

        System.out.println("COVERAGE searches " + searches + " trace entries " + expansions + " reached " + reached);
        assertTrue(expansions > 5_000_000 && reached > 300, "the worlds must exercise real searches: " + expansions + " trace entries, " + reached + " reached");
    }

    @Test
    void datapackHazardTagsAndEveryBuiltInHazardMatchAtSectionBoundaries() throws Exception {
        var holder = Blocks.POLISHED_ANDESITE.builtInRegistryHolder();
        var originalTags = holder.tags().toList();
        var bindTags = net.minecraft.core.Holder.Reference.class.getDeclaredMethod("bindTags", java.util.Collection.class);
        bindTags.setAccessible(true);
        BlockState tagged = Blocks.POLISHED_ANDESITE.defaultBlockState();
        try {
            for (var tag : List.of(BlockTags.FIRE, BlockTags.SPELEOTHEMS)) {
                var tags = new ArrayList<>(originalTags);
                tags.add(tag);
                bindTags.invoke(holder, tags);
                tagged.initPathType();
                assertEquals(tag == BlockTags.FIRE ? PathType.FIRE : PathType.DAMAGE_CAUTIOUS,
                    WalkNodeEvaluator.getPathTypeFromState(tagged));
                List<BlockState> allHazards = new ArrayList<>(hazards);
                allHazards.add(tagged);
                for (BlockState hazard : allHazards) {
                    World world = World.flat(-1, -1, 3, 63);
                    for (int[] offset : new int[][] {{-1, 0, -1}, {0, 0, -1}, {1, -1, 1}, {1, 1, 0}}) {
                        world.set(15 + offset[0], 64 + offset[1], 15 + offset[2], hazard);
                    }
                    for (Kind kind : Kind.values()) {
                        Subject subject = new Subject(world, kind, new Random(31 + kind.ordinal()));
                        subject.place(12.5, 64, 12.5, true);
                        Search request = new Search(setOf(new BlockPos(19, 64, 19)), 24, 0, 1, 24, 512);
                        subject.run(request, new PathTypeCache()).assertSame(
                            subject.runReference(request, new net.minecraft.world.level.pathfinder.reference.PathTypeCache()),
                            tag + " " + hazard + " " + kind);
                    }
                }
            }
        } finally {
            bindTags.invoke(holder, originalTags);
            tagged.initPathType();
        }
    }

    @Test
    void asyncFloorReadsSeeChangesAfterPreparation() {
        World world = World.flat(0, 0, 1, 64);
        Subject subject = new Subject(world, Kind.WALK, new Random(3));
        subject.place(4.5, 65, 4.5, true);
        var region = subject.region(new Search(setOf(new BlockPos(8, 65, 8)), 16, 0, 1, 16, 256));
        WalkNodeEvaluator evaluator = (WalkNodeEvaluator) subject.evaluator;
        evaluator.prepare(region, subject.mob);
        try {
            assertEquals(65.0, evaluator.getFloorLevel(4, 65, 4));
            evaluator.invalidateCache();
            world.set(4, 64, 4, Blocks.STONE_SLAB.defaultBlockState());
            assertEquals(64.5, evaluator.getFloorLevel(4, 65, 4));
            world.set(4, 64, 4, Blocks.AIR.defaultBlockState());
            assertEquals(64.0, evaluator.getFloorLevel(4, 65, 4));
        } finally {
            evaluator.done();
        }
    }

    @Test
    void unloadedReadsKeepTheDiscardedChunkRandomDraws() {
        World world = World.flat(0, 0, 1, 64);
        Subject subject = new Subject(world, Kind.WALK, new Random(3));
        subject.place(0.5, 65, 0.5, true);
        PathNavigationRegion region = subject.region(new Search(setOf(new BlockPos(1, 65, 1)), 8, 0, 1, 24, 64));
        var expected = new ca.spottedleaf.moonrise.common.util.SimpleThreadUnsafeRandom(123);
        for (int x : new int[] {-1, 0, 15, 16, 32}) {
            for (int y : new int[] {-65, -64, 64, 319, 320}) {
                BlockPos pos = new BlockPos(x, y, 0);
                boolean outsideHeight = y < -64 || y >= 320;
                boolean unloaded = x < 0 || x >= 16;
                assertEquals(outsideHeight ? Blocks.AIR.defaultBlockState() : world.block(x, y, 0), region.getBlockState(pos));
                if (!outsideHeight && unloaded) expected.nextInt(100000);
                assertEquals(expected.nextLong(), subject.level.simpleRandom.nextLong());
                assertEquals(net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState(), region.getFluidState(pos));
                if (!outsideHeight && unloaded) expected.nextInt(100000);
                assertEquals(expected.nextLong(), subject.level.simpleRandom.nextLong());
            }
        }
    }

    @Test
    void staleNeighbourCacheEntriesKeepTheirOriginalEffect() {
        World world = World.flat(-1, -1, 3, 64);
        Subject subject = new Subject(world, Kind.WALK, new Random(12));
        subject.place(0.5, 65, 0.5, true);
        TestRegion region = (TestRegion) subject.region(new Search(setOf(new BlockPos(8, 65, 8)), 16, 0, 1, 24, 256));
        for (BlockState hazard : hazards) {
            BlockPos neighbor = new BlockPos(-1, 65, -1);
            world.set(-1, 65, -1, hazard);
            PathTypeCache cache = new PathTypeCache();
            var referenceCache = new net.minecraft.world.level.pathfinder.reference.PathTypeCache();
            cache.getOrCompute(region, neighbor);
            referenceCache.getOrCompute(region, neighbor);
            world.set(-1, 65, -1, Blocks.AIR.defaultBlockState());
            subject.levelCache = cache;
            net.minecraft.world.level.pathfinder.reference.PathfindingContext.levelCache = referenceCache;
            PathType expected = net.minecraft.world.level.pathfinder.reference.WalkNodeEvaluator.checkNeighbourBlocks(
                new net.minecraft.world.level.pathfinder.reference.PathfindingContext(region, subject.mob), 0, 65, 0, PathType.WALKABLE);
            PathType actual = WalkNodeEvaluator.checkNeighbourBlocks(new PathfindingContext(region, subject.mob), 0, 65, 0, PathType.WALKABLE);
            assertEquals(expected, actual, hazard.toString());
        }
    }

    @Test
    void concurrentScratchLeasesStayExclusiveAndReturnedPathsStayUnchanged() throws Exception {
        int threads = 8;
        CountDownLatch acquired = new CountDownLatch(threads);
        try (var executor = Executors.newFixedThreadPool(threads)) {
            List<java.util.concurrent.Future<Node>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int coordinate = i;
                results.add(executor.submit(() -> {
                    PathSearchScratch scratch = PathSearchScratch.acquire();
                    try {
                        scratch.reset();
                        Node node = scratch.node(scratch.cell(coordinate, 64, 0), coordinate, 64, 0);
                        node.g = coordinate;
                        acquired.countDown();
                        assertTrue(acquired.await(10, TimeUnit.SECONDS));
                        for (int x = 0; x < 4096; x++) {
                            int cell = scratch.cell(x, 65, coordinate);
                            scratch.node(cell, x, 65, coordinate).g = coordinate;
                        }
                        assertEquals(coordinate, node.g);
                        return node;
                    } finally {
                        scratch.release();
                    }
                }));
            }
            for (int i = 0; i < threads; i++) {
                assertEquals(i, results.get(i).get().g);
            }
            PathSearchScratch reused = PathSearchScratch.acquire();
            try {
                reused.reset();
                for (int x = 0; x < 4096; x++) {
                    reused.node(reused.cell(x, 64, 0), x, 64, 0).g = -1;
                }
                for (int i = 0; i < threads; i++) {
                    assertEquals(i, results.get(i).get().g);
                }
            } finally {
                reused.release();
            }
        }
    }

    @Test
    void nodesStayKeyedByTheCollidingNodeHash() {
        Random random = new Random(7);
        World world = World.flat(-3, -3, 3, 64);
        Subject subject = new Subject(world, Kind.WALK, random);
        subject.place(-20.5, 65, -20.5, true);
        Search request = new Search(setOf(new BlockPos(-20, 65, -21 + 128)), 200.0F, 0, 1.0F, 16, 256);
        Result expected = subject.runReference(request, null);
        subject.run(request, null).assertSame(expected, "colliding hash");
        assertTrue(expected.reached && expected.pathLength == 2, "the reference walks straight into the node of the target that shares its hash");
    }

    @Test
    void asyncSearchesOnWorkerThreadsMatchTheReference() throws Exception {
        ThreadPoolExecutor previousExecutor = AsyncPathProcessor.PATH_PROCESSING_EXECUTOR;
        int previousThreads = AsyncPathfinding.asyncPathfindingMaxThreads;
        int previousQueueSize = AsyncPathfinding.asyncPathfindingQueueSize;
        try {
            AsyncPathfinding.asyncPathfindingMaxThreads = 4;
            AsyncPathfinding.asyncPathfindingQueueSize = 4096;
            AsyncPathProcessor.PATH_PROCESSING_EXECUTOR = null;
            AsyncPathProcessor.init();
            Random random = new Random(99);
            World world = World.generate(random);
            NodeEvaluatorGenerator generator = features -> {
                WalkNodeEvaluator evaluator = new WalkNodeEvaluator();
                evaluator.setCanPassDoors(features.canPassDoors());
                evaluator.setCanFloat(features.canFloat());
                evaluator.setCanWalkOverFences(features.canWalkOverFences());
                evaluator.setCanOpenDoors(features.canOpenDoors());
                return evaluator;
            };
            List<Subject> subjects = new ArrayList<>();
            List<Search> requests = new ArrayList<>();
            List<Path> pending = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                Subject subject = new Subject(world, Kind.WALK, random);
                subjects.add(subject);
                requests.add(Search.random(world, subject, random));
            }

            for (int i = 0; i < subjects.size(); i++) {
                Subject subject = subjects.get(i);
                Search request = requests.get(i);
                subject.reset();
                subject.levelCache = new PathTypeCache();
                pending.add(new PathFinder(subject.evaluator, request.maxVisitedNodes, generator)
                    .findPath(subject.region(request), subject.mob, request.targets, request.maxPathLength, request.reachRange, request.multiplier));
            }

            for (int i = 0; i < pending.size(); i++) {
                AsyncPath path = (AsyncPath) pending.get(i);
                path.process();
                Result expected = subjects.get(i).runReference(requests.get(i), null);
                assertEquals(expected.path, describe(path), "async search " + i + " " + requests.get(i));
            }
        } finally {
            AsyncPathProcessor.PATH_PROCESSING_EXECUTOR.shutdown();
            assertTrue(AsyncPathProcessor.PATH_PROCESSING_EXECUTOR.awaitTermination(30, TimeUnit.SECONDS));
            AsyncPathProcessor.PATH_PROCESSING_EXECUTOR = previousExecutor;
            AsyncPathfinding.asyncPathfindingMaxThreads = previousThreads;
            AsyncPathfinding.asyncPathfindingQueueSize = previousQueueSize;
        }
    }

    private static Set<BlockPos> setOf(final BlockPos... positions) {
        return new LinkedHashSet<>(List.of(positions));
    }

    private static String describe(final Path path) {
        StringBuilder out = new StringBuilder().append(path.canReach()).append(' ').append(path.getTarget()).append(' ').append(path.getDistToTarget());
        for (int i = 0; i < path.getNodeCount(); i++) {
            Node node = path.getNode(i);
            out.append('\n').append(describe(node.x, node.y, node.z, node.type, node.costMalus, node.walkedDistance, node.g, node.h, node.f, node.closed));
        }

        return out.toString();
    }

    private static String describe(final net.minecraft.world.level.pathfinder.reference.Path path) {
        StringBuilder out = new StringBuilder().append(path.canReach()).append(' ').append(path.getTarget()).append(' ').append(path.getDistToTarget());
        for (int i = 0; i < path.getNodeCount(); i++) {
            net.minecraft.world.level.pathfinder.reference.Node node = path.getNode(i);
            out.append('\n').append(describe(node.x, node.y, node.z, node.type, node.costMalus, node.walkedDistance, node.g, node.h, node.f, node.closed));
        }

        return out.toString();
    }

    private static String describe(
        final int x, final int y, final int z, final PathType type, final float costMalus, final float walkedDistance, final float g, final float h, final float f, final boolean closed
    ) {
        return x + "," + y + "," + z + " " + type + " malus " + costMalus + " walked " + walkedDistance + " g " + g + " h " + h + " f " + f + (closed ? " closed" : "");
    }

    private record Search(Set<BlockPos> targets, float maxPathLength, int reachRange, float multiplier, int regionRadius, int maxVisitedNodes) {
        static Search random(final World world, final Subject subject, final Random random) {
            subject.placeRandomly(random);
            int followRange = FOLLOW_RANGES[random.nextInt(FOLLOW_RANGES.length)];
            Set<BlockPos> targets = new LinkedHashSet<>();
            for (int i = random.nextInt(8) == 0 ? 3 : 1; i > 0; i--) {
                int x = Mth.floor(subject.x) + random.nextInt(2 * followRange + 1) - followRange;
                int z = Mth.floor(subject.z) + random.nextInt(2 * followRange + 1) - followRange;
                targets.add(new BlockPos(x, switch (random.nextInt(6)) {
                    case 0 -> world.surface(x, z) + 4;
                    case 1 -> world.surface(x, z) - 2;
                    default -> world.surface(x, z) + 1;
                }, z));
            }

            float multiplier = switch (random.nextInt(6)) {
                case 0 -> 0.25F;
                case 1 -> 2.0F;
                default -> 1.0F;
            };
            return new Search(targets, followRange, random.nextInt(3), multiplier, followRange + (random.nextBoolean() ? 8 : 16), followRange * 16);
        }
    }

    private record Result(IntArrayList trace, String path, boolean reached, int pathLength, long mobRandomTail, long levelRandomTail) {
        void assertSame(final Result expected, final String label) {
            if (!this.trace.equals(expected.trace)) {
                int at = 0;
                while (at < this.trace.size() && at < expected.trace.size() && this.trace.getInt(at) == expected.trace.getInt(at)) {
                    at++;
                }

                fail(label + ": searches diverge at trace entry " + at + " of " + expected.trace.size() + " (actual " + this.trace.size() + ")");
            }

            assertEquals(expected.path, this.path, label);
            assertEquals(expected.mobRandomTail, this.mobRandomTail, label + " mob RNG");
            assertEquals(expected.levelRandomTail, this.levelRandomTail, label + " level RNG");
        }
    }

    private static final class Subject {
        final World world;
        final Kind kind;
        final ServerLevel level = Mockito.mock(ServerLevel.class, Mockito.withSettings().stubOnly());
        final Mob mob = Mockito.mock(Mob.class, Mockito.withSettings().stubOnly());
        final Map<PathType, Float> malus = new EnumMap<>(PathType.class);
        final Map<PathType, Float> initialMalus = new EnumMap<>(PathType.class);
        final IntArrayList trace = new IntArrayList();
        final NodeEvaluator evaluator;
        final net.minecraft.world.level.pathfinder.reference.NodeEvaluator referenceEvaluator;
        final float width;
        final float height;
        final BlockPos home;
        private PathTypeCache levelCache;
        private RandomSource mobRandom;
        private long randomSeed;
        double x;
        double y;
        double z;
        boolean onGround;

        Subject(final World world, final Kind kind, final Random random) {
            this.world = world;
            this.kind = kind;
            float[] size = MOB_SIZES[random.nextInt(MOB_SIZES.length)];
            this.width = size[0];
            this.height = size[1];
            this.home = new BlockPos(world.centerX(), world.baseY, world.centerZ());
            float stepHeight = STEP_HEIGHTS[random.nextInt(STEP_HEIGHTS.length)];
            int maxFallDistance = random.nextInt(5) == 0 ? 3 + random.nextInt(300) : 3;
            boolean standsOnLava = random.nextInt(8) == 0;
            boolean inWater = random.nextBoolean();
            for (PathType type : new PathType[] {PathType.WATER, PathType.FIRE_IN_NEIGHBOR, PathType.FIRE, PathType.LAVA, PathType.DOOR_OPEN, PathType.WATER_BORDER, PathType.FENCE, PathType.LEAVES, PathType.DAMAGING_IN_NEIGHBOR, PathType.DAMAGE_CAUTIOUS, PathType.TRAPDOOR, PathType.BIG_MOBS_CLOSE_TO_DANGER}) {
                if (random.nextInt(4) == 0) {
                    this.initialMalus.put(type, new float[] {-1.0F, 0.0F, 4.0F, 16.0F}[random.nextInt(4)]);
                }
            }

            PurpurWorldConfig purpurConfig = Mockito.mock(PurpurWorldConfig.class, Mockito.withSettings().stubOnly());
            purpurConfig.mobsIgnoreRails = random.nextInt(4) == 0;
            purpurConfig.stonecutterDamage = random.nextBoolean() ? 1.0F : 0.0F;
            try {
                var field = Level.class.getDeclaredField("purpurConfig");
                field.setAccessible(true);
                field.set(this.level, purpurConfig);
                var randomField = Level.class.getDeclaredField("simpleRandom");
                randomField.setAccessible(true);
                randomField.set(this.level, new ca.spottedleaf.moonrise.common.util.SimpleThreadUnsafeRandom(123));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }

            Mockito.when(this.level.getMinY()).thenReturn(world.minY);
            Mockito.when(this.level.getHeight()).thenReturn(world.height);
            Mockito.when(this.level.getSeaLevel()).thenReturn(world.baseY);
            Mockito.when(this.level.getChunkSource()).thenReturn(Mockito.mock(ServerChunkCache.class, Mockito.withSettings().stubOnly()));
            Mockito.when(this.level.getPathTypeCache()).thenAnswer(invocation -> this.levelCache);
            Mockito.when(this.mob.level()).thenReturn(this.level);
            Mockito.doReturn(EntityTypes.ZOMBIE).when(this.mob).getType();
            Mockito.when(this.mob.getMainHandItem()).thenReturn(ItemStack.EMPTY);
            Mockito.when(this.mob.getItemBySlot(Mockito.any())).thenReturn(ItemStack.EMPTY);
            Mockito.when(this.mob.getX()).thenAnswer(invocation -> this.x);
            Mockito.when(this.mob.getY()).thenAnswer(invocation -> this.y);
            Mockito.when(this.mob.getZ()).thenAnswer(invocation -> this.z);
            Mockito.when(this.mob.getBlockY()).thenAnswer(invocation -> Mth.floor(this.y));
            Mockito.when(this.mob.blockPosition()).thenAnswer(invocation -> BlockPos.containing(this.x, this.y, this.z));
            Mockito.when(this.mob.getBoundingBox())
                .thenAnswer(invocation -> new AABB(this.x - this.width / 2.0F, this.y, this.z - this.width / 2.0F, this.x + this.width / 2.0F, this.y + this.height, this.z + this.width / 2.0F));
            Mockito.when(this.mob.getBbWidth()).thenReturn(this.width);
            Mockito.when(this.mob.getBbHeight()).thenReturn(this.height);
            Mockito.when(this.mob.onGround()).thenAnswer(invocation -> this.onGround);
            Mockito.when(this.mob.isInWater()).thenAnswer(invocation -> inWater && world.block(Mth.floor(this.x), Mth.floor(this.y), Mth.floor(this.z)).getFluidState().is(FluidTags.WATER));
            Mockito.when(this.mob.canStandOnFluid(Mockito.any())).thenAnswer(invocation -> standsOnLava && invocation.<net.minecraft.world.level.material.FluidState>getArgument(0).is(FluidTags.LAVA));
            Mockito.when(this.mob.maxUpStep()).thenReturn(stepHeight);
            Mockito.when(this.mob.getMaxFallDistance()).thenReturn(maxFallDistance);
            Mockito.when(this.mob.getRandom()).thenAnswer(invocation -> this.mobRandom);
            Mockito.when(this.mob.getPathfindingMalus(Mockito.any())).thenAnswer(invocation -> {
                PathType type = invocation.getArgument(0);
                Float value = this.malus.get(type);
                return value == null ? type.getMalus() : value;
            });
            Mockito.doAnswer(invocation -> this.malus.put(invocation.getArgument(0), invocation.getArgument(1))).when(this.mob).setPathfindingMalus(Mockito.any(), Mockito.anyFloat());

            boolean prefersShallowSwimming = random.nextBoolean();
            this.evaluator = switch (kind) {
                case WALK -> new WalkNodeEvaluator() {
                    @Override
                    public int getNeighbors(final Node[] neighbors, final Node pos) {
                        return Subject.this.record(pos, neighbors, super.getNeighbors(neighbors, pos));
                    }
                };
                case WALK_HOME -> new WalkNodeEvaluator() {
                    @Override
                    public int getNeighbors(final Node[] neighbors, final Node pos) {
                        return Subject.this.record(pos, neighbors, super.getNeighbors(neighbors, pos));
                    }

                    @Override
                    public PathType getPathType(final PathfindingContext context, final int x, final int y, final int z) {
                        return Subject.this.outsideHome(x, y, z, context.mobPosition()) ? PathType.BLOCKED : super.getPathType(context, x, y, z);
                    }
                };
                case FLY -> new FlyNodeEvaluator() {
                    @Override
                    public int getNeighbors(final Node[] neighbors, final Node pos) {
                        return Subject.this.record(pos, neighbors, super.getNeighbors(neighbors, pos));
                    }
                };
                case SWIM, SWIM_BREACH -> new SwimNodeEvaluator(kind == Kind.SWIM_BREACH) {
                    @Override
                    public int getNeighbors(final Node[] neighbors, final Node pos) {
                        return Subject.this.record(pos, neighbors, super.getNeighbors(neighbors, pos));
                    }
                };
                case AMPHIBIOUS -> new AmphibiousNodeEvaluator(prefersShallowSwimming) {
                    @Override
                    public int getNeighbors(final Node[] neighbors, final Node pos) {
                        return Subject.this.record(pos, neighbors, super.getNeighbors(neighbors, pos));
                    }
                };
                case FROG -> new AmphibiousNodeEvaluator(true) {
                    @Override
                    public int getNeighbors(final Node[] neighbors, final Node pos) {
                        return Subject.this.record(pos, neighbors, super.getNeighbors(neighbors, pos));
                    }

                    @Override
                    public PathType getPathType(final PathfindingContext context, final int x, final int y, final int z) {
                        return context.getBlockState(new BlockPos(x, y - 1, z)).is(BlockTags.FROG_PREFER_JUMP_TO) ? PathType.OPEN : super.getPathType(context, x, y, z);
                    }
                };
            };
            this.referenceEvaluator = switch (kind) {
                case WALK -> new net.minecraft.world.level.pathfinder.reference.WalkNodeEvaluator() {
                    @Override
                    public int getNeighbors(final net.minecraft.world.level.pathfinder.reference.Node[] neighbors, final net.minecraft.world.level.pathfinder.reference.Node pos) {
                        return Subject.this.record(pos, neighbors, super.getNeighbors(neighbors, pos));
                    }
                };
                case WALK_HOME -> new net.minecraft.world.level.pathfinder.reference.WalkNodeEvaluator() {
                    @Override
                    public int getNeighbors(final net.minecraft.world.level.pathfinder.reference.Node[] neighbors, final net.minecraft.world.level.pathfinder.reference.Node pos) {
                        return Subject.this.record(pos, neighbors, super.getNeighbors(neighbors, pos));
                    }

                    @Override
                    public PathType getPathType(final net.minecraft.world.level.pathfinder.reference.PathfindingContext context, final int x, final int y, final int z) {
                        return Subject.this.outsideHome(x, y, z, context.mobPosition()) ? PathType.BLOCKED : super.getPathType(context, x, y, z);
                    }
                };
                case FLY -> new net.minecraft.world.level.pathfinder.reference.FlyNodeEvaluator() {
                    @Override
                    public int getNeighbors(final net.minecraft.world.level.pathfinder.reference.Node[] neighbors, final net.minecraft.world.level.pathfinder.reference.Node pos) {
                        return Subject.this.record(pos, neighbors, super.getNeighbors(neighbors, pos));
                    }
                };
                case SWIM, SWIM_BREACH -> new net.minecraft.world.level.pathfinder.reference.SwimNodeEvaluator(kind == Kind.SWIM_BREACH) {
                    @Override
                    public int getNeighbors(final net.minecraft.world.level.pathfinder.reference.Node[] neighbors, final net.minecraft.world.level.pathfinder.reference.Node pos) {
                        return Subject.this.record(pos, neighbors, super.getNeighbors(neighbors, pos));
                    }
                };
                case AMPHIBIOUS -> new net.minecraft.world.level.pathfinder.reference.AmphibiousNodeEvaluator(prefersShallowSwimming) {
                    @Override
                    public int getNeighbors(final net.minecraft.world.level.pathfinder.reference.Node[] neighbors, final net.minecraft.world.level.pathfinder.reference.Node pos) {
                        return Subject.this.record(pos, neighbors, super.getNeighbors(neighbors, pos));
                    }
                };
                case FROG -> new net.minecraft.world.level.pathfinder.reference.AmphibiousNodeEvaluator(true) {
                    @Override
                    public int getNeighbors(final net.minecraft.world.level.pathfinder.reference.Node[] neighbors, final net.minecraft.world.level.pathfinder.reference.Node pos) {
                        return Subject.this.record(pos, neighbors, super.getNeighbors(neighbors, pos));
                    }

                    @Override
                    public PathType getPathType(final net.minecraft.world.level.pathfinder.reference.PathfindingContext context, final int x, final int y, final int z) {
                        return context.getBlockState(new BlockPos(x, y - 1, z)).is(BlockTags.FROG_PREFER_JUMP_TO) ? PathType.OPEN : super.getPathType(context, x, y, z);
                    }
                };
            };
            boolean canPassDoors = random.nextInt(4) != 0;
            boolean canOpenDoors = random.nextBoolean();
            boolean canFloat = random.nextBoolean();
            boolean canWalkOverFences = random.nextInt(4) == 0;
            this.evaluator.setCanPassDoors(canPassDoors);
            this.evaluator.setCanOpenDoors(canOpenDoors);
            this.evaluator.setCanFloat(canFloat);
            this.evaluator.setCanWalkOverFences(canWalkOverFences);
            this.referenceEvaluator.setCanPassDoors(canPassDoors);
            this.referenceEvaluator.setCanOpenDoors(canOpenDoors);
            this.referenceEvaluator.setCanFloat(canFloat);
            this.referenceEvaluator.setCanWalkOverFences(canWalkOverFences);
        }

        boolean outsideHome(final int x, final int y, final int z, final BlockPos mobPosition) {
            double distance = this.home.distSqr(new BlockPos(x, y, z));
            return distance > 100.0 && distance >= this.home.distSqr(mobPosition);
        }

        void place(final double x, final double y, final double z, final boolean onGround) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.onGround = onGround;
        }

        void placeRandomly(final Random random) {
            int blockX = this.world.centerX() + random.nextInt(25) - 12;
            int blockZ = this.world.centerZ() + random.nextInt(25) - 12;
            double offsetX = random.nextBoolean() ? 0.5 : random.nextDouble();
            double offsetZ = random.nextBoolean() ? 0.5 : random.nextDouble();
            int surface = this.world.surface(blockX, blockZ);
            switch (random.nextInt(8)) {
                case 0 -> this.place(blockX + offsetX, surface + 1 + random.nextInt(4), blockZ + offsetZ, false);
                case 1 -> this.place(blockX + offsetX, surface - random.nextInt(3), blockZ + offsetZ, random.nextBoolean());
                case 2 -> this.place(blockX + offsetX, surface + 1.5, blockZ + offsetZ, true);
                default -> this.place(blockX + offsetX, surface + 1, blockZ + offsetZ, true);
            }

            this.randomSeed = random.nextLong();
        }

        PathNavigationRegion region(final Search request) {
            BlockPos from = BlockPos.containing(this.x, this.y, this.z);
            int radius = request.regionRadius;
            return new TestRegion(this.level, from.offset(-radius, -radius, -radius), from.offset(radius, radius, radius), this.world);
        }

        private void reset() {
            this.trace.clear();
            this.malus.clear();
            this.malus.putAll(this.initialMalus);
            this.mobRandom = RandomSource.create(this.randomSeed);
            this.level.simpleRandom.setSeed(this.randomSeed);
        }

        Result run(final Search request, final PathTypeCache levelCache) {
            this.reset();
            this.levelCache = levelCache;
            Path path = new PathFinder(this.evaluator, request.maxVisitedNodes)
                .findPath(this.region(request), this.mob, request.targets, request.maxPathLength, request.reachRange, request.multiplier);
            return new Result(new IntArrayList(this.trace), describe(path), path.canReach(), path.getNodeCount(), this.mobRandom.nextLong(), this.level.simpleRandom.nextLong());
        }

        Result runReference(final Search request, final net.minecraft.world.level.pathfinder.reference.PathTypeCache levelCache) {
            this.reset();
            net.minecraft.world.level.pathfinder.reference.PathfindingContext.levelCache = levelCache;
            net.minecraft.world.level.pathfinder.reference.Path path = new net.minecraft.world.level.pathfinder.reference.PathFinder(this.referenceEvaluator, request.maxVisitedNodes)
                .findPath(this.region(request), this.mob, request.targets, request.maxPathLength, request.reachRange, request.multiplier);
            return new Result(new IntArrayList(this.trace), describe(path), path.canReach(), path.getNodeCount(), this.mobRandom.nextLong(), this.level.simpleRandom.nextLong());
        }

        int record(final Node pos, final Node[] neighbors, final int count) {
            this.trace.add(pos.x);
            this.trace.add(pos.y);
            this.trace.add(pos.z);
            this.trace.add(count);
            for (int i = 0; i < count; i++) {
                Node node = neighbors[i];
                this.record(node.x, node.y, node.z, node.type, node.costMalus, node.closed);
            }

            return count;
        }

        int record(final net.minecraft.world.level.pathfinder.reference.Node pos, final net.minecraft.world.level.pathfinder.reference.Node[] neighbors, final int count) {
            this.trace.add(pos.x);
            this.trace.add(pos.y);
            this.trace.add(pos.z);
            this.trace.add(count);
            for (int i = 0; i < count; i++) {
                net.minecraft.world.level.pathfinder.reference.Node node = neighbors[i];
                this.record(node.x, node.y, node.z, node.type, node.costMalus, node.closed);
            }

            return count;
        }

        private void record(final int x, final int y, final int z, final PathType type, final float costMalus, final boolean closed) {
            this.trace.add(x);
            this.trace.add(y);
            this.trace.add(z);
            this.trace.add(type.ordinal() << 1 | (closed ? 1 : 0));
            this.trace.add(Float.floatToIntBits(costMalus));
        }

        @Override
        public String toString() {
            return "mob " + this.width + "x" + this.height + " at " + this.x + "," + this.y + "," + this.z + (this.onGround ? " on ground" : " in air");
        }
    }

    static final class TestRegion extends PathNavigationRegion {
        private final World world;

        TestRegion(final Level level, final BlockPos start, final BlockPos end, final World world) {
            super(level, start, end);
            this.world = world;
            for (int x = 0; x < this.chunks.length; x++) {
                for (int z = 0; z < this.chunks[x].length; z++) {
                    this.chunks[x][z] = world.chunk(this.centerX + x, this.centerZ + z);
                }
            }
        }

        @Override
        public BlockGetter getChunkForCollisions(final int chunkX, final int chunkZ) {
            ProtoChunk chunk = this.world.chunk(chunkX, chunkZ);
            return chunk != null ? chunk : this.world.empty;
        }

        @Override
        public WorldBorder getWorldBorder() {
            return this.world.border;
        }

        @Override
        public int getMinY() {
            return this.world.minY;
        }

        @Override
        public int getHeight() {
            return this.world.height;
        }
    }

    static final class World {
        final int minY;
        final int height;
        final int baseY;
        final int originChunkX;
        final int originChunkZ;
        final ProtoChunk[][] chunks;
        final ProtoChunk empty;
        final WorldBorder border = new WorldBorder();
        private final int[][] surface;

        World(final int originChunkX, final int originChunkZ, final int size, final int minY, final int height, final int baseY) {
            this.minY = minY;
            this.height = height;
            this.baseY = baseY;
            this.originChunkX = originChunkX;
            this.originChunkZ = originChunkZ;
            this.chunks = new ProtoChunk[size][size];
            this.surface = new int[size * 16][size * 16];
            this.empty = this.newChunk(originChunkX - 1, originChunkZ - 1);
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    this.chunks[x][z] = this.newChunk(originChunkX + x, originChunkZ + z);
                }
            }
        }

        private ProtoChunk newChunk(final int x, final int z) {
            return new ProtoChunk(
                new ChunkPos(x, z), UpgradeData.EMPTY, LevelHeightAccessor.create(this.minY, this.height), PalettedContainerFactory.create(RegistryHelper.registryAccess()), null
            );
        }

        static World flat(final int originChunkX, final int originChunkZ, final int size, final int baseY) {
            World world = new World(originChunkX, originChunkZ, size, -64, 384, baseY);
            for (int x = 0; x < size * 16; x++) {
                for (int z = 0; z < size * 16; z++) {
                    world.surface[x][z] = baseY;
                    world.set(world.minX() + x, baseY, world.minZ() + z, Blocks.STONE.defaultBlockState());
                }
            }

            return world;
        }

        static World generate(final Random random) {
            int[] heights = HEIGHTS[random.nextInt(HEIGHTS.length)];
            int size = 3 + random.nextInt(3);
            int[][] origins = {{0, 0}, {-size, -size}, {-2, 5}, {62, -251}, {-1, -1}};
            int[] origin = origins[random.nextInt(origins.length)];
            World world = new World(origin[0], origin[1], size, heights[0], heights[1], heights[2]);
            int blocks = size * 16;
            boolean flooded = random.nextInt(4) == 0;
            boolean open = random.nextInt(5) == 0;
            int hazardCount = switch (random.nextInt(4)) {
                case 0 -> 0;
                case 1 -> 2;
                default -> blocks * blocks / 40;
            };

            for (int x = 0; x < blocks; x++) {
                for (int z = 0; z < blocks; z++) {
                    world.surface[x][z] = world.baseY;
                }
            }

            for (int i = open ? 2 : 4 + random.nextInt(blocks / 2); i > 0; i--) {
                int delta = random.nextInt(8) == 0 ? -2 - random.nextInt(6) : random.nextInt(5) - 2;
                world.rectangle(random, (x, z) -> world.surface[x][z] += delta);
            }

            for (int x = 0; x < blocks; x++) {
                for (int z = 0; z < blocks; z++) {
                    world.surface[x][z] = Math.max(world.minY, world.surface[x][z]);
                    for (int y = Math.max(world.minY, world.baseY - 9); y <= world.surface[x][z]; y++) {
                        world.set(world.minX() + x, y, world.minZ() + z, Blocks.STONE.defaultBlockState());
                    }

                    if (flooded) {
                        for (int y = world.surface[x][z] + 1; y <= world.baseY + 2; y++) {
                            world.set(world.minX() + x, y, world.minZ() + z, Blocks.WATER.defaultBlockState());
                        }
                    }
                }
            }

            for (int i = open ? 0 : random.nextInt(blocks * blocks / 12); i > 0; i--) {
                int x = random.nextInt(blocks);
                int z = random.nextInt(blocks);
                int surface = world.surface[x][z];
                switch (random.nextInt(10)) {
                    case 0, 1 -> world.set(world.minX() + x, surface, world.minZ() + z, floors.get(random.nextInt(floors.size())));
                    case 2 -> world.set(world.minX() + x, surface + 2 + random.nextInt(3), world.minZ() + z, obstacles.get(random.nextInt(obstacles.size())));
                    case 3 -> {
                        BlockState door = random.nextBoolean() ? Blocks.OAK_DOOR.defaultBlockState() : Blocks.IRON_DOOR.defaultBlockState();
                        door = door.setValue(DoorBlock.OPEN, random.nextBoolean());
                        world.set(world.minX() + x, surface + 1, world.minZ() + z, door);
                        world.set(world.minX() + x, surface + 2, world.minZ() + z, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
                    }
                    case 4 -> {
                        BlockState wall = obstacles.get(random.nextInt(4));
                        boolean alongX = random.nextBoolean();
                        for (int step = random.nextInt(12); step >= 0; step--) {
                            int wallX = Math.min(blocks - 1, x + (alongX ? step : 0));
                            int wallZ = Math.min(blocks - 1, z + (alongX ? 0 : step));
                            for (int up = 1 + random.nextInt(3); up > 0; up--) {
                                world.set(world.minX() + wallX, world.surface[wallX][wallZ] + up, world.minZ() + wallZ, wall);
                            }
                        }
                    }
                    default -> world.set(world.minX() + x, surface + 1, world.minZ() + z, obstacles.get(random.nextInt(obstacles.size())));
                }
            }

            for (int i = hazardCount; i > 0; i--) {
                BlockState hazard = hazards.get(random.nextInt(hazards.size()));
                if (random.nextInt(4) == 0) {
                    int depth = random.nextInt(3);
                    world.rectangle(random, (x, z) -> {
                        for (int y = world.surface[x][z] - depth; y <= world.surface[x][z]; y++) {
                            world.set(world.minX() + x, y, world.minZ() + z, hazard);
                        }
                    });
                } else {
                    int x = random.nextInt(blocks);
                    int z = random.nextInt(blocks);
                    world.set(world.minX() + x, world.surface[x][z] + random.nextInt(3), world.minZ() + z, hazard);
                }
            }

            if (random.nextInt(4) == 0) {
                world.chunks[random.nextInt(size)][random.nextInt(size)] = null;
            }

            return world;
        }

        private void rectangle(final Random random, final Column column) {
            int blocks = this.surface.length;
            int x0 = random.nextInt(blocks);
            int z0 = random.nextInt(blocks);
            int x1 = Math.min(blocks, x0 + 1 + random.nextInt(12));
            int z1 = Math.min(blocks, z0 + 1 + random.nextInt(12));
            for (int x = x0; x < x1; x++) {
                for (int z = z0; z < z1; z++) {
                    column.accept(x, z);
                }
            }
        }

        int minX() {
            return this.originChunkX << 4;
        }

        int minZ() {
            return this.originChunkZ << 4;
        }

        int centerX() {
            return this.minX() + this.surface.length / 2;
        }

        int centerZ() {
            return this.minZ() + this.surface.length / 2;
        }

        int surface(final int x, final int z) {
            int localX = x - this.minX();
            int localZ = z - this.minZ();
            return localX >= 0 && localX < this.surface.length && localZ >= 0 && localZ < this.surface.length ? this.surface[localX][localZ] : this.baseY;
        }

        ProtoChunk chunk(final int chunkX, final int chunkZ) {
            int x = chunkX - this.originChunkX;
            int z = chunkZ - this.originChunkZ;
            return x >= 0 && x < this.chunks.length && z >= 0 && z < this.chunks.length ? this.chunks[x][z] : null;
        }

        BlockState block(final int x, final int y, final int z) {
            ProtoChunk chunk = this.chunk(x >> 4, z >> 4);
            return chunk == null ? Blocks.VOID_AIR.defaultBlockState() : chunk.getBlockState(x, y, z);
        }

        void set(final int x, final int y, final int z, final BlockState state) {
            ProtoChunk chunk = this.chunk(x >> 4, z >> 4);
            if (chunk != null && y >= this.minY && y < this.minY + this.height) {
                chunk.getSection(chunk.getSectionIndex(y)).setBlockState(x & 15, y & 15, z & 15, state, false);
            }
        }

        private interface Column {
            void accept(int x, int z);
        }
    }
}
