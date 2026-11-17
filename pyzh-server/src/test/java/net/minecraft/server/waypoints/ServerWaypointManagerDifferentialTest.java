package net.minecraft.server.waypoints;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.google.common.collect.Tables;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.waypoints.Waypoint;
import net.minecraft.world.waypoints.WaypointTransmitter;
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.config.modules.gameplay.LocatorBarRange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

/**
 * Drives the manager and Paper's table-based one through the same random history of joins, quits, moves,
 * dimension changes, range, visibility, game mode, chunk and game rule changes, with the real connection
 * classes, and requires every player to be sent the same waypoint packets in the same order.
 * Both managers see the same entity objects, because a mock's hash code, and with it the order of a
 * HashSet of waypoints, is its identity.
 */
@LeafTest
class ServerWaypointManagerDifferentialTest {
    private static final int SEEDS = 20, STEPS = 4000, PLAYERS = 7, MOBS = 3, LEVELS = 2;
    private static final double[] TRANSMIT_RANGES = {0.0, 0.0, 6.0, 30.0, 100.0, 6.0E7, 6.0E7, 6.0E7};
    private static final double[] RECEIVE_RANGES = {0.0, 20.0, 6.0E7, 6.0E7, 6.0E7};
    private static final int EXPECTED = 0, ACTUAL = 1;

    @AfterEach
    void resetConfig() {
        LocatorBarRange.maxDistance = 0.0;
    }

    @Test
    void sendsEveryPlayerTheSamePacketsAsTheTableBasedManager() throws Exception {
        final Set<String> kinds = new HashSet<>();
        int packets = 0;
        for (int seed = 0; seed < SEEDS; seed++) {
            final World world = new World(seed);
            for (int step = 0; step < STEPS; step++) {
                world.step();
                for (int i = 0; i < PLAYERS; i++) {
                    final Actor actor = world.actors.get(i);
                    assertEquals(actor.sent.get(EXPECTED), actor.sent.get(ACTUAL), "seed " + seed + " step " + step + " (" + world.lastStep + ") player " + i);
                }
            }
            for (int level = 0; level < LEVELS; level++) {
                assertEquals(world.transmitterIds(level, EXPECTED), world.transmitterIds(level, ACTUAL), "seed " + seed + " transmitters of level " + level);
            }
            for (int i = 0; i < PLAYERS; i++) {
                final List<String> sent = world.actors.get(i).sent.get(ACTUAL);
                packets += sent.size();
                sent.forEach(packet -> kinds.add(packet.substring(0, packet.indexOf(' '))));
            }
        }
        assertTrue(packets > 15_000, "only " + packets + " packets");
        assertEquals(Set.of("00Vec3iWaypoint", "02Vec3iWaypoint", "00ChunkWaypoint", "02ChunkWaypoint", "00AzimuthWaypoint", "02AzimuthWaypoint", "01EmptyWaypoint"), kinds);
    }

