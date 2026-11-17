package net.minecraft.world.level.redstone;

import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@LeafTest
class RedstoneReadTest {
    @Test
    void signalScansKeepReadOrderEarlyExitAndReentrantPositions() {
        Random random = new Random(81);
        SignalGetter getter = Mockito.mock(SignalGetter.class, Mockito.CALLS_REAL_METHODS);
        List<BlockPos> reads = new ArrayList<>();
        Map<BlockPos, Integer> values = new HashMap<>();
        Mockito.doAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            reads.add(pos.immutable());
            return values.getOrDefault(pos, 0);
        }).when(getter).getSignal(any(), any());
        Mockito.doAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            reads.add(pos.immutable());
            return values.getOrDefault(pos, 0);
        }).when(getter).getDirectSignal(any(), any());
        for (int trial = 0; trial < 400; trial++) {
            BlockPos origin = trial == 0 ? new BlockPos(Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)
                : new BlockPos(random.nextInt(60_000_000) - 30_000_000, random.nextInt(512) - 128, random.nextInt(60_000_000) - 30_000_000);
            values.clear();
            for (Direction direction : Direction.VALUES) values.put(origin.relative(direction), trial == 0 ? 0 : random.nextInt(16));
            List<BlockPos> expected = new ArrayList<>();
            int best = 0;
            for (Direction direction : Direction.VALUES) {
                BlockPos pos = origin.relative(direction);
                expected.add(pos);
                best = Math.max(best, values.get(pos));
                if (best >= 15) break;
            }
            reads.clear();
            assertEquals(best, getter.getBestNeighborSignal(origin));
            assertEquals(expected, reads);
            reads.clear();
            assertEquals(best, getter.getDirectSignalTo(origin));
            assertEquals(expected, reads);
        }
        BlockPos origin = BlockPos.ZERO;
        Mockito.doAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            BlockPos saved = pos.immutable();
            getter.getDirectSignalTo(pos);
            assertEquals(saved, pos);
            return 0;
        }).when(getter).getSignal(any(), any());
        assertEquals(0, getter.getBestNeighborSignal(origin));
    }

    @Test
    void incomingWireScanMatchesOriginalAcrossWorldChangesAndBoundaries() {
        Random random = new Random(42);
        Level level = Mockito.mock(Level.class);
        RedStoneWireBlock wire = (RedStoneWireBlock) Blocks.REDSTONE_WIRE;
        DefaultRedstoneWireEvaluator evaluator = new DefaultRedstoneWireEvaluator(wire);
        Map<BlockPos, BlockState> states = new HashMap<>();
        List<BlockPos> reads = new ArrayList<>();
        Mockito.when(level.getBlockState(any())).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            reads.add(pos.immutable());
            return states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        });
        for (int trial = 0; trial < 500; trial++) {
            BlockPos origin = new BlockPos(random.nextInt(128) - 64, random.nextInt(512) - 128, random.nextInt(128) - 64);
            states.clear();
            for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-1, -1, -1), origin.offset(1, 1, 1))) {
                states.put(pos.immutable(), switch (random.nextInt(3)) {
                    case 0 -> Blocks.AIR.defaultBlockState();
                    case 1 -> Blocks.STONE.defaultBlockState();
                    default -> wire.defaultBlockState().setValue(RedStoneWireBlock.POWER, random.nextInt(16));
                });
            }
            reads.clear();
            int expected = originalIncoming(level, origin, wire);
            List<BlockPos> expectedReads = List.copyOf(reads);
            reads.clear();
            assertEquals(expected, evaluator.getIncomingWireSignal(level, origin));
            assertEquals(expectedReads, reads);
        }
        states.clear();
        assertEquals(0, evaluator.getIncomingWireSignal(level, BlockPos.ZERO));
    }

    @Test
    void targetStrengthMatchesOriginalForEverySignalSourceAndChunkBoundary() throws ReflectiveOperationException {
        Random random = new Random(137);
        Level level = Mockito.mock(Level.class);
        Mockito.doCallRealMethod().when(level).getBestNeighborSignal(any());
        Mockito.doCallRealMethod().when(level).getSignal(any(), any());
        Mockito.doCallRealMethod().when(level).getDirectSignalTo(any());
        Mockito.doCallRealMethod().when(level).getDirectSignal(any(), any());
        RedStoneWireBlock wire = (RedStoneWireBlock) Blocks.REDSTONE_WIRE;
        DefaultRedstoneWireEvaluator normal = new DefaultRedstoneWireEvaluator(wire);
        ExperimentalRedstoneWireEvaluator experimental = new ExperimentalRedstoneWireEvaluator(wire);
        var setPower = ExperimentalRedstoneWireEvaluator.class.getDeclaredMethod("setPower", BlockPos.class, int.class, Orientation.class);
        setPower.setAccessible(true);
        List<Block> sources = new ArrayList<>(BuiltInRegistries.BLOCK.stream().filter(block -> block.defaultBlockState().isSignalSource()).toList());
        sources.add(Blocks.TEST_BLOCK);
        sources.add(Blocks.SHULKER_BOX);
        Map<BlockPos, BlockState> states = new HashMap<>();
        Map<BlockPos, BlockEntity> entities = new HashMap<>();
        Map<BlockPos, Integer> pending = new HashMap<>();
        List<StateRead> reads = new ArrayList<>();
        BlockPos[] origin = {BlockPos.ZERO};
        boolean[] mutateOnChunkLoad = {false};
        boolean[] chunkLoaded = {false};
        ServerChunkCache chunks = Mockito.mock(ServerChunkCache.class);
        LevelChunk fullChunk = Mockito.mock(LevelChunk.class);
        Mockito.when(level.getChunkSource()).thenReturn(chunks);
        Mockito.when(chunks.getChunkAtIfLoadedImmediately(anyInt(), anyInt())).thenAnswer(call ->
            !mutateOnChunkLoad[0] || (call.<Integer>getArgument(0) == (origin[0].getX() >> 4)
                && call.<Integer>getArgument(1) == (origin[0].getZ() >> 4)) ? fullChunk : null);
        Mockito.when(level.getBlockState(any())).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            reads.add(new StateRead(pos.immutable(), wire.defaultBlockState().isSignalSource()));
            if (mutateOnChunkLoad[0] && !chunkLoaded[0]
                && ((pos.getX() >> 4) != (origin[0].getX() >> 4) || (pos.getZ() >> 4) != (origin[0].getZ() >> 4))) {
                chunkLoaded[0] = true;
                states.put(origin[0].above(), Blocks.STONE.defaultBlockState());
                for (Direction direction : Direction.Plane.HORIZONTAL.faces) {
                    states.put(origin[0].relative(direction), wire.defaultBlockState().setValue(RedStoneWireBlock.POWER, 13));
                }
            }
            return states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        });
        Mockito.when(level.getBlockEntity(any())).thenAnswer(call -> entities.get(call.<BlockPos>getArgument(0)));
        int[] coordinates = {0, 1, 2, 7, 13, 14, 15};
        for (boolean parallel : new boolean[] {false, true}) {
            boolean previousParallel = org.dreeam.leaf.config.modules.async.SparklyPaperParallelWorldTicking.enabled;
            org.dreeam.leaf.config.modules.async.SparklyPaperParallelWorldTicking.enabled = parallel;
            try {
                for (int trial = 0; trial < sources.size() * 84; trial++) {
                    int coordinate = trial / 12 % coordinates.length;
                    int x = coordinates[coordinate];
                    int z = coordinates[coordinates.length - 1 - coordinate];
                    origin[0] = new BlockPos((random.nextInt(8) - 4) * 16 + x, 64, (random.nextInt(8) - 4) * 16 + z);
                    states.clear();
                    entities.clear();
                    for (BlockPos pos : BlockPos.betweenClosed(origin[0].offset(-2, -2, -2), origin[0].offset(2, 2, 2))) {
                        states.put(pos.immutable(), switch (random.nextInt(4)) {
                            case 0 -> Blocks.AIR.defaultBlockState();
                            case 1 -> Blocks.STONE.defaultBlockState();
                            default -> wire.defaultBlockState().setValue(RedStoneWireBlock.POWER, random.nextInt(16));
                        });
                    }
                    Block source = sources.get(trial / 84);
                    List<BlockState> sourceStates = source.getStateDefinition().getPossibleStates();
                    BlockState sourceState = sourceStates.get(random.nextInt(sourceStates.size()));
                    BlockPos sourcePos = origin[0].relative(Direction.VALUES[trial % 6]);
                    states.put(sourcePos, sourceState);
                    if (source instanceof EntityBlock entityBlock) {
                        BlockEntity entity = entityBlock.newBlockEntity(sourcePos, sourceState);
                        if (entity instanceof net.minecraft.world.level.block.entity.ComparatorBlockEntity comparator) {
                            comparator.setOutputSignal(random.nextInt(16));
                        } else if (entity instanceof net.minecraft.world.level.block.entity.TestBlockEntity test) {
                            test.setPowered(random.nextBoolean());
                        } else if (entity instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
                            var count = net.minecraft.world.level.block.entity.ContainerOpenersCounter.class.getDeclaredField("openCount");
                            count.setAccessible(true);
                            count.setInt(chest.openersCounter, random.nextInt(16));
                        } else if (entity instanceof net.minecraft.world.level.block.entity.JukeboxBlockEntity jukebox && random.nextBoolean()) {
                            jukebox.getSongPlayer().song = net.minecraft.core.Holder.direct(Mockito.mock(net.minecraft.world.item.JukeboxSong.class));
                        }
                        entities.put(sourcePos, entity);
                    }
                    states.put(origin[0], wire.defaultBlockState());
                    // The experimental map deliberately disagrees with world power and includes air positions.
                    for (Direction direction : Direction.Plane.HORIZONTAL.faces) {
                        BlockPos pos = origin[0].relative(direction);
                        int power = random.nextInt(16);
                        pending.put(pos, power);
                        setPower.invoke(experimental, pos, power, Orientation.of(Direction.UP, Direction.NORTH, Orientation.SideBias.LEFT));
                    }
                    Map<BlockPos, BlockState> initial = Map.copyOf(states);
                    mutateOnChunkLoad[0] = (x < 2 || x > 13 || z < 2 || z > 13) && trial / 6 % 2 == 0;
                    for (RedstoneWireEvaluator evaluator : List.of(normal, experimental)) {
                        states.clear();
                        states.putAll(initial);
                        chunkLoaded[0] = false;
                        reads.clear();
                        int block = wire.getBlockSignal(level, origin[0]);
                        int incoming = evaluator == normal && block == 15 ? 0 : originalIncoming(level, origin[0], wire, evaluator == experimental ? pending : Map.of());
                        int expected = block << 4 | incoming;
                        List<StateRead> expectedReads = List.copyOf(reads);
                        states.clear();
                        states.putAll(initial);
                        chunkLoaded[0] = false;
                        reads.clear();
                        assertEquals(expected, evaluator.getBlockAndWireSignal(level, origin[0], evaluator == normal), "trial " + trial + " at " + origin[0] + " source " + source);
                        if (mutateOnChunkLoad[0]) {
                            assertEquals(expectedReads, reads);
                        } else {
                            assertEquals(expectedReads.stream().filter(read -> !read.signaling()).toList(), reads.stream().filter(read -> !read.signaling()).toList());
                            assertTrue(reads.size() <= expectedReads.size());
                        }
                        assertTrue(wire.defaultBlockState().isSignalSource());
                        Mockito.clearInvocations(level);
                        Mockito.clearInvocations(chunks);
                    }
                }
            } finally {
                org.dreeam.leaf.config.modules.async.SparklyPaperParallelWorldTicking.enabled = previousParallel;
            }
        }
    }

    private record StateRead(BlockPos pos, boolean signaling) {}

    @Test
    void everyChunkInSignalFootprintMustBeLoadedBeforeReusingStates() {
        Level level = Mockito.mock(Level.class);
        ServerChunkCache chunks = Mockito.mock(ServerChunkCache.class);
        LevelChunk fullChunk = Mockito.mock(LevelChunk.class);
        Mockito.when(level.getChunkSource()).thenReturn(chunks);
        Mockito.doCallRealMethod().when(level).getBestNeighborSignal(any());
        Mockito.doCallRealMethod().when(level).getSignal(any(), any());
        Mockito.doCallRealMethod().when(level).getDirectSignalTo(any());
        Mockito.doCallRealMethod().when(level).getDirectSignal(any(), any());
        RedStoneWireBlock wire = (RedStoneWireBlock) Blocks.REDSTONE_WIRE;
        DefaultRedstoneWireEvaluator evaluator = new DefaultRedstoneWireEvaluator(wire);
        Map<BlockPos, BlockState> states = new HashMap<>();
        var loaded = new HashSet<Long>();
        List<Long> probes = new ArrayList<>();
        List<StateRead> reads = new ArrayList<>();
        BlockPos[] origin = {BlockPos.ZERO};
        Mockito.when(chunks.getChunkAtIfLoadedImmediately(anyInt(), anyInt())).thenAnswer(call -> {
            long key = ChunkPos.pack(call.getArgument(0), call.getArgument(1));
            probes.add(key);
            return loaded.contains(key) ? fullChunk : null;
        });
        Mockito.when(level.getBlockState(any())).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            reads.add(new StateRead(pos.immutable(), wire.defaultBlockState().isSignalSource()));
            if (loaded.add(ChunkPos.pack(pos))) {
                states.put(origin[0].above(), Blocks.STONE.defaultBlockState());
                states.put(origin[0].east(), wire.defaultBlockState().setValue(RedStoneWireBlock.POWER, 13));
            }
            return states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        });
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                origin[0] = new BlockPos(x - 16, 64, z - 32);
                var footprint = new HashSet<Long>();
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        if (Math.abs(dx) + Math.abs(dz) <= 2) {
                            footprint.add(ChunkPos.pack(origin[0].offset(dx, 0, dz)));
                        }
                    }
                }
                states.clear();
                states.put(origin[0], wire.defaultBlockState());
                states.put(origin[0].below(), Blocks.STONE.defaultBlockState());
                for (Direction direction : Direction.Plane.HORIZONTAL.faces) {
                    states.put(origin[0].relative(direction), Blocks.STONE.defaultBlockState());
                    states.put(origin[0].relative(direction).above(), wire.defaultBlockState().setValue(RedStoneWireBlock.POWER, 12));
                }
                Map<BlockPos, BlockState> initial = Map.copyOf(states);
                List<Long> missingChunks = new ArrayList<>(footprint);
                missingChunks.add(null);
                for (Long missing : missingChunks) {
                    List<StateRead> expectedReads = null;
                    int expected = 0;
                    for (boolean optimized : new boolean[] {false, true}) {
                        loaded.clear();
                        loaded.addAll(footprint);
                        loaded.remove(missing);
                        states.clear();
                        states.putAll(initial);
                        reads.clear();
                        probes.clear();
                        if (!optimized) {
                            expected = Math.max(wire.getBlockSignal(level, origin[0]), originalIncoming(level, origin[0], wire));
                            expectedReads = List.copyOf(reads);
                            continue;
                        }
                        assertEquals(expected, evaluator.calculateTargetStrength(level, origin[0]), "at " + origin[0] + " missing " + missing);
                        assertEquals(probes.size(), new HashSet<>(probes).size());
                        if (missing == null) {
                            assertEquals(footprint, new HashSet<>(probes));
                            assertEquals(40, reads.size());
                            assertEquals(expectedReads.stream().filter(read -> !read.signaling()).toList(), reads.stream().filter(read -> !read.signaling()).toList());
                        } else {
                            assertTrue(probes.contains(missing));
                            assertEquals(expectedReads, reads);
                        }
                    }
                }
                Mockito.clearInvocations(level, chunks);
            }
        }
    }

    @Test
    void sharedScanSuppressesTheSignalFlagItsThreadReads() throws Exception {
        boolean previousParallel = org.dreeam.leaf.config.modules.async.SparklyPaperParallelWorldTicking.enabled;
        try {
            for (boolean parallel : new boolean[] {false, true}) {
                org.dreeam.leaf.config.modules.async.SparklyPaperParallelWorldTicking.enabled = parallel;
                checkSharedScanSuppression();
            }
            FutureTask<Void> task = new FutureTask<>(RedstoneReadTest::checkSharedScanSuppression, null);
            new TickThread.ServerLevelTickThread(task, "redstone-read-test").start();
            task.get(10, TimeUnit.SECONDS);
        } finally {
            org.dreeam.leaf.config.modules.async.SparklyPaperParallelWorldTicking.enabled = previousParallel;
        }
    }

    private static void checkSharedScanSuppression() {
        RedStoneWireBlock wire = (RedStoneWireBlock) Blocks.REDSTONE_WIRE;
        Level level = Mockito.mock(Level.class);
        List<Boolean> signaling = new ArrayList<>();
        Mockito.when(level.getBlockState(any())).thenAnswer(call -> {
            signaling.add(wire.defaultBlockState().isSignalSource());
            return Blocks.AIR.defaultBlockState();
        });
        assertEquals(0, wire.getBlockAndWireSignal(level, BlockPos.ZERO, new DefaultRedstoneWireEvaluator(wire), true));
        assertEquals(List.of(false, false, false, false, false, false, true, true, true, true), signaling);
        assertTrue(wire.defaultBlockState().isSignalSource());
    }

    @Test
    void solidFloorSkipsFourReadsAndConductorSidesSkipEight() {
        Level level = Mockito.mock(Level.class);
        ServerChunkCache chunks = Mockito.mock(ServerChunkCache.class);
        Mockito.when(level.getChunkSource()).thenReturn(chunks);
        Mockito.when(chunks.getChunkAtIfLoadedImmediately(anyInt(), anyInt())).thenReturn(Mockito.mock(LevelChunk.class));
        Mockito.doCallRealMethod().when(level).getBestNeighborSignal(any());
        Mockito.doCallRealMethod().when(level).getSignal(any(), any());
        Mockito.doCallRealMethod().when(level).getDirectSignalTo(any());
        Mockito.doCallRealMethod().when(level).getDirectSignal(any(), any());
        RedStoneWireBlock wire = (RedStoneWireBlock) Blocks.REDSTONE_WIRE;
        DefaultRedstoneWireEvaluator evaluator = new DefaultRedstoneWireEvaluator(wire);
        BlockPos origin = new BlockPos(8, 64, 8);
        Map<BlockPos, BlockState> states = new HashMap<>();
        List<BlockPos> reads = new ArrayList<>();
        Mockito.when(level.getBlockState(any())).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            reads.add(pos.immutable());
            return states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        });
        states.put(origin, wire.defaultBlockState());
        states.put(origin.below(), Blocks.STONE.defaultBlockState());
        for (int sides = 0; sides < 2; sides++) {
            if (sides == 1) {
                for (Direction direction : Direction.Plane.HORIZONTAL.faces) {
                    states.put(origin.relative(direction), Blocks.STONE.defaultBlockState());
                    states.put(origin.relative(direction).above(), wire.defaultBlockState().setValue(RedStoneWireBlock.POWER, 12));
                }
            }
            reads.clear();
            int expected = Math.max(wire.getBlockSignal(level, origin), originalIncoming(level, origin, wire));
            assertEquals(sides == 0 ? 20 : 48, reads.size());
            reads.clear();
            assertEquals(expected, evaluator.calculateTargetStrength(level, origin));
            assertEquals(sides == 0 ? 16 : 40, reads.size());
        }
    }

    @Test
    void wireEventsKeepListenerResultAndPostEventStateCheck() {
        Level level = Mockito.mock(Level.class);
        RedStoneWireBlock wire = (RedStoneWireBlock) Blocks.REDSTONE_WIRE;
        BlockPos pos = new BlockPos(15, 64, -16);
        BlockState state = wire.defaultBlockState();
        DefaultRedstoneWireEvaluator evaluator = Mockito.spy(new DefaultRedstoneWireEvaluator(wire));
        Mockito.doReturn(8).when(evaluator).calculateTargetStrength(level, pos);
        Mockito.when(level.getBlockState(pos)).thenReturn(state);
        RegisteredListener listener = new RegisteredListener(new Listener() {}, (ignored, event) -> {}, EventPriority.NORMAL, Mockito.mock(Plugin.class), false);
        try (var events = Mockito.mockStatic(CraftEventFactory.class)) {
            evaluator.updatePowerStrength(level, pos, state, null, false);
            events.verifyNoInteractions();
            Mockito.verify(level).setBlock(pos, state.setValue(RedStoneWireBlock.POWER, 8), Block.UPDATE_CLIENTS);
            BlockRedstoneEvent.getHandlerList().register(listener);
            events.when(() -> CraftEventFactory.callRedstoneChange(level, pos, 0, 8)).thenReturn(new BlockRedstoneEvent(Mockito.mock(org.bukkit.block.Block.class), 0, 3));
            evaluator.updatePowerStrength(level, pos, state, null, false);
            events.verify(() -> CraftEventFactory.callRedstoneChange(level, pos, 0, 8));
            Mockito.verify(level).setBlock(pos, state.setValue(RedStoneWireBlock.POWER, 3), Block.UPDATE_CLIENTS);
            Mockito.clearInvocations(level);
            events.when(() -> CraftEventFactory.callRedstoneChange(level, pos, 0, 8)).thenAnswer(call -> {
                Mockito.when(level.getBlockState(pos)).thenReturn(Blocks.AIR.defaultBlockState());
                return new BlockRedstoneEvent(Mockito.mock(org.bukkit.block.Block.class), 0, 3);
            });
            evaluator.updatePowerStrength(level, pos, state, null, false);
            Mockito.verify(level, Mockito.never()).setBlock(any(), any(), anyInt());
            BlockRedstoneEvent.getHandlerList().unregister(listener);
            Mockito.when(level.getBlockState(pos)).thenReturn(state);
            events.clearInvocations();
            evaluator.updatePowerStrength(level, pos, state, null, false);
            events.verifyNoInteractions();
        } finally {
            BlockRedstoneEvent.getHandlerList().unregister(listener);
        }
    }

    private static int originalIncoming(Level level, BlockPos pos, RedStoneWireBlock wire) {
        return originalIncoming(level, pos, wire, Map.of());
    }

    private static int originalIncoming(Level level, BlockPos pos, RedStoneWireBlock wire, Map<BlockPos, Integer> pending) {
        int result = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL.faces) {
            BlockPos neighbor = pos.relative(direction);
            BlockState state = level.getBlockState(neighbor);
            result = Math.max(result, pending.getOrDefault(neighbor, power(state, wire)));
            BlockPos above = pos.above();
            if (state.isRedstoneConductor(level, neighbor) && !level.getBlockState(above).isRedstoneConductor(level, above)) {
                result = Math.max(result, pending.getOrDefault(neighbor.above(), power(level.getBlockState(neighbor.above()), wire)));
            } else if (!state.isRedstoneConductor(level, neighbor)) {
                result = Math.max(result, pending.getOrDefault(neighbor.below(), power(level.getBlockState(neighbor.below()), wire)));
            }
        }
        return Math.max(0, result - 1);
    }

    private static int power(BlockState state, RedStoneWireBlock wire) {
        return state.is(wire) ? state.getValue(RedStoneWireBlock.POWER) : 0;
    }
}