    @Test
    void preservesUpdateSnapshotsAcrossReentrantSends() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            final boolean replace = mode != 0;
            final boolean column = mode == 2;
            final boolean clear = mode == 3;
            final World world = new World(43);
            for (Actor actor : world.actors) {
                actor.level = 0;
                setField(Entity.class, actor.entity, "firstTick", false);
                world.place(actor, new Vec3(actor.entity.getId() % 10, 64, 0));
                if (actor.player != null) actor.sentChunks.add(actor.entity.chunkPosition().longKey());
                world.join(actor);
            }
            final Actor viewer = world.actors.getFirst();
            final Actor removed = world.actors.get(2);
            for (Actor actor : world.actors) world.place(actor, actor.entity.position.add(1, 0, 0));
            world.onSend = manager -> {
                if (clear) manager.breakAllConnections();
                else if (replace) manager.remakeConnections(removed.entity);
                else manager.untrackWaypoint(removed.entity);
            };
            world.both(0, manager -> {
                if (column) manager.updateWaypoint(removed.entity);
                else manager.updatePlayer(viewer.player);
            });
            assertTrue(world.callbackFired[EXPECTED] && world.callbackFired[ACTUAL]);
            world.onSend = null;
            world.both(0, manager -> manager.updatePlayer(viewer.player));
            for (Actor actor : world.actors) assertEquals(actor.sent.get(EXPECTED), actor.sent.get(ACTUAL));
        }
    }

    @Test
    void positionIdentityShortcutsMatchTheOldDistanceAndPacketChecks() throws Exception {
        final World world = new World(88);
        final Actor source = world.actors.get(1);
        final Actor receiver = world.actors.getFirst();
        final Waypoint.Icon icon = new Waypoint.Icon();
        final var block = new WaypointTransmitter.EntityBlockConnection(source.entity, icon, receiver.player);
        final var chunk = new WaypointTransmitter.EntityChunkConnection(source.entity, icon, receiver.player);
        BlockPos lastBlock = source.entity.blockPosition();
        ChunkPos lastChunk = source.entity.chunkPosition();
        world.side = ACTUAL;
        final List<String> expected = new ArrayList<>();
        for (int step = 0; step < 10_000; step++) {
            world.place(source, world.nextPosition(source));
            final BlockPos position = source.entity.blockPosition();
            final ChunkPos chunkPosition = source.entity.chunkPosition();
            assertEquals(lastBlock.distManhattan(position), block.distanceManhattan());
            assertEquals(lastChunk.getChessboardDistance(chunkPosition), chunk.distanceChessboard());
            if (position.distManhattan(lastBlock) > 0) {
                expected.add(packetData(ClientboundTrackedWaypointPacket.updateWaypointPosition(source.entity.getUUID(), icon, position)));
                lastBlock = position;
            }
            if (chunkPosition.getChessboardDistance(lastChunk) > 0) {
                expected.add(packetData(ClientboundTrackedWaypointPacket.updateWaypointChunk(source.entity.getUUID(), icon, chunkPosition)));
                lastChunk = chunkPosition;
            }
            block.update();
            chunk.update();
            assertEquals(expected, receiver.sent.get(ACTUAL), "step " + step);
            expected.clear();
            receiver.sent.get(ACTUAL).clear();
        }
    }

    private static String packetData(final ClientboundTrackedWaypointPacket packet) {
        final RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            ClientboundTrackedWaypointPacket.STREAM_CODEC.encode(buffer, packet);
            final String bytes = ByteBufUtil.hexDump(buffer);
            return bytes.substring(0, 2) + packet.waypoint().getClass().getSimpleName() + " " + bytes;
        } finally {
            buffer.release();
        }
    }

    private static final class Actor {
        LivingEntity entity;
        ServerPlayer player;
        double transmitRange, receiveRange;
        // What the entity's attribute hooks last reported; lags the ranges until refreshAttributes.
        double hookedTransmitRange, hookedReceiveRange;
        boolean spectator, tracked;
        int level;
        Actor riding;
        final Set<Entity> hidden = new HashSet<>();
        final LongOpenHashSet sentChunks = new LongOpenHashSet();
        final List<List<String>> sent = List.of(new ArrayList<>(), new ArrayList<>());
    }

    private static final class World {
        final Random random;
        final ServerWaypointManager[][] managers = new ServerWaypointManager[LEVELS][];
        // Which of a level's two managers is being called, and so whose packets are being recorded.
        int side;
        final List<Actor> actors = new ArrayList<>();
        final Map<Object, Actor> byHandle = new HashMap<>();
        String lastStep = "";
        Consumer<ServerWaypointManager> onSend;
        final boolean[] callbackFired = new boolean[2];

        World(final long seed) throws Exception {
            this.random = new Random(seed);
            final GameRules gameRules = Mockito.mock(GameRules.class);
            Mockito.when(gameRules.get(GameRules.LOCATOR_BAR)).thenReturn(true);
            final ServerLevel level = Mockito.mock(ServerLevel.class);
            Mockito.when(level.getGameRules()).thenReturn(gameRules);
            for (int i = 0; i < LEVELS; i++) {
                this.managers[i] = new ServerWaypointManager[] {new TableBasedManager(level), new ServerWaypointManager(level)};
            }
            final Set<Integer> ids = new HashSet<>();
            for (int i = 0; i < PLAYERS + MOBS; i++) {
                int id;
                do {
                    id = this.random.nextInt(4096);
                } while (!ids.add(id));
                this.actors.add(this.createActor(i < PLAYERS, id));
            }
        }

        private Actor createActor(final boolean isPlayer, final int id) throws Exception {
            final Actor actor = new Actor();
            final var settings = Mockito.withSettings().stubOnly().defaultAnswer(Mockito.CALLS_REAL_METHODS);
            final LivingEntity entity = isPlayer ? Mockito.mock(ServerPlayer.class, settings) : Mockito.mock(LivingEntity.class, settings);
            actor.entity = entity;
            entity.setId(id);
            entity.setUUID(new UUID(this.random.nextLong(), this.random.nextLong()));
            setField(LivingEntity.class, entity, "locatorBarIcon", new Waypoint.Icon());
            setField(Entity.class, entity, "firstTick", this.random.nextInt(4) == 0);
            this.place(actor, this.randomPosition());
            actor.transmitRange = actor.hookedTransmitRange = isPlayer ? 6.0E7 : this.pick(TRANSMIT_RANGES);
            actor.receiveRange = actor.hookedReceiveRange = 6.0E7;
            Mockito.doAnswer(invocation -> invocation.getArgument(0) == Attributes.WAYPOINT_TRANSMIT_RANGE ? actor.transmitRange : actor.receiveRange)
                .when(entity).getAttributeValue(any());
            Mockito.doAnswer(invocation -> actor.spectator).when(entity).isSpectator();
            Mockito.doAnswer(invocation -> this.byHandle.get(invocation.getArgument(0)).riding == actor).when(entity).hasIndirectPassenger(any());
            Mockito.doReturn(null).when(entity).getTeam();
            if (isPlayer) {
                final ServerPlayer player = actor.player = (ServerPlayer) entity;
                final CraftPlayer bukkit = Mockito.mock(CraftPlayer.class, Mockito.withSettings().stubOnly());
                Mockito.doAnswer(invocation -> !actor.hidden.contains(this.byHandle.get(invocation.getArgument(0)).entity))
                    .when(bukkit).canSee(any(org.bukkit.entity.Entity.class));
                Mockito.doReturn(bukkit).when(player).getBukkitEntity();
                this.byHandle.put(bukkit, actor);
                final RegionizedPlayerChunkLoader.PlayerChunkLoaderData chunkLoader = Mockito.mock(RegionizedPlayerChunkLoader.PlayerChunkLoaderData.class, Mockito.withSettings().stubOnly());
                Mockito.when(chunkLoader.getSentChunksRaw()).thenReturn(actor.sentChunks);
                Mockito.doReturn(chunkLoader).when(player).moonrise$getChunkLoader();
                player.connection = Mockito.mock(ServerGamePacketListenerImpl.class, Mockito.withSettings().stubOnly());
                Mockito.doAnswer(invocation -> {
                    final ClientboundTrackedWaypointPacket packet = invocation.getArgument(0);
                    actor.sent.get(this.side).add(packetData(packet));
                    if (this.onSend != null && !this.callbackFired[this.side]) {
                        this.callbackFired[this.side] = true;
                        this.onSend.accept(this.managers[actor.level][this.side]);
                    }
                    return null;
                }).when(player.connection).send(any(Packet.class));
            } else {
                final CraftLivingEntity bukkit = Mockito.mock(CraftLivingEntity.class, Mockito.withSettings().stubOnly());
                Mockito.doReturn(bukkit).when(entity).getBukkitEntity();
                this.byHandle.put(bukkit, actor);
            }
            this.byHandle.put(entity, actor);
            actor.level = this.random.nextInt(LEVELS);
            return actor;
        }

        List<Integer> transmitterIds(final int level, final int side) {
            final List<Integer> ids = new ArrayList<>();
            this.managers[level][side].transmitters().forEach(waypoint -> ids.add(((Entity) waypoint).getId()));
            return ids;
        }

        private void both(final int level, final Consumer<ServerWaypointManager> call) {
            for (this.side = EXPECTED; this.side <= ACTUAL; this.side++) {
                call.accept(this.managers[level][this.side]);
            }
        }

        void step() throws Exception {
            final Actor actor = this.actors.get(this.random.nextInt(this.actors.size()));
            final int level = actor.level;
            final int roll = this.random.nextInt(100);
            if (roll < 40) {
                this.lastStep = "move";
                this.move(actor, this.nextPosition(actor));
            } else if (roll < 50) {
                // The order of one accepted move packet: move, snap back for the event, snap to the target, chunk map move.
                this.lastStep = "move packet";
                final Vec3 start = actor.entity.position;
                final Vec3 target = this.nextPosition(actor);
                this.move(actor, target);
                this.move(actor, start);
                this.move(actor, target);
                if (actor.player != null && actor.tracked && actor.player.isReceivingWaypoints()) {
                    this.both(level, manager -> manager.updatePlayer(actor.player));
                }
            } else if (roll < 58) {
                this.lastStep = actor.tracked ? "quit" : "join";
                if (actor.tracked) {
                    this.quit(actor);
                } else {
                    this.join(actor);
                }
            } else if (roll < 62) {
                this.lastStep = "change dimension";
                if (actor.tracked) {
                    this.quit(actor);
                }
                actor.level = (actor.level + 1) % LEVELS;
                this.place(actor, this.randomPosition());
                this.join(actor);
            } else if (roll < 68) {
                this.lastStep = "range";
                actor.transmitRange = this.pick(TRANSMIT_RANGES);
                if (actor.player != null) {
                    actor.receiveRange = this.pick(RECEIVE_RANGES);
                }
                if (this.random.nextBoolean()) {
                    this.refreshAttributes(actor);
                }
            } else if (roll < 72) {
                this.lastStep = "refresh attributes";
                this.refreshAttributes(actor);
            } else if (roll < 77) {
                this.lastStep = "visibility";
                final Actor viewer = this.actors.get(this.random.nextInt(PLAYERS));
                if (!viewer.hidden.remove(actor.entity)) {
                    viewer.hidden.add(actor.entity);
                }
            } else if (roll < 80) {
                this.lastStep = "game mode";
                actor.spectator = !actor.spectator && actor.player != null;
            } else if (roll < 82) {
                this.lastStep = "ride";
                actor.riding = actor.riding == null ? this.actors.get(this.random.nextInt(this.actors.size())) : null;
            } else if (roll < 88) {
                this.lastStep = "chunks";
                final Actor viewer = this.actors.get(this.random.nextInt(PLAYERS));
                final int change = this.random.nextInt(3);
                if (change == 0) {
                    viewer.sentChunks.clear();
                } else if (change == 1) {
                    this.actors.forEach(other -> viewer.sentChunks.add(other.entity.chunkPosition().longKey()));
                } else if (!viewer.sentChunks.remove(actor.entity.chunkPosition().longKey())) {
                    viewer.sentChunks.add(actor.entity.chunkPosition().longKey());
                }
                if (viewer.tracked && viewer.player.isReceivingWaypoints()) {
                    this.both(viewer.level, manager -> manager.updatePlayer(viewer.player));
                }
            } else if (roll < 90) {
                this.lastStep = "team";
                this.both(level, manager -> manager.remakeConnections(actor.entity));
            } else if (roll < 92) {
                this.lastStep = "icon";
                actor.entity.waypointIcon().color = this.random.nextBoolean() ? Optional.empty() : Optional.of(this.random.nextInt(0xFFFFFF));
                this.both(level, manager -> {
                    manager.untrackWaypoint(actor.entity);
                    manager.trackWaypoint(actor.entity);
                });
            } else if (roll < 94) {
                this.lastStep = "locator bar rule";
                this.both(level, manager -> {
                    manager.locatorBarEnabled = !manager.locatorBarEnabled;
                    if (manager.locatorBarEnabled) {
                        for (final Actor other : this.actors) {
                            if (other.player != null && other.tracked && other.level == level) {
                                manager.updatePlayer(other.player);
                            }
                        }
                    } else {
                        manager.breakAllConnections();
                    }
                });
            } else if (roll < 95) {
                this.lastStep = "first tick over";
                setField(Entity.class, actor.entity, "firstTick", false);
            } else if (roll < 96) {
                this.lastStep = "range limit";
                LocatorBarRange.maxDistance = this.random.nextBoolean() ? 0.0 : 25.0;
            } else {
                // Calls no caller makes in this state, such as updating a player that never joined.
                final int call = this.random.nextInt(actor.player != null ? 6 : 3);
                this.lastStep = "raw call " + call;
                this.both(level, manager -> {
                    switch (call) {
                        case 0 -> manager.trackWaypoint(actor.entity);
                        case 1 -> manager.updateWaypoint(actor.entity);
                        case 2 -> manager.untrackWaypoint(actor.entity);
                        case 3 -> manager.addPlayer(actor.player);
                        case 4 -> manager.updatePlayer(actor.player);
                        default -> manager.removePlayer(actor.player);
                    }
                });
            }
        }

        // ServerLevel.EntityCallbacks.onTrackingStart
        private void join(final Actor actor) {
            actor.tracked = true;
            this.both(actor.level, manager -> {
                if (actor.player != null && actor.player.isReceivingWaypoints()) {
                    manager.addPlayer(actor.player);
                }
                if (actor.entity.isTransmittingWaypoint()) {
                    manager.trackWaypoint(actor.entity);
                }
            });
            actor.hookedTransmitRange = actor.transmitRange;
            actor.hookedReceiveRange = actor.receiveRange;
        }

        // LivingEntity.onRemoval, then ServerLevel.EntityCallbacks.onTrackingEnd and onDestroyed
        private void quit(final Actor actor) {
            actor.tracked = false;
            this.both(actor.level, manager -> {
                manager.untrackWaypoint(actor.entity);
                if (actor.player != null) {
                    manager.removePlayer(actor.player);
                }
                manager.untrackWaypoint(actor.entity);
            });
        }

        // LivingEntity.onAttributeUpdated and ServerPlayer.onAttributeUpdated, which run some time after the value changed
        private void refreshAttributes(final Actor actor) {
            final boolean receiveChanged = actor.hookedReceiveRange != actor.receiveRange;
            final boolean transmitChanged = actor.hookedTransmitRange != actor.transmitRange;
            actor.hookedReceiveRange = actor.receiveRange;
            actor.hookedTransmitRange = actor.transmitRange;
            this.both(actor.level, manager -> {
                if (receiveChanged) {
                    if (actor.receiveRange > 0.0) {
                        manager.addPlayer(actor.player);
                    } else {
                        manager.removePlayer(actor.player);
                    }
                }
                if (transmitChanged) {
                    if (actor.transmitRange > 0.0) {
                        manager.trackWaypoint(actor.entity);
                    } else {
                        manager.untrackWaypoint(actor.entity);
                    }
                }
            });
        }

        // Entity.setPosRaw
        private void move(final Actor actor, final Vec3 position) throws Exception {
            if (position.equals(actor.entity.position)) {
                return;
            }
            this.place(actor, position);
            if (!(boolean) getField(Entity.class, actor.entity, "firstTick") && (actor.tracked || this.random.nextInt(4) == 0)) {
                this.both(actor.level, manager -> {
                    if (actor.entity.isTransmittingWaypoint()) {
                        manager.updateWaypoint(actor.entity);
                    }
                    if (actor.player != null && actor.player.isReceivingWaypoints()) {
                        manager.updatePlayer(actor.player);
                    }
                });
            }
        }

        private void place(final Actor actor, final Vec3 position) throws Exception {
            actor.entity.position = position;
            final BlockPos block = BlockPos.containing(position);
            if (!block.equals(actor.entity.blockPosition)) actor.entity.blockPosition = block;
            final ChunkPos chunk = ChunkPos.containing(block);
            if (!chunk.equals(actor.entity.chunkPosition())) setField(Entity.class, actor.entity, "chunkPosition", chunk);
        }

        private Vec3 randomPosition() {
            final double spread = this.random.nextInt(6) == 0 ? 700.0 : 40.0;
            return new Vec3((this.random.nextDouble() - 0.5) * spread, 60.0 + this.random.nextInt(8), (this.random.nextDouble() - 0.5) * spread);
        }

        private Vec3 nextPosition(final Actor actor) {
            final Vec3 from = actor.entity.position;
            final int kind = this.random.nextInt(20);
            if (kind == 0) {
                return this.randomPosition();
            }
            final double reach = kind < 13 ? 0.45 : kind < 17 ? 3.0 : 40.0;
            final double x = from.x + (this.random.nextDouble() - 0.5) * 2.0 * reach;
            final double z = from.z + (this.random.nextDouble() - 0.5) * 2.0 * reach;
            // Half the short moves end exactly on a block edge.
            return new Vec3(kind < 6 ? Mth.floor(x) : x, from.y + (kind % 3 == 0 ? this.random.nextInt(3) - 1 : 0), kind < 3 ? Mth.floor(z) : z);
        }

        private double pick(final double[] values) {
            return values[this.random.nextInt(values.length)];
        }
    }

    private static void setField(final Class<?> owner, final Object target, final String name, final Object value) throws Exception {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(final Class<?> owner, final Object target, final String name) throws Exception {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /** Paper's ServerWaypointManager as of 26.2, kept as the oracle. */
    private static final class TableBasedManager extends ServerWaypointManager {
        private final Set<WaypointTransmitter> waypoints = new HashSet<>();
        private final Set<ServerPlayer> players = new HashSet<>();
        private final Table<ServerPlayer, WaypointTransmitter, WaypointTransmitter.Connection> connections = HashBasedTable.create();

        TableBasedManager(final ServerLevel level) {
            super(level);
        }

        @Override
        public void trackWaypoint(final WaypointTransmitter waypoint) {
            this.waypoints.add(waypoint);

            if (!this.locatorBarEnabled) return;
            for (ServerPlayer player : this.players) {
                this.createConnection(player, waypoint);
            }
        }

        @Override
        public void updateWaypoint(final WaypointTransmitter waypoint) {
            if (!this.locatorBarEnabled) return;
            if (this.waypoints.contains(waypoint)) {
                Map<ServerPlayer, WaypointTransmitter.Connection> playerConnection = Tables.transpose(this.connections).row(waypoint);
                Sets.SetView<ServerPlayer> potentialPlayers = Sets.difference(this.players, playerConnection.keySet());

                for (Map.Entry<ServerPlayer, WaypointTransmitter.Connection> waypointConnection : ImmutableSet.copyOf(playerConnection.entrySet())) {
                    this.updateConnection(waypointConnection.getKey(), waypoint, waypointConnection.getValue());
                }

                for (ServerPlayer player : potentialPlayers) {
                    this.createConnection(player, waypoint);
                }
            }
        }

        @Override
        public void untrackWaypoint(final WaypointTransmitter waypoint) {
            this.connections.column(waypoint).forEach((player, connection) -> connection.disconnect());
            Tables.transpose(this.connections).row(waypoint).clear();
            this.waypoints.remove(waypoint);
        }

        @Override
        public void addPlayer(final ServerPlayer player) {
            this.players.add(player);

            if (this.locatorBarEnabled) {
                for (WaypointTransmitter waypoint : this.waypoints) {
                    this.createConnection(player, waypoint);
                }
            }

            if (player.isTransmittingWaypoint()) {
                this.trackWaypoint(player);
            }
        }

        @Override
        public void updatePlayer(final ServerPlayer player) {
            if (!this.locatorBarEnabled) return;
            Map<WaypointTransmitter, WaypointTransmitter.Connection> waypointConnections = this.connections.row(player);
            Sets.SetView<WaypointTransmitter> potentialWaypoints = Sets.difference(this.waypoints, waypointConnections.keySet());

            for (Map.Entry<WaypointTransmitter, WaypointTransmitter.Connection> waypointConnection : ImmutableSet.copyOf(waypointConnections.entrySet())) {
                this.updateConnection(player, waypointConnection.getKey(), waypointConnection.getValue());
            }

            for (WaypointTransmitter waypoint : potentialWaypoints) {
                this.createConnection(player, waypoint);
            }
        }

        @Override
        public void removePlayer(final ServerPlayer player) {
            this.connections.row(player).values().removeIf(connection -> {
                connection.disconnect();
                return true;
            });
            this.untrackWaypoint(player);
            this.players.remove(player);
        }

        @Override
        public void breakAllConnections() {
            this.connections.values().forEach(WaypointTransmitter.Connection::disconnect);
            this.connections.clear();
        }

        @Override
        public void remakeConnections(final WaypointTransmitter waypoint) {
            if (!this.locatorBarEnabled) return;
            for (ServerPlayer player : this.players) {
                this.createConnection(player, waypoint);
            }
        }

        @Override
        public Set<WaypointTransmitter> transmitters() {
            return this.waypoints;
        }

        private void createConnection(final ServerPlayer player, final WaypointTransmitter waypoint) {
            if (player != waypoint) {
                if (this.locatorBarEnabled) {
                    waypoint.makeWaypointConnectionWith(player).ifPresentOrElse(connection -> {
                        this.connections.put(player, waypoint, connection);
                        connection.connect();
                    }, () -> {
                        WaypointTransmitter.Connection connection = this.connections.remove(player, waypoint);
                        if (connection != null) {
                            connection.disconnect();
                        }
                    });
                }
            }
        }

        private void updateConnection(final ServerPlayer player, final WaypointTransmitter waypoint, final WaypointTransmitter.Connection connection) {
            if (player != waypoint) {
                if (this.locatorBarEnabled) {
                    if (!connection.isBroken()) {
                        connection.update();
                    } else {
                        waypoint.makeWaypointConnectionWith(player).ifPresentOrElse(newConnection -> {
                            newConnection.connect();
                            this.connections.put(player, waypoint, newConnection);
                        }, () -> {
                            connection.disconnect();
                            this.connections.remove(player, waypoint);
                        });
                    }
                }
            }
        }
    }
}
